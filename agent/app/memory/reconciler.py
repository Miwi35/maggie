"""The reconciler: make the Postgres index say what the bucket says.

One pass costs one listing (ListObjectsV2, every page) and one GET per file whose ETag moved. It
reads only `<user>/*.md` and `<user>/archive/*.md` — never `conflits/`, `SOMMAIRE.md` or
`competences/` — and it is the only code that deletes index rows, which it does softly and only
when the whole pass succeeded.

Races it is built around:
- the index is loaded *before* the listing, and every decision is re-checked against the fresh row
  under the path lock, so a write Maggie made meanwhile is never undone;
- a file whose fetched ETag equals the row's is the write door's own work: skipped;
- a row whose ETag is neither the one the pass loaded nor the one it fetched changed during the
  pass (the write door got there first): left for the next pass, never reverted;
- one file that cannot be applied is logged and counted, makes the pass incomplete (no deletion)
  and never stops the others;
- a row with `pending_sync` (newest version still in the outbox) is never deleted;
- losing more than half of a user's notes at once (and at least five) is treated as a broken
  listing, not as the owner deleting everything: nothing is deleted and it is logged as critical.
"""

import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime

from app.db.memory_note_model import MemoryNote
from app.db.memory_note_repository import MemoryNoteRepository, memory_note_repo
from app.memory.bucket import (
    Bucket,
    BucketUnavailable,
    ObjectData,
    ObjectInfo,
    ObjectNotFound,
    PreconditionFailed,
)
from app.memory.frontmatter import NoteDoc, UnreadableNote, parse_note, render_note
from app.memory.index import Change, apply_doc
from app.memory.locks import PathLocks
from app.memory.notifier import MemoryNotifier
from app.memory.paths import OwnedKey, classify_key, stem
from app.memory.ulid import new_ulid

logger = logging.getLogger(__name__)

MASS_DELETION_MIN = 5
MAX_PATH_LENGTH = 600  # the `path` column


@dataclass
class PassReport:
    listed: int = 0
    fetched: int = 0
    indexed: int = 0
    soft_deleted: int = 0
    unreadable: int = 0
    stamped: int = 0
    duplicates: int = 0
    errors: int = 0
    incomplete: bool = False
    blocked_deletions: dict[str, int] = field(default_factory=dict)
    changed_users: set[str] = field(default_factory=set)

    def to_counts(self) -> dict:
        """Totals only: a pass covers every user, so nothing here may be keyed by or name one."""
        return {**self.to_dict(), "blockedDeletions": sum(self.blocked_deletions.values())}

    def to_dict(self) -> dict:
        return {
            "listed": self.listed,
            "fetched": self.fetched,
            "indexed": self.indexed,
            "softDeleted": self.soft_deleted,
            "unreadable": self.unreadable,
            "stamped": self.stamped,
            "duplicates": self.duplicates,
            "errors": self.errors,
            "incomplete": self.incomplete,
            "blockedDeletions": dict(self.blocked_deletions),
        }


@dataclass(frozen=True)
class _RowView:
    id: str
    user_id: str
    path: str
    etag: str
    pending_sync: bool
    unreadable: bool


@dataclass(frozen=True)
class _Snapshot:
    """The live rows as loaded before the listing: what the pass believes it is replacing."""

    by_path: dict[tuple[str, str], _RowView]
    by_id: dict[str, _RowView]


@dataclass
class _Fetched:
    owned: OwnedKey
    data: ObjectData
    doc: NoteDoc | None = None
    error: str | None = None


class Reconciler:
    def __init__(
        self,
        bucket: Bucket,
        repo: MemoryNoteRepository | None = None,
        locks: PathLocks | None = None,
        notifier: MemoryNotifier | None = None,
    ) -> None:
        self.bucket = bucket
        self.repo = repo or memory_note_repo
        self.locks = locks or PathLocks()
        self.notifier = notifier or MemoryNotifier()

    async def reconcile(self, quiet: bool = False) -> PassReport:
        """One pass. `BucketUnavailable` from the listing or a fetch propagates: the pass failed, nothing deleted."""
        report = PassReport()
        async with self.repo.session() as session:
            before = await self.repo.all_notes(session)
        live = [row for row in before if row.deleted_at is None]
        views = [self._view(r) for r in live]
        live_by_path = {(v.user_id, v.path): v for v in views}
        snapshot = _Snapshot(live_by_path, {v.id: v for v in views})

        infos = await self.bucket.list_objects("")
        report.listed = len(infos)
        listed_keys = {info.key for info in infos}
        owned: dict[str, tuple[OwnedKey, ObjectInfo]] = {}
        for info in infos:
            classified = classify_key(info.key)
            if classified is None:
                continue
            if len(info.key) > MAX_PATH_LENGTH:
                logger.warning(f"Memory reconcile: {info.key[:80]}… exceeds {MAX_PATH_LENGTH} characters, skipped")
                report.unreadable += 1
                continue
            owned[info.key] = (classified, info)

        fetched: dict[str, _Fetched] = {}
        for key in sorted(owned):
            classified, info = owned[key]
            row = live_by_path.get((classified.user_id, key))
            if row is not None and row.etag == info.etag:
                continue
            try:
                data = await self.bucket.get(key)
            except ObjectNotFound:
                report.incomplete = True
                continue
            report.fetched += 1
            fetched[key] = self._parse(classified, data)

        occupancy: dict[str, list[str]] = {}
        for key, item in fetched.items():
            if item.doc is not None and item.doc.id:
                occupancy.setdefault(item.doc.id, []).append(key)
        for key, (classified, _info) in owned.items():
            row = live_by_path.get((classified.user_id, key))
            if key not in fetched and row is not None and not row.unreadable:
                occupancy.setdefault(row.id, []).append(key)

        for key in sorted(fetched):
            try:
                changes = await self._apply(fetched[key], occupancy, listed_keys, snapshot, report, quiet)
                await self._announce(changes, report, quiet)
            except Exception:
                logger.exception(f"Memory reconcile: could not apply {key}, skipped")
                report.errors += 1
                report.incomplete = True

        if report.incomplete:
            logger.warning("Memory reconcile: the pass was incomplete, deletions skipped until the next one")
        else:
            await self._delete_missing(live, listed_keys, report, quiet)
        return report

    @staticmethod
    def _view(row: MemoryNote) -> _RowView:
        return _RowView(
            id=str(row.id),
            user_id=str(row.user_id),
            path=str(row.path),
            etag=str(row.etag),
            pending_sync=bool(row.pending_sync),
            unreadable=bool(row.unreadable),
        )

    @staticmethod
    def _parse(owned: OwnedKey, data: ObjectData) -> _Fetched:
        try:
            text = data.body.decode("utf-8")
            if "\x00" in text:
                return _Fetched(owned, data, error="file contains a NUL byte")
            return _Fetched(owned, data, doc=parse_note(text, stem(owned.key)))
        except UnicodeDecodeError:
            return _Fetched(owned, data, error="file is not valid UTF-8")
        except UnreadableNote as e:
            return _Fetched(owned, data, error=str(e))

    async def _announce(self, changes: list[Change], report: PassReport, quiet: bool) -> None:
        for change in changes:
            report.changed_users.add(change.user_id)
        if not quiet:
            await self.notifier.publish(changes)

    async def _apply(
        self,
        item: _Fetched,
        occupancy: dict[str, list[str]],
        listed_keys: set[str],
        snapshot: _Snapshot,
        report: PassReport,
        quiet: bool,
    ) -> list[Change]:
        owned, key = item.owned, item.owned.key
        user_id = owned.user_id
        async with self.locks.hold(user_id, key), self.repo.session() as session:
            here = await self.repo.live_note_at(session, user_id, key)
            if here is not None and here.etag == item.data.etag and not here.unreadable:
                return []  # the write door recorded exactly this version while we were fetching
            if here is not None and self._moved_on(here, snapshot.by_path.get((user_id, key)), item.data.etag):
                return []

            if item.error is not None or item.doc is None:
                return await self._record_unreadable(session, item, here, report, quiet)

            doc, data = item.doc, item.data
            note_id, reason = await self._resolve_id(session, owned, doc, here, occupancy)
            existing = await self.repo.note_by_id(session, user_id, note_id)
            if (
                existing is not None
                and existing.deleted_at is None
                and self._moved_on(existing, snapshot.by_id.get(str(existing.id)), data.etag)
            ):
                return []
            if reason is not None:
                data = await self._stamp(owned, data, doc, note_id)
                if data is None:
                    return []
                report.stamped += 1 if reason == "id_stamped" else 0
                report.duplicates += 1 if reason == "duplicate_id" else 0
                if not quiet:
                    await self.repo.add_event(session, user_id, reason, note_id, key, {"etag": data.etag})

            if existing is not None and existing.pending_sync and existing.path != key:
                return []  # a move still in the outbox: the old file is expected to be there until it is flushed
            changes: list[Change] = []
            if here is not None and (existing is None or here.id != existing.id):
                if here.unreadable:
                    await session.delete(here)
                elif not any(k != key for k in occupancy.get(str(here.id), [])):
                    here.deleted_at = datetime.now(UTC)
                    changes.append(Change("deleted", user_id, str(here.id), key, str(here.title), str(here.status)))
                    if not quiet:
                        await self.repo.add_event(session, user_id, "deleted", str(here.id), key, {"replaced": True})
                    report.soft_deleted += 1

            row, kind = apply_doc(
                existing,
                note_id=note_id,
                user_id=user_id,
                owned=owned,
                doc=doc,
                etag=data.etag,
                last_modified=data.last_modified,
            )
            if existing is None:
                session.add(row)
            if kind is not None:
                changes.append(Change(kind, user_id, note_id, key, doc.title, owned.status))
                if not quiet:
                    await self.repo.add_event(session, user_id, kind, note_id, key, {"etag": data.etag})
            report.indexed += 1
            await session.commit()
            return changes

    @staticmethod
    def _moved_on(row: MemoryNote, seen: _RowView | None, fetched_etag: str) -> bool:
        """The row's ETag is neither the one this pass loaded nor the one it fetched: someone wrote it meanwhile."""
        return row.etag != fetched_etag and (seen is None or row.etag != seen.etag)

    async def _resolve_id(
        self,
        session: object,
        owned: OwnedKey,
        doc: NoteDoc,
        here: MemoryNote | None,
        occupancy: dict[str, list[str]],
    ) -> tuple[str, str | None]:
        """(the note's id, why it needs writing back — None when the file's own id stands).

        An id-less file keeps the id of the row that was at its path (the owner removed it by hand),
        else gets a new one. An id that two files carry belongs to the file the index already
        tracks, or failing that to the first path in order; the others become new notes.
        """
        if doc.id is None:
            if here is not None and not here.unreadable:
                return str(here.id), "id_stamped"
            return await self._fresh_id(session), "id_stamped"
        keys = occupancy.get(doc.id, [])
        if len(keys) > 1:
            tracked = await self.repo.note_by_id(session, owned.user_id, doc.id)  # type: ignore[arg-type]
            winner = tracked.path if tracked is not None and tracked.path in keys else min(keys)
            if owned.key != winner:
                return await self._fresh_id(session), "duplicate_id"
        other = await self.repo.note_by_id_any_user(session, doc.id)  # type: ignore[arg-type]
        if other is not None and other.user_id != owned.user_id:
            return await self._fresh_id(session), "duplicate_id"
        return doc.id, None

    async def _fresh_id(self, session: object) -> str:
        while True:
            candidate = new_ulid()
            if not await self.repo.note_id_taken(session, candidate):  # type: ignore[arg-type]
                return candidate

    async def _stamp(self, owned: OwnedKey, data: ObjectData, doc: NoteDoc, note_id: str) -> ObjectData | None:
        """Write the id into the file, conditioned on the version we read. A lost race or an outage: try next pass."""
        doc.id = note_id
        text = render_note(doc).encode("utf-8")
        try:
            etag = await self.bucket.put(owned.key, text, if_match=data.etag)
        except (PreconditionFailed, BucketUnavailable) as e:
            logger.info(f"Memory reconcile: could not write an id back to {owned.key} ({e}); will retry")
            return None
        last_modified = datetime.now(UTC)
        try:
            info = await self.bucket.head(owned.key)
            if info is not None and info.etag == etag:
                last_modified = info.last_modified
        except BucketUnavailable:
            pass
        return ObjectData(owned.key, text, etag, last_modified)

    async def _record_unreadable(
        self, session: object, item: _Fetched, here: MemoryNote | None, report: PassReport, quiet: bool
    ) -> list[Change]:
        owned = item.owned
        reason = item.error or "unreadable"
        logger.warning(f"Memory reconcile: {owned.key} is unreadable: {reason}")
        if here is None:
            here = MemoryNote(
                id=await self._fresh_id(session),
                user_id=owned.user_id,
                path=owned.key,
                title=stem(owned.key),
                use_count=0,
            )
            session.add(here)  # type: ignore[attr-defined]
        here.status = owned.status
        here.etag = item.data.etag
        here.updated_at = item.data.last_modified
        here.indexed_at = datetime.now(UTC)
        here.unreadable = True
        here.unreadable_reason = reason
        here.deleted_at = None
        report.unreadable += 1
        if not quiet:
            await self.repo.add_event(
                session,  # type: ignore[arg-type]
                owned.user_id,
                "unreadable",
                str(here.id),
                owned.key,
                {"reason": reason},
            )
        await session.commit()  # type: ignore[attr-defined]
        return [Change("unreadable", owned.user_id, str(here.id), owned.key, str(here.title), owned.status)]

    async def _delete_missing(
        self, live: list[MemoryNote], listed_keys: set[str], report: PassReport, quiet: bool
    ) -> None:
        """Soft-delete the rows whose file is gone — unless the loss looks like a broken listing, not like the owner."""
        per_user: dict[str, list[MemoryNote]] = {}
        for row in live:
            per_user.setdefault(str(row.user_id), []).append(row)
        for user_id, rows in per_user.items():
            missing = [r for r in rows if r.path not in listed_keys and not r.pending_sync]
            if not missing:
                continue
            if len(missing) >= MASS_DELETION_MIN and len(missing) * 2 > len(rows):
                report.blocked_deletions[user_id] = len(missing)
                logger.critical(
                    f"Memory reconcile: {len(missing)} of {len(rows)} notes of user {user_id} vanished from the "
                    "bucket at once; treating the listing as broken, nothing deleted"
                )
                async with self.repo.session() as session:
                    detail = {"missing": len(missing), "of": len(rows)}
                    await self.repo.add_event(session, user_id, "mass_deletion_blocked", None, None, detail)
                    await session.commit()
                continue
            for row in missing:
                changes = await self._soft_delete(user_id, row, quiet, report)
                await self._announce(changes, report, quiet)

    async def _soft_delete(self, user_id: str, seen: MemoryNote, quiet: bool, report: PassReport) -> list[Change]:
        async with self.locks.hold(user_id, str(seen.path)), self.repo.session() as session:
            row = await self.repo.note_by_id(session, user_id, str(seen.id))
            unchanged = (
                row is not None
                and row.deleted_at is None
                and row.path == seen.path
                and row.etag == seen.etag
                and not row.pending_sync
            )
            if row is None or not unchanged:
                return []  # moved, rewritten or queued since the index was loaded: not ours to delete
            row.deleted_at = datetime.now(UTC)
            if not quiet:
                await self.repo.add_event(session, user_id, "deleted", str(row.id), str(row.path), {})
            await session.commit()
            report.soft_deleted += 1
            return [Change("deleted", user_id, str(row.id), str(row.path), str(row.title), str(row.status))]

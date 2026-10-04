"""The write door: the only code that changes a note file on Maggie's behalf.

A write is a conditional PUT of the Markdown file, then the index row updated with the ETag the PUT
returned — in one unit of work, so the reconciler recognises it as our own and never reads it back
as an owner's edit. Three things can go otherwise, each with one answer:

- the bucket is unreachable or slow past the turn bound: the write is queued in the outbox and the
  index row serves the new content marked `pending_sync` (degraded mode);
- the file is no longer the version we read (412, or a different ETag on flush): the owner edited
  first. His version stays; ours goes to `<user>/conflits/<slug>-<timestamp>.md`;
- the name is taken (creation): the next free `-2`, `-3`… suffix.

The outbox is flushed by the sync loop, comparing the bucket's current ETag with the one the writer
had read before pushing anything.
"""

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime

from app.db.memory_note_model import MemoryNote, MemoryOutbox
from app.db.memory_note_repository import MemoryNoteRepository, memory_note_repo
from app.memory.bucket import Bucket, BucketUnavailable, PreconditionFailed
from app.memory.frontmatter import NoteDoc, parse_note, render_note
from app.memory.index import Change, apply_doc
from app.memory.locks import PathLocks
from app.memory.notifier import MemoryNotifier
from app.memory.paths import (
    OwnedKey,
    archive_key,
    check_user_id,
    classify_key,
    conflict_key,
    note_key,
    slugify,
    stem,
    with_suffix,
)
from app.memory.summary import SummaryWriter
from app.memory.ulid import new_ulid

logger = logging.getLogger(__name__)

MAX_NAME_ATTEMPTS = 50


class NoteNotFound(LookupError):
    pass


class _NameTaken(Exception):
    pass


@dataclass
class WriteResult:
    status: str  # written | pending | conflict
    note_id: str
    path: str
    etag: str | None = None
    conflict_path: str | None = None


@dataclass
class FlushReport:
    pushed: int = 0
    conflicts: int = 0
    dropped: int = 0
    remaining: int = 0


def _doc_from_row(row: MemoryNote) -> NoteDoc:
    return NoteDoc(
        id=str(row.id),
        title=str(row.title),
        body=str(row.body),
        summary=str(row.summary or ""),
        tags=list(row.tags or []),
        links=list(row.links or []),
        sources=list(row.sources or []),
        pinned=bool(row.pinned),
        importance=int(row.importance or 0),
        confirmed_at=row.confirmed_at,
        forget_after=row.forget_after,
        created_at=row.created_at,
        archived_at=row.archived_at,
        archive_reason=row.archive_reason,
    )


class NoteStore:
    def __init__(
        self,
        bucket: Bucket,
        repo: MemoryNoteRepository | None = None,
        locks: PathLocks | None = None,
        notifier: MemoryNotifier | None = None,
        summary: SummaryWriter | None = None,
        timeout_seconds: float | None = None,
    ) -> None:
        self.bucket = bucket
        self.repo = repo or memory_note_repo
        self.locks = locks or PathLocks()
        self.notifier = notifier or MemoryNotifier()
        self.summary = summary or SummaryWriter(bucket, self.repo)
        # Mid-turn bound: a PUT slower than this is treated as an outage and queued.
        self.timeout_seconds = timeout_seconds

    # ------------------------------------------------------------------ public

    async def save(self, user_id: str, doc: NoteDoc) -> WriteResult:
        """Create a note (`doc.id` None or unknown) or update the one with that id, keeping its file name."""
        check_user_id(user_id)
        row = None
        if doc.id:
            async with self.repo.session() as session:
                row = await self.repo.note_by_id(session, user_id, doc.id)
            if row is not None and row.deleted_at is not None:
                row = None
        if row is None:
            return await self._create(user_id, doc)
        return await self._update(user_id, row, doc)

    async def archive(self, user_id: str, note_id: str, reason: str | None = None) -> WriteResult:
        row = await self._live_row(user_id, note_id)
        doc = _doc_from_row(row)
        doc.archived_at = datetime.now(UTC)
        doc.archive_reason = reason
        return await self._move(user_id, row, doc, to_status="archived")

    async def restore(self, user_id: str, note_id: str) -> WriteResult:
        row = await self._live_row(user_id, note_id)
        doc = _doc_from_row(row)
        doc.archived_at = None
        doc.archive_reason = None
        return await self._move(user_id, row, doc, to_status="active")

    async def flush_outbox(self) -> FlushReport:
        """Push the queued writes, oldest first. Stops at the first outage: what is left stays queued."""
        report = FlushReport()
        async with self.repo.session() as session:
            queued = [(str(o.user_id), str(o.path), o.id) for o in await self.repo.outbox_all(session)]
        for user_id, path, _id in queued:
            try:
                await self._flush_one(user_id, path, report)
            except BucketUnavailable as e:
                logger.warning(f"Memory outbox flush interrupted: {e}")
                break
        report.remaining = await self.repo.outbox_size()
        return report

    # ---------------------------------------------------------------- creation

    async def _create(self, user_id: str, doc: NoteDoc) -> WriteResult:
        doc.id = doc.id if doc.id else None
        base = slugify(doc.title)
        doc.created_at = doc.created_at or datetime.now(UTC)
        for attempt in range(1, MAX_NAME_ATTEMPTS + 1):
            slug = with_suffix(base, attempt)
            key = note_key(user_id, slug)
            async with self.repo.session() as session:
                taken = await self.repo.live_note_at(session, user_id, key) or await self.repo.outbox_at(
                    session, user_id, key
                )
            if taken is not None:
                continue
            candidate = NoteDoc(**{**doc.__dict__, "id": await self._new_id()})
            owned = classify_key(key)
            assert owned is not None
            try:
                return await self._write(user_id, candidate, owned, base_etag=None)
            except _NameTaken:
                continue
        raise RuntimeError(f"No free file name for {doc.title!r} after {MAX_NAME_ATTEMPTS} attempts")

    async def _new_id(self) -> str:
        async with self.repo.session() as session:
            while True:
                candidate = new_ulid()
                if not await self.repo.note_id_taken(session, candidate):
                    return candidate

    # ------------------------------------------------------------------ update

    async def _live_row(self, user_id: str, note_id: str) -> MemoryNote:
        check_user_id(user_id)
        async with self.repo.session() as session:
            row = await self.repo.note_by_id(session, user_id, note_id)
        if row is None or row.deleted_at is not None:
            raise NoteNotFound(note_id)
        return row

    async def _update(self, user_id: str, row: MemoryNote, doc: NoteDoc) -> WriteResult:
        owned = classify_key(str(row.path))
        if owned is None:
            raise NoteNotFound(str(row.id))
        merged = NoteDoc(**{**doc.__dict__, "id": str(row.id)})
        merged.created_at = doc.created_at or row.created_at
        if owned.status == "archived":
            merged.archived_at = doc.archived_at or row.archived_at
            merged.archive_reason = doc.archive_reason or row.archive_reason
        return await self._write(user_id, merged, owned, base_etag=str(row.etag) or None, existing_note=True)

    async def _move(self, user_id: str, row: MemoryNote, doc: NoteDoc, to_status: str) -> WriteResult:
        source = classify_key(str(row.path))
        if source is None:
            raise NoteNotFound(str(row.id))
        if source.status == to_status:
            return WriteResult("written", str(row.id), str(row.path), str(row.etag))
        base = stem(source.key)
        build = archive_key if to_status == "archived" else note_key
        for attempt in range(1, MAX_NAME_ATTEMPTS + 1):
            key = build(user_id, with_suffix(base, attempt))
            async with self.repo.session() as session:
                taken = await self.repo.live_note_at(session, user_id, key) or await self.repo.outbox_at(
                    session, user_id, key
                )
            if taken is not None:
                continue
            owned = classify_key(key)
            assert owned is not None
            try:
                return await self._write(
                    user_id,
                    doc,
                    owned,
                    base_etag=None,
                    move_from=source.key,
                    move_from_etag=str(row.etag) or None,
                )
            except _NameTaken:
                continue
        raise RuntimeError(f"No free file name to move {source.key} after {MAX_NAME_ATTEMPTS} attempts")

    # -------------------------------------------------------------- the write

    async def _put(self, key: str, text: str, base_etag: str | None) -> str:
        create_only = not base_etag
        coro = self.bucket.put(
            key, text.encode("utf-8"), if_match=None if create_only else base_etag, if_none_match=create_only
        )
        try:
            if self.timeout_seconds:
                return await asyncio.wait_for(coro, timeout=self.timeout_seconds)
            return await coro
        except TimeoutError as e:
            raise BucketUnavailable(f"PUT {key} took longer than {self.timeout_seconds}s") from e

    async def _write(
        self,
        user_id: str,
        doc: NoteDoc,
        owned: OwnedKey,
        base_etag: str | None,
        move_from: str | None = None,
        move_from_etag: str | None = None,
        existing_note: bool = False,
    ) -> WriteResult:
        key = owned.key
        paths = (key, move_from) if move_from else (key,)
        text = render_note(doc)
        async with self.locks.hold(user_id, *paths):
            async with self.repo.session() as session:
                if existing_note:
                    # The version this save read may be one a concurrent save of the same note has just replaced.
                    current = await self.repo.note_by_id(session, user_id, str(doc.id))
                    if current is not None and current.path == key and current.deleted_at is None:
                        base_etag = str(current.etag) or None
                queued = await self.repo.outbox_at(session, user_id, key)
            if queued is not None:
                # An earlier write to this note is still waiting: keep the order, the flush pushes both as one.
                return await self._queue(user_id, doc, owned, text, base_etag, move_from, move_from_etag)
            try:
                etag = await self._put(key, text, base_etag)
            except BucketUnavailable as e:
                logger.warning(f"Bucket unavailable writing {key}, queued: {e}")
                return await self._queue(user_id, doc, owned, text, base_etag, move_from, move_from_etag)
            except PreconditionFailed:
                if not base_etag and not existing_note:
                    raise _NameTaken(key) from None
                try:
                    return await self._conflict(user_id, doc, owned, text, base_etag or "")
                except BucketUnavailable as e:
                    logger.warning(f"Bucket unavailable resolving a conflict on {key}, queued: {e}")
                    return await self._queue(user_id, doc, owned, text, base_etag, move_from, move_from_etag)
            changes = await self._record_success(user_id, doc, owned, etag, move_from, move_from_etag)
        await self._after(user_id, changes)
        return WriteResult("written", str(doc.id), key, etag)

    async def _record_success(
        self,
        user_id: str,
        doc: NoteDoc,
        owned: OwnedKey,
        etag: str,
        move_from: str | None,
        move_from_etag: str | None,
        drop_outbox: bool = True,
    ) -> list[Change]:
        """Index the version just written, under the path lock: the ETag we got back is the one the index keeps."""
        key = owned.key
        last_modified = datetime.now(UTC)
        try:
            info = await self.bucket.head(key)
            if info is not None and info.etag == etag:
                last_modified = info.last_modified
        except BucketUnavailable:
            pass
        changes: list[Change] = []
        async with self.repo.session() as session:
            existing = await self.repo.note_by_id(session, user_id, str(doc.id))
            row, kind = apply_doc(
                existing,
                note_id=str(doc.id),
                user_id=user_id,
                owned=owned,
                doc=doc,
                etag=etag,
                last_modified=last_modified,
            )
            if existing is None:
                session.add(row)
            if drop_outbox:
                queued = await self.repo.outbox_at(session, user_id, key)
                if queued is not None:
                    await session.delete(queued)
            if kind is not None:
                await self.repo.add_event(session, user_id, kind, str(doc.id), key, {"etag": etag, "by": "maggie"})
                changes.append(Change(kind, user_id, str(doc.id), key, doc.title, owned.status))
            await session.commit()
        if move_from:
            await self._remove_original(user_id, str(doc.id), move_from, move_from_etag)
        return changes

    async def _remove_original(self, user_id: str, note_id: str, key: str, expected_etag: str | None) -> None:
        """Delete the file a note moved away from — only if it is still the version we moved."""
        try:
            current = await self.bucket.head(key)
            if current is None:
                return
            if expected_etag and current.etag != expected_etag:
                logger.warning(f"{key} changed while its note was being moved; left in place")
                async with self.repo.session() as session:
                    await self.repo.add_event(session, user_id, "move_left_original", note_id, key, {})
                    await session.commit()
                return
            await self.bucket.delete(key)
        except BucketUnavailable:
            async with self.repo.session() as session:
                if await self.repo.outbox_at(session, user_id, key) is None:
                    session.add(
                        MemoryOutbox(
                            user_id=user_id,
                            note_id=note_id,
                            path=key,
                            op="delete",
                            content="",
                            base_etag=expected_etag,
                        )
                    )
                await session.commit()

    async def _after(self, user_id: str, changes: list[Change]) -> None:
        await self.notifier.publish(changes)
        try:
            await self.summary.refresh(user_id)
        except Exception as e:
            logger.warning(f"Memory summary refresh failed for {user_id}: {e}")

    # --------------------------------------------------------- degraded + conflict

    async def _queue(
        self,
        user_id: str,
        doc: NoteDoc,
        owned: OwnedKey,
        text: str,
        base_etag: str | None,
        move_from: str | None,
        move_from_etag: str | None,
    ) -> WriteResult:
        """Degraded mode: the index serves the new content and the outbox holds the write, in one commit."""
        key = owned.key
        async with self.repo.session() as session:
            existing = await self.repo.note_by_id(session, user_id, str(doc.id))
            previous_etag = str(existing.etag) if existing is not None else ""
            row, kind = apply_doc(
                existing,
                note_id=str(doc.id),
                user_id=user_id,
                owned=owned,
                doc=doc,
                etag=previous_etag,
                last_modified=datetime.now(UTC),
            )
            row.pending_sync = True
            if existing is None:
                session.add(row)
            queued = await self.repo.outbox_at(session, user_id, key)
            if queued is None:
                session.add(
                    MemoryOutbox(
                        user_id=user_id,
                        note_id=str(doc.id),
                        path=key,
                        op="put",
                        content=text,
                        base_etag=base_etag,
                        create_only=not base_etag,
                        move_from=move_from,
                        move_from_etag=move_from_etag,
                    )
                )
            else:
                queued.content = text  # base_etag stays the one first read: that is what the flush compares
                queued.move_from = queued.move_from or move_from
                queued.move_from_etag = queued.move_from_etag or move_from_etag
            await self.repo.add_event(session, user_id, "queued", str(doc.id), key, {"base_etag": base_etag})
            await session.commit()
        changes = [Change(kind, user_id, str(doc.id), key, doc.title, owned.status)] if kind else []
        await self.notifier.publish(changes)
        return WriteResult("pending", str(doc.id), key)

    async def _conflict(self, user_id: str, doc: NoteDoc, owned: OwnedKey, text: str, base_etag: str) -> WriteResult:
        """The owner edited first. His version stays; Maggie's goes to conflits/ and the index reads his."""
        key = owned.key
        current = await self.bucket.head(key)
        if current is None:
            # He deleted it: nothing to conflict with, so Maggie's version recreates the file.
            try:
                etag = await self._put(key, text, None)
            except PreconditionFailed:
                current = await self.bucket.head(key)
            else:
                changes = await self._record_success(user_id, doc, owned, etag, None, None)
                await self._after(user_id, changes)
                return WriteResult("written", str(doc.id), key, etag)
        copy_key = conflict_key(user_id, stem(key), datetime.now(UTC))
        await self.bucket.put(copy_key, text.encode("utf-8"))
        async with self.repo.session() as session:
            row = await self.repo.note_by_id(session, user_id, str(doc.id))
            if row is not None:
                row.etag = ""  # the next pass reads the owner's version over whatever the index held
                row.pending_sync = False
            await self.repo.add_event(
                session,
                user_id,
                "conflict",
                str(doc.id),
                key,
                {"conflict_path": copy_key, "base_etag": base_etag, "owner_etag": current.etag if current else None},
            )
            await session.commit()
        logger.warning(f"Write conflict on {key}: Maggie's version kept at {copy_key}")
        await self.notifier.publish([Change("conflict", user_id, str(doc.id), key, doc.title, owned.status)])
        return WriteResult("conflict", str(doc.id), key, conflict_path=copy_key)

    # --------------------------------------------------------------- the flush

    async def _flush_one(self, user_id: str, path: str, report: FlushReport) -> None:
        async with self.repo.session() as session:
            item = await self.repo.outbox_at(session, user_id, path)
            if item is None:
                return
            move_from = str(item.move_from) if item.move_from else None
        async with self.locks.hold(user_id, path, *([move_from] if move_from else [])):
            async with self.repo.session() as session:
                item = await self.repo.outbox_at(session, user_id, path)
                if item is None:
                    return
                op, content = str(item.op), str(item.content)
                base_etag = str(item.base_etag) if item.base_etag else None
                create_only = bool(item.create_only)
                move_etag = str(item.move_from_etag) if item.move_from_etag else None
                note_id = str(item.note_id) if item.note_id else None
            if op == "delete":
                await self._flush_delete(user_id, path, base_etag, report)
                return
            await self._flush_put(user_id, path, content, base_etag, create_only, move_from, move_etag, note_id, report)

    async def _drop_outbox(self, user_id: str, path: str) -> None:
        async with self.repo.session() as session:
            queued = await self.repo.outbox_at(session, user_id, path)
            if queued is not None:
                await session.delete(queued)
            await session.commit()

    async def _flush_delete(self, user_id: str, path: str, expected_etag: str | None, report: FlushReport) -> None:
        current = await self.bucket.head(path)
        if current is not None and (not expected_etag or current.etag == expected_etag):
            await self.bucket.delete(path)
            report.pushed += 1
        else:
            report.dropped += 1
        await self._drop_outbox(user_id, path)

    async def _flush_put(
        self,
        user_id: str,
        path: str,
        content: str,
        base_etag: str | None,
        create_only: bool,
        move_from: str | None,
        move_etag: str | None,
        note_id: str | None,
        report: FlushReport,
    ) -> None:
        owned = classify_key(path)
        if owned is None:
            await self._drop_outbox(user_id, path)
            report.dropped += 1
            return
        doc = parse_note(content, stem(path))
        current = await self.bucket.head(path)

        if current is not None and current.etag != (base_etag or ""):
            existing = await self.bucket.get(path)
            if existing.body.decode("utf-8", errors="replace") == content:
                etag = existing.etag  # a PUT that timed out on our side had landed
            elif create_only:
                await self._rename_queued(user_id, path, owned, content, doc, note_id)
                return
            else:
                await self._flush_conflict(user_id, path, owned, doc, content, base_etag, current.etag, report)
                return
        else:
            try:
                etag = await self._put(path, content, None if current is None else base_etag)
            except PreconditionFailed:
                if create_only:
                    await self._rename_queued(user_id, path, owned, content, doc, note_id)
                else:
                    current = await self.bucket.head(path)
                    await self._flush_conflict(
                        user_id, path, owned, doc, content, base_etag, current.etag if current else None, report
                    )
                return

        changes = await self._record_success(user_id, doc, owned, etag, move_from, move_etag)
        report.pushed += 1
        await self._after(user_id, changes)

    async def _flush_conflict(
        self,
        user_id: str,
        path: str,
        owned: OwnedKey,
        doc: NoteDoc,
        content: str,
        base_etag: str | None,
        owner_etag: str | None,
        report: FlushReport,
    ) -> None:
        """Pushing would overwrite an edit the owner made while we were queued: his stays, ours is kept aside."""
        copy_key = conflict_key(user_id, stem(path), datetime.now(UTC))
        await self.bucket.put(copy_key, content.encode("utf-8"))
        async with self.repo.session() as session:
            row = await self.repo.note_by_id(session, user_id, str(doc.id))
            if row is not None:
                row.etag = ""
                row.pending_sync = False
            queued = await self.repo.outbox_at(session, user_id, path)
            if queued is not None:
                await session.delete(queued)
            await self.repo.add_event(
                session,
                user_id,
                "conflict",
                str(doc.id),
                path,
                {"conflict_path": copy_key, "base_etag": base_etag, "owner_etag": owner_etag, "flushed": True},
            )
            await session.commit()
        report.conflicts += 1
        logger.warning(f"Outbox flush conflict on {path}: Maggie's version kept at {copy_key}")
        await self.notifier.publish([Change("conflict", user_id, str(doc.id), path, doc.title, owned.status)])

    async def _rename_queued(
        self, user_id: str, path: str, owned: OwnedKey, content: str, doc: NoteDoc, note_id: str | None
    ) -> None:
        """A queued creation found its name taken meanwhile: move it to the next free suffix, keep it queued."""
        build = archive_key if owned.status == "archived" else note_key
        base = stem(path)
        for attempt in range(2, MAX_NAME_ATTEMPTS + 2):
            candidate = build(user_id, with_suffix(base, attempt))
            async with self.repo.session() as session:
                busy = await self.repo.live_note_at(session, user_id, candidate) or await self.repo.outbox_at(
                    session, user_id, candidate
                )
                if busy is not None:
                    continue
                queued = await self.repo.outbox_at(session, user_id, path)
                row = await self.repo.note_by_id(session, user_id, note_id) if note_id else None
                if queued is not None:
                    queued.path = candidate
                if row is not None:
                    row.path = candidate
                await session.commit()
            return  # still queued, under its new name: the next flush pushes it
        logger.error(f"No free name found for queued note {path}")

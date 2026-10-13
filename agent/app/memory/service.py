"""The memory folder: a local copy of each user's Markdown files, kept in step with the bucket.

The files are the only truth. The bucket keeps them durably (versioned), the local copy is the cache
the agent works on, the Postgres index is a throwaway reading aid, and the outbox holds the writes
the bucket has not received yet. One synchronisation pass for one user, in this order:

1. **Push** the outbox. Before each push the object in the bucket is compared with the one the write
   started from: if the owner changed it since, **his version wins** — it is pulled, Maggie's is set
   aside in `Conflits/`, and the journal records both. Nothing is ever overwritten silently.
2. **Pull** whatever changed in the bucket, and remove what the owner deleted there.
3. **Index** whatever changed locally (a file edited in the bucket is re-read here), drop what is gone.
4. **Generate** `LISEZMOI.md`, `AGENTS.md`, `sommaire.md` — unless someone edited them since.

A bucket that does not answer makes the pass fail with `BucketUnavailable`: the user is marked
degraded (`critical` log, metrics), reads keep working from the local copy and the index, writes pile
up in the outbox, and the next pass that gets through pushes them. A listing is never trusted partial
or suddenly empty, so an outage cannot be mistaken for the owner deleting everything.
"""

import asyncio
import contextlib
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from app.config import settings
from app.db.memory_index_model import OP_APPEND, OP_DELETE, OP_PUT
from app.db.memory_index_repository import (
    KEEP,
    IndexedFile,
    Keep,
    OutboxEntry,
    memory_index_repo,
    memory_outbox_repo,
    memory_usage_repo,
)
from app.memory import carte as carte_mod
from app.memory import generated, paths
from app.memory.bucket import (
    Bucket,
    BucketError,
    BucketUnavailable,
    FakeBucket,
    ObjectNotFound,
    PreconditionFailed,
    S3Bucket,
)
from app.memory.folder import Known, LocalFolder, local_users, sha
from app.memory.indexer import index_file
from app.memory.ulid import is_ulid
from app.metrics import MEMORY_DEGRADED, MEMORY_OUTBOX_PENDING, MEMORY_SYNC_FAILURES, MEMORY_SYNC_FILES

logger = logging.getLogger(__name__)

MAX_PASSES = 3


@dataclass
class UserStatus:
    """What the process knows of the link between one user's folder and the bucket."""

    degraded_since: datetime | None = None
    last_error: str = ""
    last_ok: float | None = None  # monotonic
    last_ok_at: datetime | None = None


@dataclass
class PassReport:
    pulled: int = 0
    pushed: int = 0
    deleted: int = 0
    conflicts: int = 0
    reindexed: int = 0
    again: bool = False
    removed: set[str] = field(default_factory=set)

    def changed(self) -> bool:
        return bool(self.pulled or self.pushed or self.deleted or self.conflicts or self.reindexed)


class RemoteView(dict[str, str]):
    """rel -> ETag of what the bucket holds, remembering the key each object really has.

    A tool that decomposes accents (a Mac, rclone) leaves keys that differ from the normalised ones Maggie
    builds. Reading, rewriting or deleting such a file must go to the object that is there, not to a twin.
    """

    def __init__(self) -> None:
        super().__init__()
        self.raw_keys: dict[str, str] = {}

    def key(self, user_id: str, rel: str) -> str:
        if rel in self and rel in self.raw_keys:
            return self.raw_keys[rel]
        return paths.bucket_key(user_id, rel)


@dataclass
class RebuildReport:
    users: int = 0
    files: int = 0
    skipped: list[str] = field(default_factory=list)


def build_bucket() -> Bucket | None:
    """The bucket the settings describe: the in-memory one, the S3 one, or None when memory is off."""
    if settings.memory_provider == "fake":
        return FakeBucket()
    if not settings.memory_bucket:
        return None
    return S3Bucket(
        bucket=settings.memory_bucket,
        endpoint_url=settings.memory_endpoint,
        region=settings.memory_region,
        key_id=settings.memory_key_id,
        application_key=settings.memory_application_key,
        timeout_seconds=settings.memory_timeout_seconds,
        conditional_writes=settings.memory_conditional_writes,
    )


class MemoryService:
    def __init__(
        self,
        bucket: Bucket | None,
        root: Path,
        *,
        clock: Callable[[], datetime] | None = None,
        interval_seconds: float = 60,
        max_backoff_seconds: float = 300,
        turn_max_age_seconds: float = 30,
        turn_timeout_seconds: float = 2.0,
        push_after_write: bool = True,
    ) -> None:
        self.bucket = bucket
        self.push_after_write = push_after_write
        self.root = root
        self._clock = clock or (lambda: datetime.now(UTC))
        self.interval_seconds = interval_seconds
        self.max_backoff_seconds = max_backoff_seconds
        self.turn_max_age_seconds = turn_max_age_seconds
        self.turn_timeout_seconds = turn_timeout_seconds
        self._status: dict[str, UserStatus] = {}
        self._pass_locks: dict[str, asyncio.Lock] = {}
        self._file_locks: dict[str, asyncio.Lock] = {}
        self._tasks: dict[str, asyncio.Task] = {}
        self._known: set[str] = set()
        self._loop_task: asyncio.Task | None = None

    @property
    def enabled(self) -> bool:
        return self.bucket is not None

    def folder(self, user_id: str) -> LocalFolder:
        return LocalFolder(self.root, user_id)

    def status(self, user_id: str) -> UserStatus:
        return self._status.setdefault(user_id, UserStatus())

    def reset_status(self, user_id: str) -> None:
        self._status.pop(user_id, None)
        MEMORY_DEGRADED.set(1 if self.degraded() else 0)

    def _lock(self, locks: dict[str, asyncio.Lock], user_id: str) -> asyncio.Lock:
        # Locks bind to the event loop that contended for them: one per user, created on demand.
        return locks.setdefault(user_id, asyncio.Lock())

    # ------------------------------------------------------------------ what Maggie does with the folder

    async def read(self, user_id: str, rel: str) -> str | None:
        """The file's text: the local copy, else the index's copy of it (the bucket may be down)."""
        rel = paths.clean_relative(rel)
        text = self.folder(user_id).read(rel)
        if text is not None:
            return text
        indexed = await memory_index_repo.get(user_id, rel)
        return indexed.body if indexed else None

    async def search(self, user_id: str, query: str, limit: int = 10) -> list[IndexedFile]:
        paths.check_user_id(user_id)
        return await memory_index_repo.search(user_id, query, limit)

    async def indexed(self, user_id: str, rel: str) -> IndexedFile | None:
        """What the index holds of one file (its card id, its summary), None when it is not there."""
        paths.check_user_id(user_id)
        return await memory_index_repo.get(user_id, paths.clean_relative(rel))

    async def fiches(self, user_id: str) -> list[IndexedFile]:
        """Every fiche of this user, by path: what the prompt lists so that Maggie can open them."""
        paths.check_user_id(user_id)
        return await memory_index_repo.list_files(user_id, kind="fiche")

    async def write(
        self,
        user_id: str,
        rel: str,
        text: str,
        *,
        reason: str = "",
        conversation: bool = False,
        journal: bool = True,
    ) -> str:
        """Write a file to the local copy and queue it for the bucket. Returns the path written.

        Local first, bucket after the answer: this never waits for the network. The compare-before-push
        happens when the outbox is flushed, so an edit of the owner's made meanwhile still wins.
        `journal=False` is for the conversations, which have a file of their own to say what happened.
        """
        rel = paths.check_writable(rel, conversation=conversation)
        paths.check_user_id(user_id)
        if paths.kind_of(rel) == "conversation":
            current = self.folder(user_id).read(rel)
            if current is not None and not text.startswith(current):
                raise paths.UnsafePath(f"{rel!r} is a conversation: it can only grow, never be rewritten")
        existed = await self._save_local_and_queue(user_id, rel, text)
        indexed = await index_file(user_id, rel, text)
        if indexed.stamped is not None:
            await self._save_local_and_queue(user_id, rel, indexed.stamped)
            await index_file(user_id, rel, indexed.stamped)
        if journal:
            verb = "modifié" if existed else "créé"
            await self.journal(user_id, f"{verb} `{rel}`" + (f" — {reason}" if reason else ""))
        self.schedule(user_id)
        return rel

    async def delete(self, user_id: str, rel: str, *, reason: str = "") -> bool:
        """Remove a file: gone locally and from the index now, from the bucket at the next push."""
        rel = paths.check_writable(rel, conversation=True)
        paths.check_user_id(user_id)
        folder = self.folder(user_id)
        async with self._lock(self._file_locks, user_id):
            if not folder.exists(rel) and await memory_outbox_repo.get(user_id, rel) is None:
                return False
            known = folder.load_state().get(rel)
            folder.delete(rel)
            await memory_outbox_repo.enqueue(user_id, rel, OP_DELETE, None, known.etag if known else None)
        await memory_index_repo.remove(user_id, rel)
        await self.journal(user_id, f"supprimé `{rel}`" + (f" — {reason}" if reason else ""))
        self.schedule(user_id)
        return True

    async def journal(self, user_id: str, text: str) -> None:
        """Add a line to `journal.md`: appended locally, and merged onto the bucket's copy when pushed."""
        line = generated.journal_line(self._clock(), text)
        folder = self.folder(user_id)
        async with self._lock(self._file_locks, user_id):
            current = folder.read(paths.JOURNAL)
            folder.write(paths.JOURNAL, (current if current is not None else generated.journal_header()) + line)
            await memory_outbox_repo.enqueue(user_id, paths.JOURNAL, OP_APPEND, line, None)

    async def record_use(self, user_id: str, card_id: str) -> None:
        await memory_usage_repo.record(user_id, card_id, self._clock())

    def conflicts(self, user_id: str) -> list[str]:
        """Maggie's versions set aside because the owner edited the same file first."""
        prefix = f"{paths.CONFLICTS_DIR}/"
        return [rel for rel in self.folder(user_id).files() if rel.startswith(prefix)]

    async def _save_local_and_queue(self, user_id: str, rel: str, text: str) -> bool:
        folder = self.folder(user_id)
        async with self._lock(self._file_locks, user_id):
            existed = folder.exists(rel)
            known = folder.load_state().get(rel)
            await memory_outbox_repo.enqueue(user_id, rel, OP_PUT, text, known.etag if known else None)
            folder.write(rel, text)
        return existed

    # ------------------------------------------------------------------ degraded mode

    def degraded(self, user_id: str | None = None) -> bool:
        if user_id is not None:
            return self.status(user_id).degraded_since is not None
        return any(s.degraded_since is not None for s in self._status.values())

    async def notice(self, user_id: str) -> str:
        """What Maggie is told about her memory when it matters: a late bucket, versions set aside."""
        if not self.enabled or not is_ulid(user_id):
            return ""
        lines: list[str] = []
        status = self.status(user_id)
        if status.degraded_since is not None:
            pending = await memory_outbox_repo.count(user_id)
            since = status.degraded_since.astimezone(UTC)
            lines.append(
                "Ta mémoire (le dossier de fichiers) est peut-être en retard : son stockage ne répond plus depuis "
                f"{since:%d/%m} à {since:%Hh%M} UTC. Tu réponds avec ta copie locale et ton index"
                + (f" ; {pending} écriture(s) attendent le retour du stockage" if pending else "")
                + ". Si on te demande si ta mémoire est à jour, dis-le franchement."
            )
        conflicts = self.conflicts(user_id)
        if conflicts:
            lines.append(
                f"{len(conflicts)} de tes versions ont été mises de côté dans `{paths.CONFLICTS_DIR}/` parce que "
                "le propriétaire avait modifié le même fichier avant toi ; sa version est celle qui fait foi. "
                "Mentionne-le à l'occasion."
            )
        return "\n".join(lines)

    async def before_turn(self, user_id: str) -> None:
        """Catch up with the bucket before a turn, without ever making the turn wait long for it."""
        if not self.enabled or not is_ulid(user_id):
            return
        self._known.add(user_id)
        status = self.status(user_id)
        fresh = status.last_ok is not None and time.monotonic() - status.last_ok < self.turn_max_age_seconds
        if fresh:
            return
        task = self._pass_task(user_id)
        await asyncio.wait({task}, timeout=self.turn_timeout_seconds)

    # ------------------------------------------------------------------ scheduling

    def _pass_task(self, user_id: str) -> asyncio.Task:
        """The pass running for this user, or a new one. Never two at once."""
        running = self._tasks.get(user_id)
        if running is not None and not running.done():
            return running
        task = asyncio.create_task(self._safe_pass(user_id), name=f"memory-sync-{user_id}")
        self._tasks[user_id] = task
        return task

    def schedule(self, user_id: str) -> None:
        """Push soon, after the answer: fire and forget. A failure is already counted and logged."""
        if not self.enabled:
            return
        self._known.add(user_id)
        if not self.push_after_write:
            return
        with contextlib.suppress(RuntimeError):  # no running loop: the periodic pass will do it
            self._pass_task(user_id)

    async def _safe_pass(self, user_id: str) -> bool:
        try:
            await self.sync_user(user_id)
            return True
        except BucketUnavailable:
            return False
        except Exception:
            logger.exception("Memory synchronisation of %s failed", user_id)
            return False

    def start(self) -> None:
        if self.enabled and self._loop_task is None:
            self._loop_task = asyncio.create_task(self._run(), name="memory-sync-loop")

    async def stop(self) -> None:
        task, self._loop_task = self._loop_task, None
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        for pending in list(self._tasks.values()):
            pending.cancel()
        self._tasks.clear()

    async def _run(self) -> None:
        failures = 0
        while True:
            try:
                ok = await self.sync_all()
            except Exception:
                logger.exception("Memory synchronisation loop failed; trying again after a pause")
                ok = False
            failures = 0 if ok else failures + 1
            delay = min(self.interval_seconds * (2**failures if failures else 1), self.max_backoff_seconds)
            await asyncio.sleep(delay)

    async def users(self) -> list[str]:
        found = set(self._known) | set(local_users(self.root)) | set(await memory_outbox_repo.users())
        if self.bucket is not None:
            with contextlib.suppress(BucketUnavailable):
                found |= {p for p in await self.bucket.list_prefixes() if is_ulid(p)}
        return sorted(found)

    async def sync_all(self) -> bool:
        """One pass for every user. True when all of them got through."""
        ok = True
        for user_id in await self.users():
            ok = await self._pass_task(user_id) and ok
        MEMORY_OUTBOX_PENDING.set(await memory_outbox_repo.count())
        return ok

    # ------------------------------------------------------------------ one pass

    async def sync_user(self, user_id: str) -> PassReport:
        """Bring the bucket, the local copy and the index of one user in step. Raises `BucketUnavailable`."""
        paths.check_user_id(user_id)
        assert self.bucket is not None
        total = PassReport()
        async with self._lock(self._pass_locks, user_id):
            try:
                for _ in range(MAX_PASSES):
                    report = await self._one_pass(user_id)
                    for name in ("pulled", "pushed", "deleted", "conflicts", "reindexed"):
                        setattr(total, name, getattr(total, name) + getattr(report, name))
                    if not report.again:
                        break
            except BucketUnavailable as e:
                await self._mark_down(user_id, e)
                raise
            await self._mark_up(user_id)
        return total

    async def _mark_down(self, user_id: str, error: Exception) -> None:
        status = self.status(user_id)
        first = status.degraded_since is None
        if first:
            status.degraded_since = self._clock()
        status.last_error = str(error)[:300]
        MEMORY_SYNC_FAILURES.labels(reason=type(error).__name__).inc()
        MEMORY_DEGRADED.set(1)
        logger.critical(
            "Memory bucket unreachable for user %s (%s): working from the local copy and the index",
            user_id,
            status.last_error,
        )
        try:
            MEMORY_OUTBOX_PENDING.set(await memory_outbox_repo.count())
        except Exception:
            logger.exception("Could not count the memory writes waiting for %s", user_id)

    async def _mark_up(self, user_id: str) -> None:
        status = self.status(user_id)
        if status.degraded_since is not None:
            logger.warning("Memory bucket answers again for user %s; degraded since %s", user_id, status.degraded_since)
        status.degraded_since = None
        status.last_error = ""
        status.last_ok = time.monotonic()
        status.last_ok_at = self._clock()
        MEMORY_DEGRADED.set(1 if self.degraded() else 0)
        MEMORY_OUTBOX_PENDING.set(await memory_outbox_repo.count())

    async def _one_pass(self, user_id: str) -> PassReport:
        assert self.bucket is not None
        bucket = self.bucket
        folder = self.folder(user_id)
        report = PassReport()
        state = folder.load_state()
        remote = await self._remote_view(user_id, state)

        # 1. push
        queue = list(await memory_outbox_repo.pending(user_id))
        while queue:
            entry = queue.pop(0)
            try:
                await self._push(user_id, entry, remote, state, report, queue)
            except BucketUnavailable as e:
                await memory_outbox_repo.failed(user_id, entry.path, str(e))
                folder.save_state(state)
                raise
            folder.save_state(state)

        # 2. pull
        waiting = {e.path for e in await memory_outbox_repo.pending(user_id)}
        try:
            for rel in sorted(set(state) - set(remote)):
                if rel in waiting:
                    continue
                async with self._lock(self._file_locks, user_id):
                    if await memory_outbox_repo.get(user_id, rel) is not None:
                        continue
                    folder.delete(rel)
                state.pop(rel, None)
                report.removed.add(rel)
                report.deleted += 1
                MEMORY_SYNC_FILES.labels(direction="deleted").inc()
            for rel in sorted(remote):
                if rel in waiting:
                    continue
                known = state.get(rel)
                if known is not None and known.etag == remote[rel] and folder.exists(rel):
                    continue
                try:
                    data = await bucket.get(remote.key(user_id, rel))
                except ObjectNotFound:
                    continue
                text = data.body.decode("utf-8", errors="replace")
                async with self._lock(self._file_locks, user_id):
                    if await memory_outbox_repo.get(user_id, rel) is not None:
                        continue
                    folder.write(rel, text)
                state[rel] = Known(data.etag, sha(text))
                report.pulled += 1
                MEMORY_SYNC_FILES.labels(direction="pulled").inc()
        finally:
            folder.save_state(state)

        # 3. index
        await self._reconcile_index(user_id, report)

        # 4. generated files
        await self._generate(user_id, remote, state, report)
        folder.save_state(state)
        return report

    async def _remote_view(self, user_id: str, state: dict[str, Known]) -> RemoteView:
        """What the bucket holds. An empty answer that contradicts what is known is refused."""
        assert self.bucket is not None
        listing = await self.bucket.list_objects(paths.bucket_prefix(user_id))
        remote = RemoteView()
        for item in listing:
            rel = paths.relative_from_key(user_id, item.key)
            if rel is not None and paths.is_syncable(rel):
                remote[rel] = item.etag
                remote.raw_keys[rel] = item.key
        if not remote and state:
            raise BucketUnavailable(f"the listing came back empty although {len(state)} file(s) are known")
        return remote

    async def _push(
        self,
        user_id: str,
        entry: OutboxEntry,
        remote: RemoteView,
        state: dict[str, Known],
        report: PassReport,
        queue: list[OutboxEntry],
    ) -> None:
        assert self.bucket is not None
        bucket = self.bucket
        rel = entry.path
        key = remote.key(user_id, rel)
        current = remote.get(rel)

        if entry.op == OP_APPEND:
            await self._push_append(user_id, entry, remote, state, report)
            return

        if entry.op == OP_DELETE:
            new_base: str | None | Keep = None
            if current is None:
                state.pop(rel, None)
            elif current == entry.base_etag:
                await bucket.delete(key)
                remote.pop(rel, None)
                state.pop(rel, None)
                report.deleted += 1
                MEMORY_SYNC_FILES.labels(direction="deleted").inc()
            else:
                # Edited by the owner after the copy this delete started from: his version stays.
                logger.warning("Not deleting %s of %s: it changed in the bucket since", rel, user_id)
                new_base = KEEP
            await self._settle(user_id, entry, state, new_base)
            return

        body = entry.body or ""
        for _ in range(3):
            if current != entry.base_etag:
                break
            try:
                etag = await bucket.put(key, body.encode("utf-8"), if_match=current, if_none_match=current is None)
            except PreconditionFailed:
                # Lost a race: look again. Unchanged means the refusal was spurious, so try once more.
                current = await self._refresh_etag(key, remote, rel)
                continue
            remote[rel] = etag
            state[rel] = Known(etag, sha(body))
            await self._settle(user_id, entry, state, etag)
            report.pushed += 1
            MEMORY_SYNC_FILES.labels(direction="pushed").inc()
            return

        # The bucket's version is not the one this write started from: the owner (or another pod) wrote first.
        theirs: str | None = None
        if current is not None:
            try:
                data = await bucket.get(key)
            except ObjectNotFound:
                data = None
            if data is not None:
                theirs = data.body.decode("utf-8", errors="replace")
                remote[rel] = data.etag
                if theirs == body:
                    state[rel] = Known(data.etag, sha(theirs))
                    await self._settle(user_id, entry, state, data.etag)
                    return
        else:
            remote.pop(rel, None)
        await self._set_aside(user_id, entry, theirs, remote, state, report, queue)

    async def _settle(self, user_id: str, entry: OutboxEntry, state: dict[str, Known], new_base: str | None | Keep):
        """The entry is delivered: remember what the bucket holds, then drop the entry.

        The state goes to disk first, so a write that queues right after the drop starts from the new ETag.
        An entry rewritten while it travelled stays, based on the object the push left in the bucket.
        """
        self.folder(user_id).save_state(state)
        await memory_outbox_repo.done(user_id, entry.path, only_if_updated_at=entry.updated_at, new_base_etag=new_base)

    async def _refresh_etag(self, key: str, remote: dict[str, str], rel: str) -> str | None:
        assert self.bucket is not None
        info = await self.bucket.head(key)
        if info is None:
            remote.pop(rel, None)
            return None
        remote[rel] = info.etag
        return info.etag

    async def _set_aside(
        self,
        user_id: str,
        entry: OutboxEntry,
        theirs: str | None,
        remote: RemoteView,
        state: dict[str, Known],
        report: PassReport,
        queue: list[OutboxEntry],
    ) -> None:
        """The owner's version stays; Maggie's goes to `Conflits/`; the journal says both."""
        folder = self.folder(user_id)
        rel = entry.path
        taken = set(folder.files()) | set(remote) | {e.path for e in await memory_outbox_repo.pending(user_id)}
        aside = paths.conflict_relative(rel, self._clock(), taken)
        async with self._lock(self._file_locks, user_id):
            current_entry = await memory_outbox_repo.get(user_id, rel)
            mine = current_entry.body if current_entry and current_entry.body is not None else (entry.body or "")
            folder.write(aside, mine)
            await memory_outbox_repo.enqueue(user_id, aside, OP_PUT, mine, None)
            if theirs is None:
                folder.delete(rel)
                state.pop(rel, None)
                report.removed.add(rel)
            else:
                folder.write(rel, theirs)
                state[rel] = Known(remote[rel], sha(theirs))
            await memory_outbox_repo.done(user_id, rel)
        queued = await memory_outbox_repo.get(user_id, aside)
        if queued is not None:
            queue.append(queued)
        report.conflicts += 1
        report.again = True
        MEMORY_SYNC_FILES.labels(direction="conflict").inc()
        what = "a supprimé" if theirs is None else "a modifié"
        logger.warning("Conflict on %s of %s: the owner's version stays, Maggie's is in %s", rel, user_id, aside)
        await self.journal(
            user_id,
            f"conflit sur `{rel}` : le propriétaire {what} le fichier avant la synchronisation ; sa version est "
            f"conservée, la version de Maggie est mise de côté dans `{aside}`",
        )

    async def _push_append(
        self,
        user_id: str,
        entry: OutboxEntry,
        remote: RemoteView,
        state: dict[str, Known],
        report: PassReport,
    ) -> None:
        """Journal lines merge onto whatever the bucket holds: an append never conflicts."""
        assert self.bucket is not None
        rel = entry.path
        key = remote.key(user_id, rel)
        current = remote.get(rel)
        for _ in range(3):
            base = generated.journal_header()
            if current is not None:
                data = await self.bucket.get(key)
                base = data.body.decode("utf-8", errors="replace")
                current = data.etag
            text = base + (entry.body or "")
            try:
                etag = await self.bucket.put(key, text.encode("utf-8"), if_match=current, if_none_match=current is None)
            except PreconditionFailed:
                current = await self._refresh_etag(key, remote, rel)
                continue
            remote[rel] = etag
            state[rel] = Known(etag, sha(text))
            async with self._lock(self._file_locks, user_id):
                latest = await memory_outbox_repo.get(user_id, rel)
                if latest is not None and latest.body == entry.body:
                    self.folder(user_id).write(rel, text)
                    await memory_outbox_repo.done(user_id, rel)
                elif latest is not None and (latest.body or "").startswith(entry.body or ""):
                    # Lines were added while this one travelled: keep only those, and the local file as it is.
                    rest = (latest.body or "")[len(entry.body or "") :]
                    await memory_outbox_repo.done(user_id, rel)
                    await memory_outbox_repo.enqueue(user_id, rel, OP_APPEND, rest, None)
                    self.folder(user_id).write(rel, text + rest)
                    report.again = True
            report.pushed += 1
            MEMORY_SYNC_FILES.labels(direction="pushed").inc()
            return
        raise BucketUnavailable(f"{rel} keeps changing in the bucket")

    async def _reconcile_index(self, user_id: str, report: PassReport) -> None:
        folder = self.folder(user_id)
        hashes = await memory_index_repo.hashes(user_id)
        local = [rel for rel in folder.files() if paths.is_indexable(rel)]
        local_set = set(local)
        for rel in sorted(set(hashes) - local_set):
            await memory_index_repo.remove(user_id, rel)
            report.reindexed += 1
        for rel in local:
            text = folder.read(rel)
            if text is None or hashes.get(rel) == carte_mod.content_hash(text):
                continue
            indexed = await index_file(user_id, rel, text)
            report.reindexed += 1
            if indexed.stamped is not None:
                await self._save_local_and_queue(user_id, rel, indexed.stamped)
                await index_file(user_id, rel, indexed.stamped)
                await self.journal(user_id, f"identifiant ajouté à `{rel}`")
                report.again = True

    async def _generate(self, user_id: str, remote: RemoteView, state: dict[str, Known], report: PassReport) -> None:
        """Write the generated files that are missing or stale — never over a version someone edited."""
        assert self.bucket is not None
        folder = self.folder(user_id)
        fiches = [f for f in await memory_index_repo.list_files(user_id, kind="fiche")]
        desired = {
            paths.README: generated.readme(),
            paths.AGENTS: generated.agents(),
            paths.SUMMARY: generated.summary([(f.path, f.resume) for f in fiches]),
        }
        for rel, text in desired.items():
            present = folder.read(rel) if rel in remote else None
            if present is not None and not generated.is_untouched(present):
                continue
            if present == text:
                continue
            key = remote.key(user_id, rel)
            current = remote.get(rel)
            try:
                etag = await self.bucket.put(key, text.encode("utf-8"), if_match=current, if_none_match=current is None)
            except PreconditionFailed:
                report.again = True
                continue
            remote[rel] = etag
            state[rel] = Known(etag, sha(text))
            async with self._lock(self._file_locks, user_id):
                folder.write(rel, text)
            report.pushed += 1
            MEMORY_SYNC_FILES.labels(direction="pushed").inc()
        if paths.JOURNAL not in remote and await memory_outbox_repo.get(user_id, paths.JOURNAL) is None:
            header = generated.journal_header()
            etag = await self.bucket.put(
                paths.bucket_key(user_id, paths.JOURNAL), header.encode("utf-8"), if_none_match=True
            )
            remote[paths.JOURNAL] = etag
            state[paths.JOURNAL] = Known(etag, sha(header))
            async with self._lock(self._file_locks, user_id):
                if not folder.exists(paths.JOURNAL):
                    folder.write(paths.JOURNAL, header)

    # ------------------------------------------------------------------ rebuild

    async def rebuild(self, user_id: str | None = None) -> RebuildReport:
        """Drop the index and rebuild it from the files. Everything comes back except the usage counters.

        The files are refreshed from the bucket first when it answers; when it does not, the local copy
        is used — and a user with no local copy is left alone, rather than losing the index's copy of
        their text, which is then the last one.
        """
        report = RebuildReport()
        users = [user_id] if user_id else sorted(set(await self.users()) | set(await memory_index_repo.user_ids()))
        for user in users:
            paths.check_user_id(user)
            if self.enabled:
                try:
                    await self.sync_user(user)
                except BucketError as e:
                    logger.warning("Rebuilding the index of %s from the local copy: %s", user, e)
            folder = self.folder(user)
            files = [rel for rel in folder.files() if paths.is_indexable(rel)]
            if not files and await memory_index_repo.hashes(user):
                report.skipped.append(user)
                logger.critical("Not rebuilding the index of %s: no local copy to rebuild it from", user)
                continue
            await memory_index_repo.clear_user(user)
            for rel in files:
                text = folder.read(rel)
                if text is None:
                    continue
                indexed = await index_file(user, rel, text)
                if indexed.stamped is not None:
                    await self._save_local_and_queue(user, rel, indexed.stamped)
                    await index_file(user, rel, indexed.stamped)
                report.files += 1
            report.users += 1
            if self.enabled:
                with contextlib.suppress(BucketError):
                    await self.sync_user(user)
        return report


def build_service() -> MemoryService:
    return MemoryService(
        build_bucket(),
        Path(settings.memory_local_dir),
        interval_seconds=settings.memory_sync_interval_seconds,
        max_backoff_seconds=settings.memory_sync_max_backoff_seconds,
        turn_max_age_seconds=settings.memory_turn_sync_max_age_seconds,
        turn_timeout_seconds=settings.memory_turn_sync_timeout_seconds,
    )


memory_service = build_service()

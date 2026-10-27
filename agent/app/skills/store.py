"""The skills as Markdown files (MAG-218, ADR-011): what the owner teaches Maggie is memory, so he can open it.

The same machinery as the memory folder, with its own scope: `MemoryService`'s client, outbox, push
with compare-before-push, pull, conflicts and degraded mode, pointed at `Compétences/` instead of a user's
`Mémoire/`. The folder is global — skills carry no `user_id` until MAG-108 — so it sits outside every
user's folder, and the archive of the knowledge never holds a skill.

The files are the only truth. The `skill` table is an index derived from them: each pass of the
synchronisation reads the files and brings the table in step, so a file the owner corrected in the bucket
reaches the next prompt. A skill is identified by the `name` of its frontmatter, never by the file name.
"""

import logging
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any

from app.config import settings
from app.db.memory_index_model import OP_DELETE
from app.db.memory_index_repository import memory_outbox_repo
from app.db.skill_repository import SkillRepository, skill_repo
from app.memory import paths
from app.memory.bucket import Bucket, BucketError
from app.memory.service import MemoryService, PassReport, memory_service
from app.metrics import MEMORY_DEGRADED
from app.skills.document import SkillEntry, parse_markdown, render_markdown

logger = logging.getLogger(__name__)

SCOPE = paths.SKILLS_SCOPE


class SkillFiles(MemoryService):
    def __init__(
        self,
        bucket: Bucket | None,
        root: Path,
        *,
        repo: SkillRepository | None = None,
        clock: Callable[[], datetime] | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(bucket, root, clock=clock, **kwargs)
        self.repo = repo or skill_repo

    def _check_scope(self, scope: str) -> str:
        if scope != SCOPE:
            raise paths.UnsafePath(f"{scope!r} is not the scope of the skills")
        return scope

    def _is_scope(self, scope: str) -> bool:
        return scope == SCOPE

    def _publish_degraded(self) -> None:
        # The gauge is the memory's: this scope may raise it, never lower it under a memory still cut.
        if self.degraded():
            MEMORY_DEGRADED.set(1)

    async def users(self) -> list[str]:
        return [SCOPE]

    async def journal(self, user_id: str, text: str) -> None:
        logger.info("Skills folder: %s", text)

    async def _generate(self, *args: object, **kwargs: object) -> None:
        """The skills folder holds the owner's documents and nothing Maggie writes about them."""

    # ------------------------------------------------------------------ the files

    async def catch_up(self) -> None:
        """Before a prompt is built: take what the bucket changed, without waiting long for it."""
        await self.before_turn(SCOPE)

    def _scan(self) -> list[tuple[str, SkillEntry, str]]:
        """(file, entry, body) of every skill file that can be read; Conflits/ holds set-aside versions, not skills."""
        folder = self.folder(SCOPE)
        found: list[tuple[str, SkillEntry, str]] = []
        for rel in folder.files():
            if rel.split("/", 1)[0] == paths.CONFLICTS_DIR:
                continue
            text = folder.read(rel)
            parsed = parse_markdown(text, default_name=Path(rel).stem) if text is not None else None
            if parsed is None:
                logger.warning("Skill file %s has no readable frontmatter: left out of the index", rel)
                continue
            found.append((rel, *parsed))
        return found

    def documents(self) -> dict[str, tuple[str, SkillEntry, str]]:
        """name -> (file, entry, body). Two files of one name: the first one (by path) is used."""
        found: dict[str, tuple[str, SkillEntry, str]] = {}
        for rel, entry, body in self._scan():
            if entry.name in found:
                logger.warning("Skill %s is in %s and %s: the first one is used", entry.name, found[entry.name][0], rel)
                continue
            found[entry.name] = (rel, entry, body)
        return found

    def load(self, name: str) -> tuple[SkillEntry, str] | None:
        found = self.documents().get(name)
        return (found[1], found[2]) if found else None

    async def save(self, name: str, description: str, tags: list[str], content: str) -> str:
        """Write the skill's file (replacing the one of that name), queue it for the bucket. Returns the file."""
        text = render_markdown(name, description, tags, content)
        folder = self.folder(SCOPE)
        existing = self.documents().get(name)
        rel = existing[0] if existing else paths.unique_relative("", paths.filename_for(name), folder.files())
        async with self._lock(self._index_locks, SCOPE):
            await self._save_local_and_queue(SCOPE, rel, text)
        self.schedule(SCOPE)
        return rel

    async def remove(self, name: str) -> bool:
        """Delete the skill's file now, from the bucket at the next push. False when no file holds that name."""
        folder = self.folder(SCOPE)
        files = [rel for rel, entry, _ in self._scan() if entry.name == name]
        if not files:
            return False
        async with self._lock(self._file_locks, SCOPE):
            for rel in files:
                known = folder.load_state().get(rel)
                folder.delete(rel)
                await memory_outbox_repo.enqueue(SCOPE, rel, OP_DELETE, None, known.etag if known else None)
        self.schedule(SCOPE)
        return True

    # ------------------------------------------------------------------ the derived table

    async def _reconcile_index(self, user_id: str, report: PassReport) -> None:
        """Bring the `skill` table in step with the files. Runs after a pass that reached the bucket."""
        async with self._lock(self._index_locks, SCOPE):
            documents = self.documents()
            rows = {row.name: row for row in await self.repo.list_all()}
            if not documents and rows and await self._first_run():
                await self._move_table_to_files(list(rows.values()))
                report.again = True
                documents = self.documents()
            pending = await self._pending_names()
            for name, (_, entry, body) in documents.items():
                row = rows.get(name)
                if row is None or (row.description, list(row.tags or []), row.content) != (
                    entry.description,
                    entry.tags,
                    body,
                ):
                    await self.repo.upsert(name, entry.description, entry.tags, body)
                    report.reindexed += 1
            for name in sorted(set(rows) - set(documents) - pending):
                await self.repo.delete(name)
                report.reindexed += 1

    async def _first_run(self) -> bool:
        """The folder holds no file and nothing waits to be pushed: it has never been filled."""
        return not self.folder(SCOPE).files() and not await memory_outbox_repo.pending(SCOPE)

    async def _move_table_to_files(self, rows: list) -> None:
        """The skills of MAG-187 leave the table for files, never over a file that is already there."""
        folder = self.folder(SCOPE)
        for row in rows:
            rel = paths.unique_relative("", paths.filename_for(row.name), folder.files())
            if folder.exists(rel):
                continue
            text = render_markdown(row.name, row.description, list(row.tags or []), row.content or "")
            await self._save_local_and_queue(SCOPE, rel, text)
        logger.info("Moved %d skill(s) from the table to the skills folder", len(rows))

    async def _pending_names(self) -> set[str]:
        """Skills written here (or by another pod) whose file has not reached the bucket: their row stays."""
        names: set[str] = set()
        for entry in await memory_outbox_repo.pending(SCOPE):
            parsed = parse_markdown(entry.body, default_name=Path(entry.path).stem) if entry.body else None
            if parsed is not None:
                names.add(parsed[0].name)
        return names

    # ------------------------------------------------------------------ boot and rebuild

    async def start_up(self) -> None:
        """At boot: take the bucket's files and build the table from them, then keep both in step.

        A bucket that does not answer leaves the table as it is, unless the local copy has files to build
        it from: a stale index is better than no skill at all.
        """
        if self.enabled:
            try:
                await self.sync_user(SCOPE)
            except BucketError as e:
                logger.warning("Skills folder not reached at boot, the index stays as it is: %s", e)
                if self.folder(SCOPE).files():
                    await self._reconcile_index(SCOPE, PassReport())
            self.start()
            return
        await self._reconcile_index(SCOPE, PassReport())

    async def rebuild_table(self) -> int:
        """Empty the table and rebuild it from the files. Returns the number of skills.

        The bucket is read first when it answers. With no file to build from, the table is left alone
        rather than emptied: it is then the last copy there is.
        """
        if self.enabled:
            try:
                await self.sync_user(SCOPE)
            except BucketError as e:
                logger.warning("Rebuilding the skills from the local copy: %s", e)
        async with self._lock(self._index_locks, SCOPE):
            documents = self.documents()
            if not documents:
                if await self.repo.list_all():
                    logger.critical("Not rebuilding the skills: no file to rebuild them from")
                return 0
            for row in await self.repo.list_all():
                await self.repo.delete(row.name)
            for name, (_, entry, body) in documents.items():
                await self.repo.upsert(name, entry.description, entry.tags, body)
            return len(documents)


def build_skill_files() -> SkillFiles:
    return SkillFiles(
        memory_service.bucket,
        Path(settings.memory_local_dir),
        interval_seconds=settings.memory_sync_interval_seconds,
        max_backoff_seconds=settings.memory_sync_max_backoff_seconds,
        turn_max_age_seconds=settings.memory_turn_sync_max_age_seconds,
        turn_timeout_seconds=settings.memory_turn_sync_timeout_seconds,
    )


skill_files = build_skill_files()

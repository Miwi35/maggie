"""SOMMAIRE.md: the owner's table of contents, generated from the index and never read back.

It carries no counters, so a note added or removed is the only thing that can change it, and the
header says not to edit it: the next change overwrites it. It sits beside the notes, outside what the
reconciler reads.
"""

import hashlib
import logging

from app.db.memory_note_repository import MemoryNoteRepository, memory_note_repo
from app.memory.bucket import Bucket, BucketError
from app.memory.paths import stem, summary_key

logger = logging.getLogger(__name__)

HEADER = (
    "<!-- Fichier généré par Maggie — ne pas modifier / generated file, do not edit: "
    "toute modification sera écrasée. -->\n"
)


class SummaryWriter:
    def __init__(self, bucket: Bucket, repo: MemoryNoteRepository | None = None) -> None:
        self.bucket = bucket
        self.repo = repo or memory_note_repo
        self._written: dict[str, str] = {}

    async def render(self, user_id: str) -> str:
        rows = await self.repo.live_notes_of_user(user_id)
        prefix = f"{user_id}/"

        def line(row) -> str:
            link = str(row.path).removeprefix(prefix)
            text = f"- [{row.title}]({link})"
            if row.summary:
                text += f" — {row.summary}"
            if row.tags:
                text += f" ({', '.join(row.tags)})"
            return text

        def section(title: str, selected: list) -> list[str]:
            if not selected:
                return []
            return [f"## {title}", "", *(line(r) for r in sorted(selected, key=lambda r: str(r.title).casefold())), ""]

        readable = [r for r in rows if not r.unreadable]
        lines = [HEADER, "# Sommaire de la mémoire", ""]
        lines += section("Notes", [r for r in readable if r.status == "active"])
        lines += section("Archives", [r for r in readable if r.status == "archived"])
        broken = sorted((r for r in rows if r.unreadable), key=lambda r: str(r.path))
        if broken:
            lines += ["## Fichiers illisibles", ""]
            lines += [f"- {stem(str(r.path))} — {r.unreadable_reason}" for r in broken]
            lines.append("")
        return "\n".join(lines).rstrip() + "\n"

    async def refresh(self, user_id: str) -> bool:
        """Write the summary if it changed since last written. Best effort: a bucket that is down skips it."""
        text = await self.render(user_id)
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if self._written.get(user_id) == digest:
            return False
        try:
            await self.bucket.put(summary_key(user_id), text.encode("utf-8"))
        except BucketError as e:
            logger.info(f"Memory summary for {user_id} not written: {e}")
            return False
        self._written[user_id] = digest
        return True

import logging
from datetime import timedelta

from app.config import settings
from app.db.memory_note_repository import memory_note_repo
from app.db.memory_repository import memory_repo
from app.memory import service as memory_service_module

logger = logging.getLogger(__name__)


def _age(delta: timedelta | None) -> str:
    if delta is None:
        return "âge inconnu"
    seconds = int(delta.total_seconds())
    if seconds < 90:
        return f"{seconds} s"
    if seconds < 5400:
        return f"{round(seconds / 60)} min"
    return f"{round(seconds / 3600)} h"


class AgentMemory:
    """What goes into the volatile prompt block from memory: the old facts and the note files."""

    async def get_memory_context(self, user_id: str) -> str:
        sections = [await self._facts(user_id), await self._notes(user_id)]
        return "".join(section for section in sections if section)

    async def _facts(self, user_id: str) -> str:
        try:
            memories = await memory_repo.find_by_user(user_id, memory_type="factual")
            if not memories:
                return ""
            lines = ["\n\nCe que tu sais sur l'utilisateur :"]
            lines.extend(f"- {m.content}" for m in memories)
            return "\n".join(lines)
        except Exception as e:
            logger.warning(f"Could not load agent memory: {e}")
            return ""

    async def _notes(self, user_id: str) -> str:
        """The note index of the user, served stale when the bucket is out of reach — and said so.

        A transitional listing: the note tools and the summary-based prompt are MAG-16/MAG-17.
        """
        service = memory_service_module.get_service()
        if service is None:
            return ""
        try:
            turn = await service.sync.before_turn()
            notes = await memory_note_repo.notes_for_prompt(user_id, settings.memory_prompt_max_notes)
            notices = []
            if turn.stale:
                notices.append(
                    f"Ces notes viennent de l'index local, pas du bucket (injoignable, index vieux de "
                    f"{_age(turn.stale_age)}) : elles peuvent être en retard sur les fichiers de l'utilisateur."
                )
            if turn.skipped:
                notices.append(
                    "Synchronisation avec le bucket sautée pour ce tour (trop lente) : les notes peuvent "
                    "ne pas refléter une modification toute récente."
                )
            if not notes and not notices:
                return ""
            lines = ["\n\nTes notes sur l'utilisateur :", *(f"({n})" for n in notices)]
            for note in notes:
                excerpt = str(note.body or "").strip()[: settings.memory_prompt_note_chars]
                lines.append(f"\n## {note.title}\n{excerpt}")
            await memory_note_repo.mark_used(user_id, [str(n.id) for n in notes])
            return "\n".join(lines)
        except Exception as e:
            logger.warning(f"Could not load memory notes: {e}")
            return ""

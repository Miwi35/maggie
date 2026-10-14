import logging

from app.memory import legacy
from app.memory.service import memory_service

logger = logging.getLogger(__name__)

# The list of fiches in the prompt is a table of contents, not the knowledge: past this, memory_search finds the rest.
MAX_LISTED = 80


class AgentMemory:
    """What the prompt says of the memory folder: the fiches Maggie can open, and whether the folder is in step."""

    async def get_memory_context(self, user_id: str) -> str:
        """The fiches, as paths and summaries, read from the index: a fiche written one turn ago is there."""
        notice = await self._folder_notice(user_id)
        if memory_service.enabled:
            await legacy.import_once(user_id)
        if not memory_service.enabled:
            return notice
        try:
            listed = await memory_service.fiches(user_id)
        except Exception as e:
            logger.warning(f"Could not list the fiches: {e}")
            return notice
        if not listed:
            return notice

        lines = [
            "\n\nTes fiches (ce que tu sais). Ouvre celle qui concerne la question avec memory_open avant "
            "d'affirmer quoi que ce soit, et cherche avec memory_search ce qui n'est pas dans cette liste :"
        ]
        for fiche in listed[:MAX_LISTED]:
            lines.append(f"- {fiche.path}" + (f" — {fiche.resume}" if fiche.resume else ""))
        if len(listed) > MAX_LISTED:
            lines.append(f"- … et {len(listed) - MAX_LISTED} autres : memory_search.")
        return "\n".join(lines) + notice

    async def _folder_notice(self, user_id: str) -> str:
        """Catch the memory folder up with its bucket, then say so if it could not be (never fails a turn)."""
        try:
            await memory_service.before_turn(user_id)
            notice = await memory_service.notice(user_id)
        except Exception as e:
            logger.warning(f"Could not check the memory folder: {e}")
            return ""
        return f"\n\n{notice}" if notice else ""

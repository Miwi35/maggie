import logging

from app.db.memory_repository import memory_repo
from app.memory.service import memory_service

logger = logging.getLogger(__name__)


class AgentMemory:
    """Persistent memory backed by the agent's own database."""

    async def get_memory_context(self, user_id: str) -> str:
        """Fetch all factual memories and format them as a system prompt section."""
        notice = await self._folder_notice(user_id)
        try:
            memories = await memory_repo.find_by_user(user_id, memory_type="factual")
            if not memories:
                return notice

            lines = ["\n\nCe que tu sais sur l'utilisateur :"]
            for m in memories:
                lines.append(f"- {m.content}")

            return "\n".join(lines) + notice

        except Exception as e:
            logger.warning(f"Could not load agent memory: {e}")
            return notice

    async def _folder_notice(self, user_id: str) -> str:
        """Catch the memory folder up with its bucket, then say so if it could not be (never fails a turn)."""
        try:
            await memory_service.before_turn(user_id)
            notice = await memory_service.notice(user_id)
        except Exception as e:
            logger.warning(f"Could not check the memory folder: {e}")
            return ""
        return f"\n\n{notice}" if notice else ""

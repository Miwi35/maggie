import logging

from app.db.memory_repository import memory_repo

logger = logging.getLogger(__name__)


class AgentMemory:
    """Persistent memory backed by the agent's own database."""

    async def get_memory_context(self, user_id: str) -> str:
        """Fetch all factual memories and format them as a system prompt section."""
        try:
            memories = await memory_repo.find_by_user(user_id, memory_type="factual")
            if not memories:
                return ""

            lines = ["\n\nCe que tu sais sur l'utilisateur :"]
            for m in memories:
                lines.append(f"- {m.content}")

            return "\n".join(lines)

        except Exception as e:
            logger.warning(f"Could not load agent memory: {e}")
            return ""

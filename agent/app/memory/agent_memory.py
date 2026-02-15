import json
import logging

from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)


class AgentMemory:
    """Persistent memory backed by MCP tools (PostgreSQL via Symfony API)."""

    async def get_memory_context(self, user_id: str) -> str:
        """Fetch all factual memories and format them as a system prompt section.

        Returns an empty string if no memories exist or MCP is unavailable.
        """
        try:
            result = await mcp_client.call_tool("get_user_profile", {})
            data = json.loads(result)

            memories = data.get("factual_memories", [])
            if not memories:
                return ""

            lines = ["\n\nCe que tu sais sur l'utilisateur :"]
            for m in memories:
                lines.append(f"- {m['content']}")

            return "\n".join(lines)

        except Exception as e:
            logger.warning(f"Could not load agent memory: {e}")
            return ""

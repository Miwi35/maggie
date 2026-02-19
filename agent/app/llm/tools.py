import json
import logging
from datetime import datetime, timezone

from app.db.proaction_repository import proaction_repo
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

# Native tool definitions (available to Maggie during internal/proaction calls only)
NATIVE_TOOLS = [
    {
        "name": "schedule_proaction",
        "description": (
            "Schedule a proaction (autonomous task) for later execution. "
            "Provide a prompt that Maggie will execute at the scheduled time, "
            "and the ISO 8601 datetime for when it should run."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "The instruction/prompt to execute at the scheduled time",
                },
                "scheduled_at": {
                    "type": "string",
                    "description": "ISO 8601 datetime for when to execute (e.g. 2026-02-20T09:00:00+01:00)",
                },
            },
            "required": ["prompt", "scheduled_at"],
        },
    },
    {
        "name": "list_proactions",
        "description": (
            "List all scheduled proactions for the current user. "
            "Returns proactions with their status (pending, running, completed, failed)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
        },
    },
]


async def _handle_schedule_proaction(arguments: dict, user_id: str) -> str:
    """Create a new proaction entry."""
    prompt = arguments.get("prompt", "")
    scheduled_at_str = arguments.get("scheduled_at", "")

    if not prompt or not scheduled_at_str:
        return json.dumps({"error": "Both 'prompt' and 'scheduled_at' are required"})

    try:
        scheduled_at = datetime.fromisoformat(scheduled_at_str)
        if scheduled_at.tzinfo is None:
            scheduled_at = scheduled_at.replace(tzinfo=timezone.utc)
    except ValueError:
        return json.dumps({"error": f"Invalid datetime format: {scheduled_at_str}"})

    proaction = await proaction_repo.create(
        user_id=user_id, prompt=prompt, scheduled_at=scheduled_at
    )
    return json.dumps(proaction.to_dict())


async def _handle_list_proactions(arguments: dict, user_id: str) -> str:
    """List proactions for the current user."""
    proactions = await proaction_repo.find_by_user(user_id)
    return json.dumps([p.to_dict() for p in proactions])


_NATIVE_HANDLERS = {
    "schedule_proaction": _handle_schedule_proaction,
    "list_proactions": _handle_list_proactions,
}


class ToolRouter:
    """Converts MCP tool definitions to Anthropic format and routes tool calls.

    Supports two types of tools:
    - Native tools (schedule_proaction, list_proactions) — internal only, not exposed via A2A
    - MCP tools — fetched from the Symfony MCP server
    """

    async def get_tool_definitions(self, include_native: bool = False) -> list[dict]:
        """Get available tools in Anthropic tool format.

        Args:
            include_native: If True, include native proaction tools (for internal/proaction calls).
        """
        tools = []

        if include_native:
            tools.extend(NATIVE_TOOLS)

        mcp_tools = await mcp_client.list_tools()
        for tool in mcp_tools:
            tools.append(
                {
                    "name": tool["name"],
                    "description": tool.get("description", ""),
                    "input_schema": tool.get("inputSchema", {"type": "object", "properties": {}}),
                }
            )

        return tools

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        """Route a tool call to native handler or MCP server."""
        # Check native handlers first
        if name in _NATIVE_HANDLERS:
            if user_id is None:
                return json.dumps({"error": "user_id required for native tools"})
            try:
                return await _NATIVE_HANDLERS[name](arguments, user_id)
            except Exception as e:
                logger.error(f"Native tool call failed: {name}({arguments}): {e}")
                return json.dumps({"error": f"Tool call failed: {e}"})

        # Fall through to MCP
        try:
            result = await mcp_client.call_tool(name, arguments)
            return result
        except Exception as e:
            logger.error(f"Tool call failed: {name}({arguments}): {e}")
            return f'{{"error": "Tool call failed: {e}"}}'

import json
import logging
from datetime import UTC, datetime

from app.db.memory_repository import memory_repo
from app.db.proaction_repository import proaction_repo
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

# Proaction tools (available during proaction/autonomous calls only)
PROACTION_TOOLS = [
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

# Memory tools (always available — chat + proaction)
MEMORY_TOOLS = [
    {
        "name": "store_memory",
        "description": (
            "Store a new memory about the user. Use type 'factual' for preferences, "
            "habits, personal info. Use type 'episodic' for notable events, accomplishments."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "content": {
                    "type": "string",
                    "description": "The memory content to store",
                },
                "type": {
                    "type": "string",
                    "enum": ["factual", "episodic"],
                    "description": "Memory type: factual (preferences, info) or episodic (events)",
                },
            },
            "required": ["content", "type"],
        },
    },
    {
        "name": "search_memory",
        "description": (
            "Search the user's memories by keyword. "
            "Returns matching memories ordered by most recent."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query (keyword or phrase)",
                },
                "type": {
                    "type": "string",
                    "enum": ["factual", "episodic"],
                    "description": "Optional: filter by memory type",
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "update_memory",
        "description": (
            "Update an existing memory. Use this when a factual memory has changed "
            "(e.g. new job, new address) instead of creating a duplicate."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "memory_id": {
                    "type": "string",
                    "description": "The ID of the memory to update",
                },
                "content": {
                    "type": "string",
                    "description": "The new content for the memory",
                },
            },
            "required": ["memory_id", "content"],
        },
    },
    {
        "name": "delete_memory",
        "description": "Delete a memory by its ID.",
        "input_schema": {
            "type": "object",
            "properties": {
                "memory_id": {
                    "type": "string",
                    "description": "The ID of the memory to delete",
                },
            },
            "required": ["memory_id"],
        },
    },
]


# --- Handlers ---


async def _handle_schedule_proaction(arguments: dict, user_id: str) -> str:
    prompt = arguments.get("prompt", "")
    scheduled_at_str = arguments.get("scheduled_at", "")

    if not prompt or not scheduled_at_str:
        return json.dumps({"error": "Both 'prompt' and 'scheduled_at' are required"})

    try:
        scheduled_at = datetime.fromisoformat(scheduled_at_str)
        if scheduled_at.tzinfo is None:
            scheduled_at = scheduled_at.replace(tzinfo=UTC)
    except ValueError:
        return json.dumps({"error": f"Invalid datetime format: {scheduled_at_str}"})

    proaction = await proaction_repo.create(
        user_id=user_id, prompt=prompt, scheduled_at=scheduled_at
    )
    return json.dumps(proaction.to_dict())


async def _handle_list_proactions(arguments: dict, user_id: str) -> str:
    proactions = await proaction_repo.find_by_user(user_id)
    return json.dumps([p.to_dict() for p in proactions])


async def _handle_store_memory(arguments: dict, user_id: str) -> str:
    content = arguments.get("content", "")
    memory_type = arguments.get("type", "factual")

    if not content:
        return json.dumps({"error": "'content' is required"})

    memory = await memory_repo.store(user_id, content, memory_type)
    return json.dumps(memory.to_dict())


async def _handle_search_memory(arguments: dict, user_id: str) -> str:
    query = arguments.get("query", "")
    memory_type = arguments.get("type")

    if not query:
        return json.dumps({"error": "'query' is required"})

    memories = await memory_repo.search(user_id, query, memory_type)
    return json.dumps([m.to_dict() for m in memories])


async def _handle_update_memory(arguments: dict, user_id: str) -> str:
    memory_id = arguments.get("memory_id", "")
    content = arguments.get("content", "")

    if not memory_id or not content:
        return json.dumps({"error": "'memory_id' and 'content' are required"})

    memory = await memory_repo.update(memory_id, content)
    if memory is None:
        return json.dumps({"error": f"Memory '{memory_id}' not found"})
    return json.dumps(memory.to_dict())


async def _handle_delete_memory(arguments: dict, user_id: str) -> str:
    memory_id = arguments.get("memory_id", "")

    if not memory_id:
        return json.dumps({"error": "'memory_id' is required"})

    deleted = await memory_repo.delete(memory_id)
    if not deleted:
        return json.dumps({"error": f"Memory '{memory_id}' not found"})
    return json.dumps({"deleted": True, "id": memory_id})


_NATIVE_HANDLERS = {
    "schedule_proaction": _handle_schedule_proaction,
    "list_proactions": _handle_list_proactions,
    "store_memory": _handle_store_memory,
    "search_memory": _handle_search_memory,
    "update_memory": _handle_update_memory,
    "delete_memory": _handle_delete_memory,
}


class ToolRouter:
    """Routes tool calls to native handlers or MCP server.

    Tool categories:
    - Memory tools — always available (chat + proaction)
    - Proaction tools — only during proaction/autonomous calls
    - MCP tools — fetched from the Symfony MCP server
    """

    async def get_tool_definitions(self, include_native: bool = False) -> list[dict]:
        """Get available tools in Anthropic tool format.

        Args:
            include_native: If True, include proaction tools (for internal/proaction calls).
                            Memory tools are always included.
        """
        tools = list(MEMORY_TOOLS)

        if include_native:
            tools.extend(PROACTION_TOOLS)

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

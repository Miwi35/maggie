import json
import logging
from datetime import UTC, datetime

from app.db.instruction_repository import instruction_repo
from app.db.memory_repository import memory_repo
from app.db.proaction_repository import proaction_repo
from app.mcp.client import mcp_client
from app.skills.index import skill_index

logger = logging.getLogger(__name__)

# Proaction tools (available in both chat and proaction modes)
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
        "description": ("Search the user's memories by keyword. Returns matching memories ordered by most recent."),
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

# Instruction tools (always available — chat + proaction)
INSTRUCTION_TOOLS = [
    {
        "name": "add_instruction",
        "description": (
            "Store a new proaction guideline. Instructions tell Maggie WHEN to act autonomously "
            "(e.g. 'send me a day summary every morning at 9', 'don't bother me 9pm-9am'). "
            "These are used during daily proaction planning."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "content": {
                    "type": "string",
                    "description": "The instruction content (a scheduling/notification rule)",
                },
            },
            "required": ["content"],
        },
    },
    {
        "name": "list_instructions",
        "description": "List all proaction guidelines for the current user.",
        "input_schema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "delete_instruction",
        "description": "Delete a proaction guideline by its ID.",
        "input_schema": {
            "type": "object",
            "properties": {
                "instruction_id": {
                    "type": "string",
                    "description": "The ID of the instruction to delete",
                },
            },
            "required": ["instruction_id"],
        },
    },
]

# Skill tools (always available — chat + proaction)
SKILL_TOOLS = [
    {
        "name": "create_skill",
        "description": (
            "Create a new skill file. Skills teach Maggie HOW to perform specific tasks "
            "(e.g. 'when adding a concert, search for the event webpage'). "
            "Skills are listed by name and description in the system prompt and loaded on demand with get_skill."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Unique skill name (kebab-case, e.g. 'concert-event-link')",
                },
                "description": {
                    "type": "string",
                    "description": "Short description of what the skill does",
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Keywords describing the skill (e.g. ['concert', 'calendrier', 'lien'])",
                },
                "content": {
                    "type": "string",
                    "description": "The full skill procedure in markdown",
                },
            },
            "required": ["name", "description", "tags", "content"],
        },
    },
    {
        "name": "list_skills",
        "description": "List all available skills (name, description, tags).",
        "input_schema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "get_skill",
        "description": (
            "Charge une compétence AVANT d'exécuter une tâche qu'elle couvre "
            "(voir l'index des compétences du system prompt)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The skill name to load",
                },
            },
            "required": ["name"],
        },
    },
    {
        "name": "update_skill",
        "description": "Update an existing skill's content, description, or tags.",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The skill name to update",
                },
                "content": {
                    "type": "string",
                    "description": "New skill content (optional)",
                },
                "description": {
                    "type": "string",
                    "description": "New description (optional)",
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "New tags (optional)",
                },
            },
            "required": ["name"],
        },
    },
    {
        "name": "delete_skill",
        "description": "Delete a skill by name.",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The skill name to delete",
                },
            },
            "required": ["name"],
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

    proaction = await proaction_repo.create(user_id=user_id, prompt=prompt, scheduled_at=scheduled_at)
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

    memory = await memory_repo.update(user_id, memory_id, content)
    if memory is None:
        return json.dumps({"error": f"Memory '{memory_id}' not found"})
    return json.dumps(memory.to_dict())


async def _handle_delete_memory(arguments: dict, user_id: str) -> str:
    memory_id = arguments.get("memory_id", "")

    if not memory_id:
        return json.dumps({"error": "'memory_id' is required"})

    deleted = await memory_repo.delete(user_id, memory_id)
    if not deleted:
        return json.dumps({"error": f"Memory '{memory_id}' not found"})
    return json.dumps({"deleted": True, "id": memory_id})


async def _handle_add_instruction(arguments: dict, user_id: str) -> str:
    content = arguments.get("content", "")
    if not content:
        return json.dumps({"error": "'content' is required"})
    instruction = await instruction_repo.store(user_id, content)
    return json.dumps(instruction.to_dict())


async def _handle_list_instructions(arguments: dict, user_id: str) -> str:
    instructions = await instruction_repo.find_by_user(user_id)
    return json.dumps([i.to_dict() for i in instructions])


async def _handle_delete_instruction(arguments: dict, user_id: str) -> str:
    instruction_id = arguments.get("instruction_id", "")
    if not instruction_id:
        return json.dumps({"error": "'instruction_id' is required"})
    deleted = await instruction_repo.delete(user_id, instruction_id)
    if not deleted:
        return json.dumps({"error": f"Instruction '{instruction_id}' not found"})
    return json.dumps({"deleted": True, "id": instruction_id})


async def _handle_create_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    description = arguments.get("description", "")
    tags = arguments.get("tags", [])
    content = arguments.get("content", "")
    if not name or not content:
        return json.dumps({"error": "'name' and 'content' are required"})
    if len(name) > 200:
        return json.dumps({"error": "'name' must be 200 characters or fewer"})
    if not isinstance(tags, list):
        return json.dumps({"error": "'tags' must be a list of strings"})
    entry = await skill_index.create(name, description, tags, content, user_id)
    return json.dumps({"name": entry.name, "description": entry.description, "tags": entry.tags})


async def _handle_list_skills(arguments: dict, user_id: str) -> str:
    entries = skill_index.list_all()
    return json.dumps([{"name": e.name, "description": e.description, "tags": e.tags} for e in entries])


async def _handle_get_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    if not name:
        return json.dumps({"error": "'name' is required"})
    content = await skill_index.get(name)
    if content is None:
        return json.dumps({"error": f"Skill '{name}' not found"})
    return json.dumps({"name": name, "content": content})


async def _handle_update_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    if not name:
        return json.dumps({"error": "'name' is required"})
    entry = await skill_index.update(
        name,
        description=arguments.get("description"),
        tags=arguments.get("tags"),
        content=arguments.get("content"),
        user_id=user_id,
    )
    if entry is None:
        return json.dumps({"error": f"Skill '{name}' not found"})
    return json.dumps({"name": entry.name, "description": entry.description, "tags": entry.tags})


async def _handle_delete_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    if not name:
        return json.dumps({"error": "'name' is required"})
    deleted = await skill_index.delete(name, user_id=user_id)
    if not deleted:
        return json.dumps({"error": f"Skill '{name}' not found"})
    return json.dumps({"deleted": True, "name": name})


_NATIVE_HANDLERS = {
    "schedule_proaction": _handle_schedule_proaction,
    "list_proactions": _handle_list_proactions,
    "store_memory": _handle_store_memory,
    "search_memory": _handle_search_memory,
    "update_memory": _handle_update_memory,
    "delete_memory": _handle_delete_memory,
    "add_instruction": _handle_add_instruction,
    "list_instructions": _handle_list_instructions,
    "delete_instruction": _handle_delete_instruction,
    "create_skill": _handle_create_skill,
    "list_skills": _handle_list_skills,
    "get_skill": _handle_get_skill,
    "update_skill": _handle_update_skill,
    "delete_skill": _handle_delete_skill,
}


A2A_SOURCE = "a2a"

# What an A2A peer may use: MCP tools that only read. No native tool (they write
# memory, skills, instructions and proactions) and no `manage_*` tool (one tool,
# read and write actions). A tool missing from this list is refused, so a new MCP
# tool stays out of A2A until someone decides it is read-only.
A2A_ALLOWED_TOOLS = frozenset(
    {
        "check_conflicts",
        "get_daily_score",
        "get_events_by_date",
        "get_finance_dashboard",
        "get_grocery_list",
        "get_recipe",
        "get_tasks",
        "get_upcoming_events",
        "search",
        "search_ciqual_foods",
        "search_ingredients",
        "search_products",
        "search_recipes",
    }
)


class ToolRouter:
    """Routes tool calls to native handlers or MCP server.

    Tool categories:
    - Memory tools — always available (chat + proaction)
    - Instruction tools — always available (chat + proaction)
    - Skill tools — always available (chat + proaction)
    - Proaction tools — always available (chat + proaction)
    - MCP tools — fetched from the Symfony MCP server

    Calls from the `a2a` source are narrowed to A2A_ALLOWED_TOOLS.
    """

    async def get_tool_definitions(self, include_native: bool = True, source: str | None = None) -> list[dict]:
        """Get available tools in Anthropic tool format.

        Args:
            include_native: If True, include proaction tools (schedule/list proactions).
                            Defaults to True — proaction tools are available in all modes.
            source: Who the tools are for. `a2a` gets the read-only MCP tools and nothing else.
        """
        a2a = source == A2A_SOURCE
        tools = [] if a2a else list(MEMORY_TOOLS) + list(INSTRUCTION_TOOLS) + list(SKILL_TOOLS)

        if include_native and not a2a:
            tools.extend(PROACTION_TOOLS)

        mcp_tools = await mcp_client.list_tools()
        for tool in mcp_tools:
            if a2a and tool["name"] not in A2A_ALLOWED_TOOLS:
                continue
            tools.append(
                {
                    "name": tool["name"],
                    "description": tool.get("description", ""),
                    "input_schema": tool.get("inputSchema", {"type": "object", "properties": {}}),
                }
            )

        return tools

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None, source: str = "chat") -> str:
        """Route a tool call to native handler or MCP server.

        `source` says who triggered the call: chat, chat_stream, proaction, subagent:<name>, a2a or approval.
        """
        if source == A2A_SOURCE and name not in A2A_ALLOWED_TOOLS:
            logger.warning(f"A2A call to a tool outside the read-only list refused: {name}")
            return json.dumps({"error": f"Tool '{name}' is not available over A2A"})

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
            result = await mcp_client.call_tool(name, arguments, user_id=user_id)
            return result
        except Exception as e:
            logger.error(f"Tool call failed: {name}({arguments}): {e}")
            return f'{{"error": "Tool call failed: {e}"}}'

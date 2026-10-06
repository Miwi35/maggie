import json
import logging
from fnmatch import fnmatchcase

from app.agents.registry import SubagentDefinition, subagent_registry
from app.llm.client import create_llm_client, llm_configured
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.personality.engine import current_datetime_line
from app.skills.index import skill_index

logger = logging.getLogger(__name__)

DELEGATE_TOOL = "delegate"

_client = None


def _llm_client():
    global _client
    if _client is None:
        _client = create_llm_client()
    return _client


def select_tools(definition: SubagentDefinition, tools: list[dict]) -> list[dict]:
    """The tools matching the agent's patterns — never `delegate`, so a sub-agent cannot spawn another."""
    return [
        tool
        for tool in tools
        if tool["name"] != DELEGATE_TOOL and any(fnmatchcase(tool["name"], pattern) for pattern in definition.tools)
    ]


class ScopedToolRouter:
    """Refuses any call outside the tools the sub-agent was given, whatever name the model makes up."""

    def __init__(self, router: ToolRouter, allowed: set[str]):
        self._router = router
        self._allowed = allowed

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
    ) -> str:
        if name not in self._allowed:
            return json.dumps({"error": f"Tool '{name}' is not available to this agent"})
        return await self._router.call_tool(name, arguments, user_id=user_id, source=source, context_id=context_id)


async def _system_prompt(definition: SubagentDefinition, user_id: str, tools: list[dict]) -> str:
    memory_context = await AgentMemory().get_memory_context(user_id)
    skills = skill_index.get_skills_index() if any(t["name"] == "get_skill" for t in tools) else ""
    return f"{definition.prompt}{skills}{memory_context}\n\n{current_datetime_line()}"


async def handle_delegate(arguments: dict, user_id: str) -> str:
    """Run a sub-agent on a task in its own conversation and return its answer to the calling agent."""
    name = arguments.get("agent", "")
    task = arguments.get("task", "")
    if not name or not task:
        return json.dumps({"error": "'agent' and 'task' are required"})

    definition = subagent_registry.get(name)
    if definition is None:
        available = [a.name for a in subagent_registry.list_all()]
        return json.dumps({"error": f"Unknown agent '{name}'", "available_agents": available})

    if not llm_configured():
        return json.dumps({"error": "AI service is not configured"})

    router = ToolRouter()
    tools = select_tools(definition, await router.get_tool_definitions(include_native=True))
    system = await _system_prompt(definition, user_id, tools)

    result = await run_tool_loop(
        system,
        [{"role": "user", "content": task}],
        tools,
        client=_llm_client(),
        tool_router=ScopedToolRouter(router, {t["name"] for t in tools}),
        user_id=user_id,
        model=definition.model_id,
        max_iterations=definition.max_iterations,
        call_type="subagent",
        source=f"subagent:{name}",
    )
    return json.dumps(
        {
            "agent": name,
            "result": result["response"],
            "tool_calls": [call["name"] for call in result["tool_calls"]],
        },
        ensure_ascii=False,
    )

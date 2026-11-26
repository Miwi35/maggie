import json
import logging
from fnmatch import fnmatchcase

from app.agents.registry import SubagentDefinition, subagent_registry
from app.agents.report import REPORT_TOOL, ReportingToolRouter, report_tool_definition, specialist_report
from app.container import container
from app.llm.client import client_for, llm_configured
from app.llm.runner import ITERATION_LIMIT_MESSAGE, run_tool_loop
from app.llm.tools import ToolRouter
from app.llm.web_search import WEB_SEARCH_TOOL, with_web_search
from app.memory.agent_memory import AgentMemory
from app.personality.engine import current_datetime_line
from app.skills.index import skill_index
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

DELEGATE_TOOL = "delegate"


def select_tools(definition: SubagentDefinition, tools: list[dict]) -> list[dict]:
    """The tools matching the agent's patterns plus its return; never `delegate`: no sub-agent spawns another."""
    selected = [
        tool
        for tool in tools
        if tool["name"] not in (DELEGATE_TOOL, REPORT_TOOL)
        and any(fnmatchcase(tool["name"], pattern) for pattern in definition.tools)
    ]
    return [*selected, report_tool_definition()]


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
    skills = ""
    if any(t["name"] == "get_skill" for t in tools):
        await skill_index.refresh()
        skills = skill_index.get_skills_index()
    now = current_datetime_line(tz=await resolve_user_timezone(user_id))
    return f"{definition.prompt}\n\nTermine par report_to_maggie.{skills}{memory_context}\n\n{now}"


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

    router = container.tool_router
    tools = select_tools(definition, await router.get_tool_definitions())
    if WEB_SEARCH_TOOL in definition.tools:
        # Anthropic's server tool, not one the router knows: offered, never routed (MAG-384).
        tools = with_web_search(tools, await resolve_user_timezone(user_id))
    system = await _system_prompt(definition, user_id, tools)

    reporting = ReportingToolRouter(ScopedToolRouter(router, {t["name"] for t in tools}))
    result = await run_tool_loop(
        system,
        [{"role": "user", "content": task}],
        tools,
        client=await client_for(user_id, container.llm_client),
        tool_router=reporting,
        user_id=user_id,
        model=definition.model_id,
        max_iterations=definition.max_iterations,
        call_type="subagent",
        source=f"subagent:{name}",
    )
    complete = reporting.report is not None and result["response"] != ITERATION_LIMIT_MESSAGE
    tool_calls = [call["name"] for call in result["tool_calls"] if call["name"] != REPORT_TOOL]
    return json.dumps(
        specialist_report(name, result["response"], reporting.report, tool_calls, complete), ensure_ascii=False
    )

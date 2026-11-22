"""The tool list sent to the model must not change when the native tools move (MAG-945)."""

import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

from app.agents.registry import SubagentDefinition, SubagentRegistry
from app.llm.tools import ToolRouter

SNAPSHOT = Path(__file__).parent / "fixtures" / "tool_definitions.json"

MCP_TOOLS = [
    {"name": "get_events", "description": "Events", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "search_recipes", "description": "Recipes", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "memory_search", "description": "Same name as a native tool"},
    {"name": "claim_due_proactions", "description": "Executor only"},
]

RESEARCHER = SubagentDefinition(
    name="researcher", description="Cherche", model="haiku", tools=["get_events"], prompt="Tu cherches."
)


def _router() -> ToolRouter:
    return ToolRouter()


@pytest.mark.asyncio
async def test_the_tool_list_sent_to_the_model_is_unchanged():
    captured: dict[str, list[dict]] = {}
    for agents_label, agents in (("no_subagents", {}), ("subagents", {"researcher": RESEARCHER})):
        registry = SubagentRegistry(agents=agents)
        with (
            patch("app.llm.tools.mcp_client") as mcp,
            patch("app.llm.tools.subagent_registry", registry),
        ):
            mcp.list_tools = AsyncMock(return_value=MCP_TOOLS)
            for source in (None, "proaction", "a2a"):
                captured[f"{agents_label}/{source}"] = await _router().get_tool_definitions(source)

    if not SNAPSHOT.exists():
        SNAPSHOT.write_text(json.dumps(captured, indent=2, ensure_ascii=False) + "\n")
    assert captured == json.loads(SNAPSHOT.read_text())

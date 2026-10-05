import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.delegate import ScopedToolRouter, handle_delegate, select_tools
from app.agents.registry import SubagentDefinition, SubagentRegistry
from app.config import settings
from app.llm.tools import ToolRouter
from app.metrics import LLM_REQUESTS

RESEARCHER = SubagentDefinition(
    name="researcher",
    description="Recherche en lecture seule",
    model="haiku",
    tools=["search*", "get_*", "list_*", "search_memory"],
    prompt="Tu cherches.",
    max_iterations=6,
)

ALL_TOOLS = [
    {"name": name, "description": "", "input_schema": {"type": "object", "properties": {}}}
    for name in (
        "search_recipes",
        "get_upcoming_events",
        "list_skills",
        "search_memory",
        "create_event",
        "delete_memory",
        "manage_grocery",
        "delegate",
    )
]


@pytest.fixture()
def registry():
    registry = SubagentRegistry(agents={"researcher": RESEARCHER})
    with patch("app.agents.delegate.subagent_registry", registry), patch("app.llm.tools.subagent_registry", registry):
        yield registry


@pytest.fixture()
def router():
    mock = MagicMock()
    mock.get_tool_definitions = AsyncMock(return_value=ALL_TOOLS)
    mock.call_tool = AsyncMock(return_value='{"ok": true}')
    with patch("app.agents.delegate.ToolRouter", return_value=mock):
        yield mock


@pytest.fixture()
def loop():
    result = {"response": "Votre semaine est calme.", "tool_calls": [{"name": "get_upcoming_events"}, {"name": "search_recipes"}]}
    with (
        patch("app.agents.delegate.run_tool_loop", AsyncMock(return_value=result)) as run,
        patch("app.agents.delegate._llm_client", return_value="client"),
        patch("app.agents.delegate.llm_configured", return_value=True),
        patch("app.agents.delegate.AgentMemory") as memory,
    ):
        memory.return_value.get_memory_context = AsyncMock(return_value="\n\nCe que tu sais : allergique aux noix")
        yield run


class TestSelectTools:
    def test_keeps_only_tools_matching_a_pattern(self):
        names = [t["name"] for t in select_tools(RESEARCHER, ALL_TOOLS)]

        assert names == ["search_recipes", "get_upcoming_events", "list_skills", "search_memory"]

    def test_delegate_is_always_removed(self):
        everything = SubagentDefinition(**{**RESEARCHER.__dict__, "tools": ["*"]})

        names = [t["name"] for t in select_tools(everything, ALL_TOOLS)]

        assert "delegate" not in names
        assert "create_event" in names

    def test_patterns_are_case_sensitive(self):
        shouty = SubagentDefinition(**{**RESEARCHER.__dict__, "tools": ["SEARCH*"]})

        assert select_tools(shouty, ALL_TOOLS) == []


class TestHandleDelegate:
    async def test_runs_the_loop_with_the_agent_setup(self, registry, router, loop):
        result = json.loads(await handle_delegate({"agent": "researcher", "task": "Ma semaine"}, "user-1"))

        loop.assert_awaited_once()
        system, messages, tools = loop.await_args.args
        kwargs = loop.await_args.kwargs
        assert system.startswith("Tu cherches.")
        assert "allergique aux noix" in system
        assert messages == [{"role": "user", "content": "Ma semaine"}]
        assert [t["name"] for t in tools] == ["search_recipes", "get_upcoming_events", "list_skills", "search_memory"]
        assert kwargs["model"] == settings.model_aliases["haiku"]
        assert kwargs["max_iterations"] == 6
        assert kwargs["call_type"] == "subagent"
        assert kwargs["source"] == "subagent:researcher"
        assert kwargs["user_id"] == "user-1"
        assert result == {
            "agent": "researcher",
            "result": "Votre semaine est calme.",
            "tool_calls": ["get_upcoming_events", "search_recipes"],
        }

    async def test_the_agent_never_receives_delegate(self, registry, router, loop):
        registry.agents["all"] = SubagentDefinition(**{**RESEARCHER.__dict__, "name": "all", "tools": ["*"]})

        await handle_delegate({"agent": "all", "task": "x"}, "user-1")

        assert "delegate" not in [t["name"] for t in loop.await_args.args[2]]

    async def test_unknown_agent_is_a_json_error(self, registry, router, loop):
        result = json.loads(await handle_delegate({"agent": "ghost", "task": "x"}, "user-1"))

        assert "ghost" in result["error"]
        assert result["available_agents"] == ["researcher"]
        loop.assert_not_awaited()

    @pytest.mark.parametrize("arguments", [{}, {"agent": "researcher"}, {"task": "x"}])
    async def test_missing_argument_is_a_json_error(self, registry, router, loop, arguments):
        result = json.loads(await handle_delegate(arguments, "user-1"))

        assert "required" in result["error"]
        loop.assert_not_awaited()

    async def test_unconfigured_model_is_a_json_error(self, registry, router, loop):
        with patch("app.agents.delegate.llm_configured", return_value=False):
            result = json.loads(await handle_delegate({"agent": "researcher", "task": "x"}, "user-1"))

        assert "not configured" in result["error"]
        loop.assert_not_awaited()


class TestScopedToolRouter:
    async def test_forwards_an_allowed_call_with_its_source(self):
        inner = MagicMock()
        inner.call_tool = AsyncMock(return_value="events")

        result = await ScopedToolRouter(inner, {"get_events"}).call_tool(
            "get_events", {"a": 1}, user_id="u", source="subagent:researcher"
        )

        assert result == "events"
        inner.call_tool.assert_awaited_once_with("get_events", {"a": 1}, user_id="u", source="subagent:researcher")

    async def test_refuses_a_tool_it_was_not_given(self):
        inner = MagicMock()
        inner.call_tool = AsyncMock()

        result = json.loads(await ScopedToolRouter(inner, {"get_events"}).call_tool("delete_event", {}, user_id="u"))

        assert "not available" in result["error"]
        inner.call_tool.assert_not_awaited()


def _tool_use(name: str, tool_input: dict) -> MagicMock:
    block = MagicMock(spec=["type", "name", "input", "id"])
    block.type, block.name, block.input, block.id = "tool_use", name, tool_input, f"tu-{name}"
    return _response([block], "tool_use")


def _text(text: str) -> MagicMock:
    block = MagicMock(spec=["type", "text"])
    block.type, block.text = "text", text
    return _response([block], "end_turn")


def _response(content: list, stop_reason: str) -> MagicMock:
    response = MagicMock()
    response.content = content
    response.stop_reason = stop_reason
    response.usage.input_tokens = 10
    response.usage.output_tokens = 5
    return response


class TestDelegateThroughTheToolRouter:
    @patch("app.llm.tools.mcp_client")
    async def test_subagent_reads_through_mcp_and_answers(self, mcp, registry):
        mcp.list_tools = AsyncMock(
            return_value=[
                {"name": "get_upcoming_events", "description": "", "inputSchema": {"type": "object"}},
                {"name": "create_event", "description": "", "inputSchema": {"type": "object"}},
            ]
        )
        mcp.call_tool = AsyncMock(return_value='{"events": []}')
        client = MagicMock()
        client.messages.create = AsyncMock(
            side_effect=[
                _tool_use("create_event", {"title": "x"}),
                _tool_use("get_upcoming_events", {}),
                _text("Rien de prévu."),
            ]
        )
        model = settings.model_aliases["haiku"]
        metric = LLM_REQUESTS.labels(model=model, call_type="subagent", status="success")
        before = metric._value.get()

        with (
            patch("app.agents.delegate._llm_client", return_value=client),
            patch("app.agents.delegate.llm_configured", return_value=True),
            patch("app.agents.delegate.AgentMemory") as memory,
        ):
            memory.return_value.get_memory_context = AsyncMock(return_value="")
            raw = await ToolRouter().call_tool(
                "delegate", {"agent": "researcher", "task": "Ma semaine"}, user_id="user-1"
            )

        result = json.loads(raw)
        assert result["result"] == "Rien de prévu."
        assert result["tool_calls"] == ["create_event", "get_upcoming_events"]
        # The read ran; the write the model made up was refused before reaching MCP.
        mcp.call_tool.assert_awaited_once_with("get_upcoming_events", {}, user_id="user-1")
        assert client.messages.create.await_args.kwargs["model"] == model
        assert metric._value.get() - before == 3

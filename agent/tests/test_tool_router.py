import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.time_tool import DATE_TIME_TOOLS
from app.agents.registry import SubagentDefinition, SubagentRegistry
from app.llm.tools import (
    INSTRUCTION_TOOLS,
    MEMORY_TOOLS,
    PENDING_APPROVAL_MESSAGE,
    POLICY_DENIED_MESSAGE,
    SKILL_TOOLS,
    ToolRouter,
)
from app.policy.engine import PolicyEngine

NUM_MEMORY_TOOLS = len(MEMORY_TOOLS)
NUM_INSTRUCTION_TOOLS = len(INSTRUCTION_TOOLS)
NUM_SKILL_TOOLS = len(SKILL_TOOLS)
NUM_DATE_TIME_TOOLS = len(DATE_TIME_TOOLS)
NUM_ANSWER_TOOLS = 1  # answer_approval (MAG-683)
# Every native tool; the proaction tools are the API's since MAG-327, served over MCP
NUM_DEFAULT_TOOLS = (
    NUM_MEMORY_TOOLS + NUM_INSTRUCTION_TOOLS + NUM_SKILL_TOOLS + NUM_DATE_TIME_TOOLS + NUM_ANSWER_TOOLS
)


class TestToolRouter:
    @patch("app.llm.tools.mcp_client")
    async def test_get_tool_definitions_converts_mcp_format(self, mock_mcp_client):
        """MCP tool definitions should be converted to Anthropic tool format."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {
                "name": "create_event",
                "description": "Create a calendar event",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "date": {"type": "string"},
                    },
                    "required": ["title", "date"],
                },
            },
            {
                "name": "list_events",
                "description": "List upcoming events",
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                },
            },
        ])

        router = ToolRouter()
        tools = await router.get_tool_definitions()

        # Native tools + 2 MCP tools
        assert len(tools) == NUM_DEFAULT_TOOLS + 2

        # MCP tools come after native tools
        mcp_tools = tools[NUM_DEFAULT_TOOLS:]
        assert mcp_tools[0]["name"] == "create_event"
        assert mcp_tools[0]["description"] == "Create a calendar event"
        assert mcp_tools[0]["input_schema"]["type"] == "object"
        assert "title" in mcp_tools[0]["input_schema"]["properties"]
        assert mcp_tools[0]["input_schema"]["required"] == ["title", "date"]

        assert mcp_tools[1]["name"] == "list_events"
        assert mcp_tools[1]["description"] == "List upcoming events"

    @patch("app.llm.tools.mcp_client")
    async def test_the_proaction_tools_are_the_api_s(self, mock_mcp_client):
        """No native proaction tool is left: schedule_proaction and list_proactions come from MCP (MAG-327)."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {"name": "schedule_proaction", "description": "API's", "inputSchema": {"type": "object", "properties": {}}},
            {"name": "list_proactions", "description": "API's", "inputSchema": {"type": "object", "properties": {}}},
        ])

        tools = await ToolRouter().get_tool_definitions()

        assert len(tools) == NUM_DEFAULT_TOOLS + 2
        proactions = [t for t in tools if t["name"] in {"schedule_proaction", "list_proactions"}]
        assert [t["description"] for t in proactions] == ["API's", "API's"]

    @patch("app.llm.tools.mcp_client")
    async def test_schedule_proaction_is_called_on_the_api(self, mock_mcp_client):
        mock_mcp_client.call_tool = AsyncMock(return_value='{"success": true}')
        arguments = {"prompt": "Check calendar", "scheduled_at": "2037-02-20T09:00:00+01:00"}

        result = await ToolRouter().call_tool("schedule_proaction", arguments, user_id="test-user")

        assert result == '{"success": true}'
        mock_mcp_client.call_tool.assert_awaited_once_with("schedule_proaction", arguments, user_id="test-user")

    @patch("app.llm.tools.mcp_client")
    async def test_get_tool_definitions_with_missing_fields(self, mock_mcp_client):
        """Tools missing description or inputSchema get sensible defaults."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {
                "name": "bare_tool",
            },
        ])

        router = ToolRouter()
        tools = await router.get_tool_definitions()

        assert len(tools) == NUM_DEFAULT_TOOLS + 1
        bare = tools[-1]
        assert bare["name"] == "bare_tool"
        assert bare["description"] == ""
        assert bare["input_schema"] == {"type": "object", "properties": {}}

    @patch("app.llm.tools.mcp_client")
    async def test_call_tool_delegates_to_mcp_client(self, mock_mcp_client):
        """call_tool should delegate to mcp_client.call_tool for MCP tools."""
        mock_mcp_client.call_tool = AsyncMock(return_value="Event created successfully")

        router = ToolRouter()
        result = await router.call_tool("create_event", {"title": "Test", "date": "2026-03-01"}, user_id="user-1")

        assert result == "Event created successfully"
        mock_mcp_client.call_tool.assert_awaited_once_with(
            "create_event", {"title": "Test", "date": "2026-03-01"}, user_id="user-1"
        )

    @patch("app.llm.tools.mcp_client")
    async def test_call_tool_accepts_source(self, mock_mcp_client):
        """call_tool accepts a source without changing how the call is routed."""
        mock_mcp_client.call_tool = AsyncMock(return_value="ok")

        router = ToolRouter()
        result = await router.call_tool("create_event", {"title": "Test"}, user_id="user-1", source="subagent:cook")

        assert result == "ok"
        mock_mcp_client.call_tool.assert_awaited_once_with("create_event", {"title": "Test"}, user_id="user-1")

    async def test_call_native_tool_without_user_id(self):
        """Native tools should fail if user_id is not provided."""
        router = ToolRouter()
        result = await router.call_tool("list_instructions", {})

        assert "error" in result
        assert "user_id" in result

    async def test_call_memory_tool_puts_the_exchange_on_file_before_running(self):
        """A memory tool runs after the current exchange is written, so a fiche can cite it."""
        order: list[str] = []

        async def record(user_id, context_id, **_):
            order.append("conversation")

        async def handler(arguments, user_id):
            order.append("tool")
            return json.dumps({"resultats": []})

        with (
            patch("app.llm.tools.conversations.record_thread", side_effect=record) as recorded,
            patch.dict("app.llm.tools._NATIVE_HANDLERS", {"memory_search": handler}),
        ):
            result = await ToolRouter().call_tool(
                "memory_search", {"requete": "coffee"}, user_id="test-user", context_id="ctx-1"
            )

        assert json.loads(result) == {"resultats": []}
        assert order == ["conversation", "tool"]
        recorded.assert_awaited_once_with("test-user", "ctx-1")

    @patch("app.llm.tools.mcp_client")
    async def test_the_old_memory_tools_are_gone(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(return_value=[])
        names = {t["name"] for t in await ToolRouter().get_tool_definitions()}

        assert not names & {"store_memory", "search_memory", "update_memory", "delete_memory"}


class TestThePolicyGuard:
    """`call_tool` is the one place every tool call passes, so it is where the policy stands (MAG-4).

    The point of each test is the *absence* of the call: an `ask` that still deletes the
    event and merely also asks would be worse than no policy at all.
    """

    POLICY = PolicyEngine.from_mapping(
        {
            "default": "allow",
            "rules": [
                {"tools": ["delete_instruction"], "mode": "allow"},
                {"tools": ["delete_*"], "mode": "ask"},
                {"tools": ["drop_database"], "mode": "deny"},
            ],
        }
    )

    @pytest.fixture()
    def guard(self):
        """The router's policy and its pending-action store, both under control."""
        action = MagicMock()
        action.id = "01HAPPROVAL"
        repo = MagicMock()
        repo.create = AsyncMock(return_value=action)

        with (
            patch("app.llm.tools.policy_engine", self.POLICY),
            patch("app.llm.tools.pending_action_repo", repo),
            patch("app.llm.tools.mcp_client") as mcp,
            patch("app.llm.tools.build_summary", new_callable=AsyncMock) as summary,
        ):
            summary.return_value = "Supprimer l'événement « Test validation »"
            mcp.call_tool = AsyncMock(return_value='{"deleted": true}')
            yield SimpleNamespace(repo=repo, mcp=mcp, summary=summary)

    async def test_an_ask_tool_is_not_routed(self, guard):
        await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}, user_id="user-1")

        guard.mcp.call_tool.assert_not_awaited()

    async def test_an_ask_tool_is_held_with_its_arguments_source_and_thread(self, guard):
        await ToolRouter().call_tool(
            "delete_event", {"eventId": "evt-1"}, user_id="user-1", source="chat_stream", context_id="ctx-1"
        )

        guard.repo.create.assert_awaited_once_with(
            "user-1",
            "delete_event",
            {"eventId": "evt-1"},
            source="chat_stream",
            context_id="ctx-1",
            summary="Supprimer l'événement « Test validation »",
        )

    async def test_the_held_action_carries_a_readable_summary_built_from_a_read_tool(self, guard):
        await ToolRouter().call_tool("delete_event", {"id": "evt-1"}, user_id="user-1")

        guard.summary.assert_awaited_once_with("delete_event", {"id": "evt-1"}, "user-1")

    async def test_a_summary_that_cannot_be_built_never_stops_the_action_from_being_held(self, guard):
        guard.summary.side_effect = RuntimeError("boom")

        result = json.loads(await ToolRouter().call_tool("delete_event", {"id": "evt-1"}, user_id="user-1"))

        assert result["status"] == "pending_approval"
        assert guard.repo.create.await_args.kwargs["summary"] is None

    async def test_an_ask_tool_answers_the_model_with_the_approval_id_and_an_order_not_to_retry(self, guard):
        result = json.loads(await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}, user_id="user-1"))

        assert result == {
            "status": "pending_approval",
            "approval_id": "01HAPPROVAL",
            "message": PENDING_APPROVAL_MESSAGE,
        }

    async def test_a_native_ask_tool_is_held_before_its_handler_runs(self, guard):
        # The guard sits before the native/MCP fork, so a native tool is covered too.
        result = json.loads(await ToolRouter().call_tool("delete_skill", {"name": "courses"}, user_id="user-1"))

        assert result["status"] == "pending_approval"

    async def test_the_second_identical_call_gets_the_same_approval(self, guard):
        # Deduplication is the repository's, so what is checked here is that the router
        # asks for it instead of creating a card per turn.
        first = json.loads(await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}, user_id="user-1"))
        second = json.loads(await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}, user_id="user-1"))

        assert first["approval_id"] == second["approval_id"]
        assert guard.repo.create.await_count == 2
        assert guard.repo.create.await_args_list[0] == guard.repo.create.await_args_list[1]

    async def test_a_denied_tool_is_refused_without_being_routed(self, guard):
        result = json.loads(await ToolRouter().call_tool("drop_database", {}, user_id="user-1"))

        assert result == {"error": POLICY_DENIED_MESSAGE}
        guard.mcp.call_tool.assert_not_awaited()
        guard.repo.create.assert_not_awaited()

    async def test_an_allowed_tool_still_runs(self, guard):
        result = await ToolRouter().call_tool("create_event", {"title": "Dentiste"}, user_id="user-1")

        assert result == '{"deleted": true}'
        guard.repo.create.assert_not_awaited()

    async def test_the_exception_rule_lets_maggie_correct_her_own_notes(self, guard):
        with patch.dict("app.llm.tools._NATIVE_HANDLERS", {"delete_instruction": AsyncMock(return_value='{"deleted": true}')}):
            result = json.loads(await ToolRouter().call_tool("delete_instruction", {"instruction_id": "i1"}, user_id="user-1"))

        assert result == {"deleted": True}
        guard.repo.create.assert_not_awaited()

    async def test_an_approved_call_is_replayed_for_real(self, guard):
        result = await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}, user_id="user-1", source="approval")

        assert result == '{"deleted": true}'
        guard.repo.create.assert_not_awaited()
        guard.mcp.call_tool.assert_awaited_once_with("delete_event", {"eventId": "evt-1"}, user_id="user-1")

    async def test_an_ask_tool_with_nobody_to_ask_is_refused_rather_than_run(self, guard):
        result = json.loads(await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}))

        assert "error" in result
        guard.mcp.call_tool.assert_not_awaited()
        guard.repo.create.assert_not_awaited()

    async def test_an_action_that_cannot_be_stored_is_refused_rather_than_run(self, guard):
        # No row means nothing can ever approve it; running it anyway would be acting
        # behind the user's back, which is the one outcome the policy exists to prevent.
        guard.repo.create.side_effect = RuntimeError("db down")

        result = json.loads(await ToolRouter().call_tool("delete_event", {"eventId": "evt-1"}, user_id="user-1"))

        assert "error" in result
        guard.mcp.call_tool.assert_not_awaited()


def _registry(*names: str) -> SubagentRegistry:
    return SubagentRegistry(
        agents={
            name: SubagentDefinition(
                name=name, description=f"Description de {name}", model="haiku", tools=["get_*"], prompt="Prompt"
            )
            for name in names
        }
    )


class TestDelegateTool:
    @patch("app.llm.tools.mcp_client")
    async def test_absent_when_no_sub_agent_is_loaded(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(return_value=[])

        with patch("app.llm.tools.subagent_registry", _registry()):
            tools = await ToolRouter().get_tool_definitions()

        assert "delegate" not in [t["name"] for t in tools]
        assert len(tools) == NUM_DEFAULT_TOOLS

    @patch("app.llm.tools.mcp_client")
    async def test_present_with_the_agents_as_enum_and_in_the_description(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(return_value=[])

        with patch("app.llm.tools.subagent_registry", _registry("researcher", "cook")):
            tools = await ToolRouter().get_tool_definitions()

        delegate = next(t for t in tools if t["name"] == "delegate")
        assert delegate["input_schema"]["properties"]["agent"]["enum"] == ["cook", "researcher"]
        assert delegate["input_schema"]["required"] == ["agent", "task"]
        assert "- researcher : Description de researcher" in delegate["description"]
        assert "- cook : Description de cook" in delegate["description"]

    @patch("app.llm.tools.mcp_client")
    async def test_never_offered_to_a_peer_over_a2a(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(return_value=[])

        with patch("app.llm.tools.subagent_registry", _registry("researcher")):
            tools = await ToolRouter().get_tool_definitions(source="a2a")

        assert "delegate" not in [t["name"] for t in tools]

    async def test_a2a_cannot_call_it_either(self):
        result = json.loads(await ToolRouter().call_tool("delegate", {}, user_id="u", source="a2a"))

        assert "not available over A2A" in result["error"]

    async def test_call_reaches_the_delegate_handler(self):
        with patch("app.agents.delegate.handle_delegate", AsyncMock(return_value='{"agent": "researcher"}')) as handle:
            result = await ToolRouter().call_tool("delegate", {"agent": "researcher", "task": "x"}, user_id="u")

        assert result == '{"agent": "researcher"}'
        handle.assert_awaited_once_with({"agent": "researcher", "task": "x"}, "u")

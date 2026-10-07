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
    PROACTION_TOOLS,
    SKILL_TOOLS,
    ToolRouter,
)
from app.policy.engine import PolicyEngine

NUM_MEMORY_TOOLS = len(MEMORY_TOOLS)
NUM_PROACTION_TOOLS = len(PROACTION_TOOLS)
NUM_INSTRUCTION_TOOLS = len(INSTRUCTION_TOOLS)
NUM_SKILL_TOOLS = len(SKILL_TOOLS)
NUM_DATE_TIME_TOOLS = len(DATE_TIME_TOOLS)
# Tools available when include_native=False (no proaction tools)
NUM_ALWAYS_ON_TOOLS = NUM_MEMORY_TOOLS + NUM_INSTRUCTION_TOOLS + NUM_SKILL_TOOLS + NUM_DATE_TIME_TOOLS
# All native tools are included by default (include_native=True)
NUM_DEFAULT_TOOLS = NUM_ALWAYS_ON_TOOLS + NUM_PROACTION_TOOLS


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

        # Default tools (including proaction) + 2 MCP tools
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
    async def test_get_tool_definitions_with_native(self, mock_mcp_client):
        """include_native=True should include proaction tools alongside memory tools."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {"name": "create_event", "description": "Create event", "inputSchema": {"type": "object", "properties": {}}},
        ])

        router = ToolRouter()
        tools = await router.get_tool_definitions(include_native=True)

        # All native tools (including proaction) + 1 MCP tool
        assert len(tools) == NUM_DEFAULT_TOOLS + 1
        names = [t["name"] for t in tools]
        assert "schedule_proaction" in names
        assert "list_proactions" in names
        assert "store_memory" in names
        assert "search_memory" in names
        assert "create_event" in names

    @patch("app.llm.tools.mcp_client")
    async def test_get_tool_definitions_without_native(self, mock_mcp_client):
        """include_native=False should return memory tools + MCP tools (no proaction tools)."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {"name": "create_event", "description": "Create event", "inputSchema": {"type": "object", "properties": {}}},
        ])

        router = ToolRouter()
        tools = await router.get_tool_definitions(include_native=False)

        assert len(tools) == NUM_ALWAYS_ON_TOOLS + 1
        names = [t["name"] for t in tools]
        assert "store_memory" in names
        assert "add_instruction" in names
        assert "create_skill" in names
        assert "create_event" in names
        assert "schedule_proaction" not in names

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

    @patch("app.llm.tools.proaction_repo")
    async def test_call_native_tool_schedule_proaction(self, mock_repo):
        """Native schedule_proaction tool should create a proaction."""
        from unittest.mock import MagicMock

        mock_proaction = MagicMock()
        mock_proaction.to_dict.return_value = {
            "id": "abc123",
            "prompt": "Check calendar",
            "status": "pending",
            "scheduledAt": "2026-02-20T09:00:00+00:00",
        }
        mock_repo.create = AsyncMock(return_value=mock_proaction)

        router = ToolRouter()
        result = await router.call_tool(
            "schedule_proaction",
            {"prompt": "Check calendar", "scheduled_at": "2026-02-20T09:00:00+00:00"},
            user_id="test-user",
        )

        assert "abc123" in result
        mock_repo.create.assert_awaited_once()

    async def test_call_native_tool_without_user_id(self):
        """Native tools should fail if user_id is not provided."""
        router = ToolRouter()
        result = await router.call_tool("schedule_proaction", {"prompt": "test", "scheduled_at": "2026-02-20T09:00:00Z"})

        assert "error" in result
        assert "user_id" in result

    @patch("app.llm.tools.memory_repo")
    async def test_call_store_memory(self, mock_repo):
        """store_memory tool should persist via memory_repo."""
        from unittest.mock import MagicMock

        mock_memory = MagicMock()
        mock_memory.to_dict.return_value = {
            "id": "mem123",
            "content": "Likes coffee",
            "type": "factual",
        }
        mock_repo.store = AsyncMock(return_value=mock_memory)

        router = ToolRouter()
        result = await router.call_tool(
            "store_memory",
            {"content": "Likes coffee", "type": "factual"},
            user_id="test-user",
        )

        assert "mem123" in result
        mock_repo.store.assert_awaited_once_with("test-user", "Likes coffee", "factual")

    @patch("app.llm.tools.memory_repo")
    async def test_call_search_memory(self, mock_repo):
        """search_memory tool should query via memory_repo."""
        from unittest.mock import MagicMock

        mock_memory = MagicMock()
        mock_memory.to_dict.return_value = {"id": "mem1", "content": "Likes coffee"}
        mock_repo.search = AsyncMock(return_value=[mock_memory])

        router = ToolRouter()
        result = await router.call_tool(
            "search_memory",
            {"query": "coffee"},
            user_id="test-user",
        )

        assert "coffee" in result
        mock_repo.search.assert_awaited_once_with("test-user", "coffee", None)


class TestThePolicyGuard:
    """`call_tool` is the one place every tool call passes, so it is where the policy stands (MAG-4).

    The point of each test is the *absence* of the call: an `ask` that still deletes the
    event and merely also asks would be worse than no policy at all.
    """

    POLICY = PolicyEngine.from_mapping(
        {
            "default": "allow",
            "rules": [
                {"tools": ["delete_memory"], "mode": "allow"},
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
            patch("app.llm.tools.memory_repo") as memory,
            patch("app.llm.tools.build_summary", new_callable=AsyncMock) as summary,
        ):
            summary.return_value = "Supprimer l'événement « Test validation »"
            mcp.call_tool = AsyncMock(return_value='{"deleted": true}')
            memory.delete = AsyncMock(return_value=True)
            yield SimpleNamespace(repo=repo, mcp=mcp, memory=memory, summary=summary)

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
        result = json.loads(await ToolRouter().call_tool("delete_memory", {"memory_id": "m1"}, user_id="user-1"))

        assert result == {"deleted": True, "id": "m1"}
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
    async def test_also_present_without_proaction_tools(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(return_value=[])

        with patch("app.llm.tools.subagent_registry", _registry("researcher")):
            tools = await ToolRouter().get_tool_definitions(include_native=False)

        assert "delegate" in [t["name"] for t in tools]

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

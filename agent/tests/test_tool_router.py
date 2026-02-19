from unittest.mock import AsyncMock, patch

from app.llm.tools import ToolRouter


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

        assert len(tools) == 2

        # Verify first tool conversion
        assert tools[0]["name"] == "create_event"
        assert tools[0]["description"] == "Create a calendar event"
        assert tools[0]["input_schema"]["type"] == "object"
        assert "title" in tools[0]["input_schema"]["properties"]
        assert tools[0]["input_schema"]["required"] == ["title", "date"]

        # Verify second tool conversion
        assert tools[1]["name"] == "list_events"
        assert tools[1]["description"] == "List upcoming events"

    @patch("app.llm.tools.mcp_client")
    async def test_get_tool_definitions_with_native(self, mock_mcp_client):
        """include_native=True should prepend native proaction tools."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {"name": "create_event", "description": "Create event", "inputSchema": {"type": "object", "properties": {}}},
        ])

        router = ToolRouter()
        tools = await router.get_tool_definitions(include_native=True)

        # Should have 2 native tools + 1 MCP tool
        assert len(tools) == 3
        names = [t["name"] for t in tools]
        assert "schedule_proaction" in names
        assert "list_proactions" in names
        assert "create_event" in names

    @patch("app.llm.tools.mcp_client")
    async def test_get_tool_definitions_without_native(self, mock_mcp_client):
        """include_native=False should only return MCP tools."""
        mock_mcp_client.list_tools = AsyncMock(return_value=[
            {"name": "create_event", "description": "Create event", "inputSchema": {"type": "object", "properties": {}}},
        ])

        router = ToolRouter()
        tools = await router.get_tool_definitions(include_native=False)

        assert len(tools) == 1
        assert tools[0]["name"] == "create_event"

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

        assert len(tools) == 1
        assert tools[0]["name"] == "bare_tool"
        assert tools[0]["description"] == ""
        assert tools[0]["input_schema"] == {"type": "object", "properties": {}}

    @patch("app.llm.tools.mcp_client")
    async def test_call_tool_delegates_to_mcp_client(self, mock_mcp_client):
        """call_tool should delegate to mcp_client.call_tool for MCP tools."""
        mock_mcp_client.call_tool = AsyncMock(return_value="Event created successfully")

        router = ToolRouter()
        result = await router.call_tool("create_event", {"title": "Test", "date": "2026-03-01"})

        assert result == "Event created successfully"
        mock_mcp_client.call_tool.assert_awaited_once_with("create_event", {"title": "Test", "date": "2026-03-01"})

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

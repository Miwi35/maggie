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
        """call_tool should delegate to mcp_client.call_tool and return its result."""
        mock_mcp_client.call_tool = AsyncMock(return_value="Event created successfully")

        router = ToolRouter()
        result = await router.call_tool("create_event", {"title": "Test", "date": "2026-03-01"})

        assert result == "Event created successfully"
        mock_mcp_client.call_tool.assert_awaited_once_with("create_event", {"title": "Test", "date": "2026-03-01"})

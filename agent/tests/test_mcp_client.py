import json

import httpx
import pytest
import respx

from app.mcp.client import McpClient


@pytest.fixture()
def mcp_server_url() -> str:
    return "http://nginx/_mcp"


@pytest.fixture()
def client(mcp_server_url: str) -> McpClient:
    """Create an McpClient pointing at the default server URL."""
    c = McpClient()
    c.server_url = mcp_server_url
    return c


class TestMcpClient:
    @respx.mock
    async def test_connect_captures_session_id(self, client: McpClient, mcp_server_url: str):
        """After connect(), _session_id should be captured from the response header."""
        # Mock the initialize request
        respx.post(mcp_server_url).mock(
            side_effect=[
                # 1st call: initialize
                httpx.Response(
                    200,
                    json={
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "protocolVersion": "2025-03-26",
                            "serverInfo": {"name": "test-server", "version": "1.0.0"},
                            "capabilities": {},
                        },
                    },
                    headers={"mcp-session-id": "session-abc-123"},
                ),
                # 2nd call: initialized notification
                httpx.Response(200),
                # 3rd call: tools/list
                httpx.Response(
                    200,
                    json={
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "tools": [
                                {
                                    "name": "get_events",
                                    "description": "List calendar events",
                                    "inputSchema": {"type": "object", "properties": {}},
                                }
                            ]
                        },
                    },
                    headers={"mcp-session-id": "session-abc-123"},
                ),
            ]
        )

        await client.connect()

        assert client._session_id == "session-abc-123"
        assert len(client._tools) == 1
        assert client._tools[0]["name"] == "get_events"

        await client.disconnect()

    @respx.mock
    async def test_call_tool_returns_text_content(self, client: McpClient, mcp_server_url: str):
        """call_tool should extract text from MCP content blocks."""
        # Manually set up the client as if already connected
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        respx.post(mcp_server_url).mock(
            return_value=httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {
                        "content": [
                            {"type": "text", "text": "Event created: Meeting at 10am"},
                        ]
                    },
                },
            )
        )

        result = await client.call_tool("create_event", {"title": "Meeting", "time": "10am"})

        assert result == "Event created: Meeting at 10am"

        await client.disconnect()

    @respx.mock
    async def test_call_tool_with_no_response(self, client: McpClient, mcp_server_url: str):
        """When the server returns an error status, call_tool returns error JSON."""
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        respx.post(mcp_server_url).mock(
            return_value=httpx.Response(500, text="Internal Server Error")
        )

        result = await client.call_tool("broken_tool", {})

        # _send_request returns None on non-200, so call_tool returns error JSON
        parsed = json.loads(result)
        assert "error" in parsed

        await client.disconnect()

    @respx.mock
    async def test_call_tool_sends_user_header(self, client: McpClient, mcp_server_url: str):
        """The MCP server needs to know which user the tool acts for."""
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        route = respx.post(mcp_server_url).mock(
            return_value=httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {"content": [{"type": "text", "text": "{}"}]},
                },
            )
        )

        await client.call_tool("manage_accounts", {"action": "list"}, user_id="01JABCDEF0123456789ABCDEFG")

        assert route.calls.last.request.headers["X-Maggie-User-Id"] == "01JABCDEF0123456789ABCDEFG"

        await client.disconnect()

    @respx.mock
    async def test_call_tool_without_user_omits_header(self, client: McpClient, mcp_server_url: str):
        """No user in context means no impersonation header at all."""
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        route = respx.post(mcp_server_url).mock(
            return_value=httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {"content": [{"type": "text", "text": "{}"}]},
                },
            )
        )

        await client.call_tool("manage_accounts", {"action": "list"})

        assert "X-Maggie-User-Id" not in route.calls.last.request.headers

        await client.disconnect()

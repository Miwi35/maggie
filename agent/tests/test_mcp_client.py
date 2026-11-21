import asyncio
import json
from unittest.mock import patch

import httpx
import pytest
import respx

from app.mcp import client as client_module
from app.mcp.client import McpClient


@pytest.fixture(autouse=True)
def no_retry_delay(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(client_module, "CONNECT_RETRY_DELAY_SECONDS", 0)


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
    @pytest.mark.parametrize("failure", [httpx.ConnectTimeout("connect timed out"), httpx.ConnectError("refused")])
    async def test_call_tool_retries_a_connection_that_never_opened(
        self, client: McpClient, mcp_server_url: str, failure: httpx.TransportError
    ):
        """Right after a rollout the first connection can reach the pod that just left: retry it."""
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        route = respx.post(mcp_server_url).mock(
            side_effect=[
                failure,
                httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "ok"}]}}),
            ]
        )

        result = await client.call_tool("get_events", {})

        assert result == "ok"
        assert route.call_count == 2

        await client.disconnect()

    @respx.mock
    async def test_call_tool_gives_up_after_three_connection_attempts(self, client: McpClient, mcp_server_url: str):
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        route = respx.post(mcp_server_url).mock(side_effect=httpx.ConnectError("refused"))

        result = await client.call_tool("get_events", {})

        assert json.loads(result) == {"error": "No response from MCP server"}
        assert route.call_count == 3

        await client.disconnect()

    @respx.mock
    async def test_call_tool_does_not_retry_a_request_already_sent(self, client: McpClient, mcp_server_url: str):
        """A read timeout means the server may have acted: a retry could write twice."""
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"

        route = respx.post(mcp_server_url).mock(side_effect=httpx.ReadTimeout("read timed out"))

        result = await client.call_tool("create_event", {})

        assert json.loads(result) == {"error": "No response from MCP server"}
        assert route.call_count == 1

        await client.disconnect()

    async def test_connect_opens_connections_within_five_seconds(self, client: McpClient):
        """A dead endpoint must fail fast enough to leave room for the retry."""
        client._send_request = _no_response  # type: ignore[method-assign]

        await client.connect()

        assert client._http_client is not None
        assert client._http_client.timeout.connect == 5.0
        assert client._http_client.timeout.read == 30.0

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


class TestConcurrentReconnect:
    @staticmethod
    def _rpc(result: dict) -> dict:
        return {"jsonrpc": "2.0", "id": 1, "result": result}

    @respx.mock
    async def test_a_call_in_flight_survives_a_reconnect_from_another_task(
        self, client: McpClient, mcp_server_url: str
    ):
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"
        client._tools = [{"name": "get_event"}]
        client._last_activity = 0  # stale: the next ensure_connected reconnects

        call_started = asyncio.Event()
        release_call = asyncio.Event()

        async def respond(request: httpx.Request) -> httpx.Response:
            method = json.loads(request.content)["method"]
            if method == "tools/call":
                call_started.set()
                await release_call.wait()
                return httpx.Response(200, json=self._rpc({"content": [{"type": "text", "text": "the event"}]}))
            if method == "tools/list":
                return httpx.Response(200, json=self._rpc({"tools": [{"name": "get_event"}]}))
            return httpx.Response(200, json=self._rpc({"serverInfo": {}}), headers={"mcp-session-id": "new"})

        respx.post(mcp_server_url).mock(side_effect=respond)

        in_flight = asyncio.create_task(client.call_tool("get_event", {"id": "1"}))
        await call_started.wait()
        in_use = client._http_client
        await client.ensure_connected()
        # Closing it would cut the request still waiting on it.
        assert client._http_client is in_use
        assert in_use is not None and not in_use.is_closed
        release_call.set()

        assert await in_flight == "the event"

        await client.disconnect()

    @respx.mock
    async def test_concurrent_reconnections_send_one_initialize(self, client: McpClient, mcp_server_url: str):
        client._last_activity = 0

        async def respond(request: httpx.Request) -> httpx.Response:
            method = json.loads(request.content)["method"]
            await asyncio.sleep(0.01)
            if method == "tools/list":
                return httpx.Response(200, json=self._rpc({"tools": [{"name": "get_event"}]}))
            return httpx.Response(200, json=self._rpc({"serverInfo": {}}), headers={"mcp-session-id": "new"})

        route = respx.post(mcp_server_url).mock(side_effect=respond)

        await asyncio.gather(client.ensure_connected(), client.ensure_connected(), client.ensure_connected())

        methods = [json.loads(c.request.content)["method"] for c in route.calls]
        assert methods.count("initialize") == 1

        await client.disconnect()


async def _no_response(*_args, **_kwargs) -> None:
    return None


class TestInvalidParams:
    @respx.mock
    async def test_call_tool_invalid_params_names_the_fields_and_the_expected_form(
        self, client: McpClient, mcp_server_url: str
    ):
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"
        schema = {"type": "object", "properties": {"quantity": {"type": "integer"}}}
        client._tools = [{"name": "add_grocery_item", "inputSchema": schema}]
        respx.post(mcp_server_url).mock(
            return_value=httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "error": {
                        "code": -32602,
                        "message": "Invalid parameters for tool 'add_grocery_item'",
                        "data": {
                            "validation_errors": [
                                {"pointer": "/quantity", "keyword": "type", "message": "Must be of type integer"}
                            ]
                        },
                    },
                },
            )
        )

        with patch("app.mcp.client.capture_signal") as signal:
            result = await client.call_tool("add_grocery_item", {"quantity": "x"})

        assert json.loads(result) == {
            "error": "invalid_input",
            "tool": "add_grocery_item",
            "fields": [{"field": "quantity", "problem": "Must be of type integer"}],
            "expected": schema,
        }
        signal.assert_not_called()
        await client.disconnect()

    @respx.mock
    async def test_call_tool_another_json_rpc_error_keeps_its_message(self, client: McpClient, mcp_server_url: str):
        client._http_client = httpx.AsyncClient(timeout=30.0)
        client._session_id = "session-existing"
        respx.post(mcp_server_url).mock(
            return_value=httpx.Response(
                200, json={"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "Internal boom"}}
            )
        )

        with patch("app.mcp.client.capture_signal") as signal:
            result = await client.call_tool("add_grocery_item", {})

        assert json.loads(result) == {"error": "Internal boom"}
        assert signal.call_args.kwargs["reason"] == "jsonrpc_error"
        await client.disconnect()

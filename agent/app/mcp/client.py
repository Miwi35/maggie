import asyncio
import json
import logging
import time

import httpx

from app.config import settings
from app.e2e_coverage import journey_headers
from app.error_tracking import MCP_TOOL_ERROR, capture_signal
from app.llm.tool_failures import invalid_input

SESSION_STALENESS_SECONDS = 300  # 5 minutes

USER_HEADER = "X-Maggie-User-Id"

# What `call_tool` answers when the server could not be reached or did not answer at all.
NO_RESPONSE_ERROR = "No response from MCP server"

# The JSON-RPC code of arguments the server's schema refuses (MAG-872).
INVALID_PARAMS_CODE = -32602

# Just after a rollout, a new connection can still be routed to the pod that left and hang:
# connecting fails fast and is tried again, since the request was never sent (MAG-704).
TIMEOUT = httpx.Timeout(30.0, connect=5.0)
CONNECT_ATTEMPTS = 3
CONNECT_RETRY_DELAY_SECONDS = 1.0

logger = logging.getLogger(__name__)


def _invalid_fields(error: dict) -> list[dict]:
    """The faulty fields of a `-32602` error: the server's validation errors, `pointer` as a name."""
    data = error.get("data")
    errors = data.get("validation_errors") if isinstance(data, dict) else None
    fields = [
        {"field": str(item.get("pointer", "")).removeprefix("/"), "problem": str(item.get("message", ""))}
        for item in errors or []
        if isinstance(item, dict)
    ]
    return fields or [{"field": "", "problem": str(error.get("message", ""))}]


class McpClient:
    """MCP client using Streamable HTTP transport to communicate with the Symfony MCP server."""

    def __init__(self):
        self.server_url = settings.mcp_server_url
        self._http_client: httpx.AsyncClient | None = None
        self._session_id: str | None = None
        self._session_expired: bool = False
        self._tools: list[dict] = []
        self._last_activity: float = 0.0
        self._reconnect_lock = asyncio.Lock()

    async def connect(self) -> None:
        """Open a new MCP session. The HTTP client is kept: calls in flight still use it."""
        if self._http_client is None or self._http_client.is_closed:
            self._http_client = httpx.AsyncClient(timeout=TIMEOUT)
        self._session_id = None
        self._session_expired = False

        # Initialize MCP session
        response = await self._send_request(
            "initialize",
            {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "maggie-agent-hub", "version": "0.1.0"},
            },
        )

        if not response:
            logger.error("MCP initialize failed — no response from server")
            return

        logger.info(f"MCP server: {response.get('serverInfo', {})}")

        # Send initialized notification
        await self._send_notification("notifications/initialized", {})

        # List available tools
        tools_response = await self._send_request("tools/list", {})
        if tools_response:
            self._tools = tools_response.get("tools", [])
            self._last_activity = time.monotonic()
            logger.info(f"MCP tools available: {[t['name'] for t in self._tools]}")
        else:
            logger.error("MCP tools/list failed — no response from server")

    async def disconnect(self) -> None:
        """Close the HTTP client — at shutdown only, a reconnection must not cut calls in flight."""
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None
            self._session_id = None

    async def ensure_connected(self) -> None:
        """Reconnect to MCP server if tools are not loaded or session is stale."""
        if self._is_fresh():
            return
        async with self._reconnect_lock:
            if self._is_fresh():  # another task reconnected while this one waited
                return
            if self._tools:
                elapsed = time.monotonic() - self._last_activity
                logger.info(f"MCP session stale ({elapsed:.0f}s idle), proactively reconnecting...")
            else:
                logger.info("MCP tools not loaded, reconnecting...")
            await self.connect()

    def _is_fresh(self) -> bool:
        return bool(self._tools) and time.monotonic() - self._last_activity < SESSION_STALENESS_SECONDS

    async def list_tools(self) -> list[dict]:
        """Return tool definitions, reconnecting if needed."""
        await self.ensure_connected()
        return self._tools

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        """Call an MCP tool and return the result as a string.

        The MCP server acts for `user_id`; without it, tools touching user data
        refuse the call.
        """
        stale_session = self._session_id
        response = await self._send_request(
            "tools/call",
            {
                "name": name,
                "arguments": arguments,
            },
            user_id=user_id,
            keep_error=True,
        )

        # Retry once on session expiration
        if response is None and self._session_expired:
            logger.info("MCP session expired, reconnecting...")
            async with self._reconnect_lock:
                if self._session_id == stale_session:  # not already renewed by another task
                    await self.connect()
            response = await self._send_request(
                "tools/call",
                {
                    "name": name,
                    "arguments": arguments,
                },
                user_id=user_id,
                keep_error=True,
            )

        if response and "jsonrpc_error" in response:
            error = response["jsonrpc_error"]
            if error.get("code") == INVALID_PARAMS_CODE:
                return invalid_input(name, _invalid_fields(error), self._input_schema(name))
            capture_signal(MCP_TOOL_ERROR, tool=name, user_id=user_id, reason="jsonrpc_error")
            return json.dumps({"error": error.get("message") or NO_RESPONSE_ERROR}, ensure_ascii=False)

        if response is None or response.get("isError"):
            # A transport failure, a JSON-RPC error or a tool that raised — not the 400s a tool
            # answers on purpose with the current state, which carry no `isError`.
            capture_signal(
                MCP_TOOL_ERROR,
                tool=name,
                user_id=user_id,
                reason="no_response" if response is None else "is_error",
            )

        if response and "content" in response:
            # Extract text content from MCP response
            for content_block in response["content"]:
                if content_block.get("type") == "text":
                    return content_block["text"]

        return json.dumps(response or {"error": NO_RESPONSE_ERROR})

    def _input_schema(self, name: str) -> dict | None:
        for tool in self._tools:
            if tool.get("name") == name:
                return tool.get("inputSchema")
        return None

    async def _send_request(
        self, method: str, params: dict, user_id: str | None = None, keep_error: bool = False
    ) -> dict | None:
        """Send a JSON-RPC request to the MCP server.

        `keep_error`: a JSON-RPC error comes back as `{"jsonrpc_error": …}` instead of `None`."""
        if not self._http_client:
            logger.error("MCP client not connected")
            return None

        payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": params,
        }

        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if settings.service_token:
            headers["Authorization"] = f"Bearer {settings.service_token}"
        if user_id:
            headers[USER_HEADER] = user_id
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        # The e2e journey this call works for, so the API records its lines under it (nightly only).
        headers.update(journey_headers())

        try:
            response = await self._post(payload, headers)

            # Capture session ID from response
            if "mcp-session-id" in response.headers:
                self._session_id = response.headers["mcp-session-id"]

            self._session_expired = False

            if response.status_code == 200:
                self._last_activity = time.monotonic()
                data = response.json()
                if keep_error and isinstance(data.get("error"), dict):
                    return {"jsonrpc_error": data["error"]}
                return data.get("result")
            elif response.status_code == 404:
                self._session_expired = True
                logger.error(f"MCP session expired ({response.status_code}): {response.text}")
                return None
            else:
                logger.error(f"MCP request failed ({response.status_code}): {response.text}")
                return None
        except Exception as e:
            logger.error(f"MCP request error: {e}")
            return None

    async def _post(self, payload: dict, headers: dict) -> httpx.Response:
        """POST to the server, trying again only when the connection never opened.

        A failure after the request left (read timeout, dropped response) is not retried:
        the server may have acted, and a tool that writes would write twice.
        """
        assert self._http_client is not None
        for attempt in range(1, CONNECT_ATTEMPTS):
            try:
                return await self._http_client.post(self.server_url, json=payload, headers=headers)
            except (httpx.ConnectError, httpx.ConnectTimeout) as e:
                logger.warning(f"MCP connection failed ({e!r}), retrying ({attempt}/{CONNECT_ATTEMPTS - 1})")
                await asyncio.sleep(CONNECT_RETRY_DELAY_SECONDS)
        return await self._http_client.post(self.server_url, json=payload, headers=headers)

    async def _send_notification(self, method: str, params: dict) -> None:
        """Send a JSON-RPC notification (no response expected)."""
        if not self._http_client:
            return

        payload = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        }

        headers = {"Content-Type": "application/json"}
        if settings.service_token:
            headers["Authorization"] = f"Bearer {settings.service_token}"
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        headers.update(journey_headers())

        try:
            await self._http_client.post(self.server_url, json=payload, headers=headers)
        except Exception as e:
            logger.warning(f"MCP notification failed: {e}")


# Singleton instance
mcp_client = McpClient()

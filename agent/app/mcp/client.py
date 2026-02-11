import json
import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class McpClient:
    """MCP client using Streamable HTTP transport to communicate with the Symfony MCP server."""

    def __init__(self):
        self.server_url = settings.mcp_server_url
        self._http_client: httpx.AsyncClient | None = None
        self._session_id: str | None = None
        self._tools: list[dict] = []

    async def connect(self) -> None:
        """Initialize connection to MCP server."""
        self._http_client = httpx.AsyncClient(timeout=30.0)

        # Initialize MCP session
        response = await self._send_request("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "maggie-agent-hub", "version": "0.1.0"},
        })

        if response:
            logger.info(f"MCP server: {response.get('serverInfo', {})}")

            # Send initialized notification
            await self._send_notification("notifications/initialized", {})

            # List available tools
            tools_response = await self._send_request("tools/list", {})
            if tools_response:
                self._tools = tools_response.get("tools", [])
                logger.info(f"MCP tools available: {[t['name'] for t in self._tools]}")

    async def disconnect(self) -> None:
        """Close the HTTP client."""
        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None

    async def list_tools(self) -> list[dict]:
        """Return cached tool definitions."""
        return self._tools

    async def call_tool(self, name: str, arguments: dict) -> str:
        """Call an MCP tool and return the result as a string."""
        response = await self._send_request("tools/call", {
            "name": name,
            "arguments": arguments,
        })

        if response and "content" in response:
            # Extract text content from MCP response
            for content_block in response["content"]:
                if content_block.get("type") == "text":
                    return content_block["text"]

        return json.dumps(response or {"error": "No response from MCP server"})

    async def _send_request(self, method: str, params: dict) -> dict | None:
        """Send a JSON-RPC request to the MCP server."""
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
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id

        try:
            response = await self._http_client.post(self.server_url, json=payload, headers=headers)

            # Capture session ID from response
            if "mcp-session-id" in response.headers:
                self._session_id = response.headers["mcp-session-id"]

            if response.status_code == 200:
                data = response.json()
                return data.get("result")
            else:
                logger.error(f"MCP request failed ({response.status_code}): {response.text}")
                return None
        except Exception as e:
            logger.error(f"MCP request error: {e}")
            return None

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
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id

        try:
            await self._http_client.post(self.server_url, json=payload, headers=headers)
        except Exception as e:
            logger.warning(f"MCP notification failed: {e}")


# Singleton instance
mcp_client = McpClient()

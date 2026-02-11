import logging

from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)


class ToolRouter:
    """Converts MCP tool definitions to Anthropic format and routes tool calls."""

    async def get_tool_definitions(self) -> list[dict]:
        """Get available tools from MCP server in Anthropic tool format."""
        mcp_tools = await mcp_client.list_tools()

        anthropic_tools = []
        for tool in mcp_tools:
            anthropic_tools.append({
                "name": tool["name"],
                "description": tool.get("description", ""),
                "input_schema": tool.get("inputSchema", {"type": "object", "properties": {}}),
            })

        return anthropic_tools

    async def call_tool(self, name: str, arguments: dict) -> str:
        """Route a tool call to the MCP server and return the result."""
        try:
            result = await mcp_client.call_tool(name, arguments)
            return result
        except Exception as e:
            logger.error(f"Tool call failed: {name}({arguments}): {e}")
            return f'{{"error": "Tool call failed: {e}"}}'

from unittest.mock import AsyncMock, patch

from app.llm.tools import ToolRouter


def _mcp(name: str) -> dict:
    return {"name": name, "description": f"API's {name}", "inputSchema": {"type": "object", "properties": {}}}


class TestToolDefinitions:
    """No tool is offered twice, and the executor's tools never reach a conversation (MAG-326)."""

    async def test_an_mcp_tool_named_like_one_of_the_agent_s_own_is_not_offered(self):
        router = ToolRouter()
        with patch("app.llm.tools.mcp_client") as mcp:
            mcp.list_tools = AsyncMock(return_value=[_mcp("memory_search"), _mcp("cancel_proaction")])
            tools = await router.get_tool_definitions()

        names = [tool["name"] for tool in tools]
        assert len(names) == len(set(names))
        assert "cancel_proaction" in names
        memory = next(tool for tool in tools if tool["name"] == "memory_search")
        assert memory["description"] != "API's memory_search"

    async def test_the_proaction_tools_offered_are_the_api_s_once_each(self):
        # MAG-327: the agent no longer serves schedule_proaction nor list_proactions itself.
        router = ToolRouter()
        with patch("app.llm.tools.mcp_client") as mcp:
            mcp.list_tools = AsyncMock(return_value=[_mcp("schedule_proaction"), _mcp("list_proactions")])
            tools = await router.get_tool_definitions()

        proactions = [tool for tool in tools if tool["name"] in {"schedule_proaction", "list_proactions"}]
        assert [tool["description"] for tool in proactions] == [
            "API's schedule_proaction",
            "API's list_proactions",
        ]

    async def test_the_executor_s_tools_are_never_offered(self):
        router = ToolRouter()
        with patch("app.llm.tools.mcp_client") as mcp:
            mcp.list_tools = AsyncMock(
                return_value=[_mcp("claim_due_proactions"), _mcp("record_proaction_result"), _mcp("get_event")]
            )
            names = {tool["name"] for tool in await router.get_tool_definitions()}

        assert "get_event" in names
        assert not names & {"claim_due_proactions", "record_proaction_result"}

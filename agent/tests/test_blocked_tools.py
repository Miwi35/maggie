import json
from unittest.mock import AsyncMock, MagicMock

from app.llm.blocked_tools import BLOCKED_RESULT, BlockedToolsRouter


def _router() -> MagicMock:
    router = MagicMock()
    router.get_tool_definitions = AsyncMock(return_value=[{"name": "get_weather"}, {"name": "send_voice_message"}])
    router.call_tool = AsyncMock(return_value="ok")
    return router


class TestBlockedToolsRouter:
    async def test_a_blocked_tool_is_hidden_from_the_model(self):
        blocked = BlockedToolsRouter(_router(), frozenset({"send_voice_message"}))

        assert [d["name"] for d in await blocked.get_tool_definitions()] == ["get_weather"]

    async def test_a_blocked_tool_called_anyway_is_refused_and_never_runs(self):
        router = _router()
        blocked = BlockedToolsRouter(router, frozenset({"send_voice_message"}))

        result = await blocked.call_tool("send_voice_message", {"text": "x"}, user_id="u")

        assert json.loads(result) == BLOCKED_RESULT
        router.call_tool.assert_not_awaited()

    async def test_another_tool_goes_through(self):
        router = _router()
        blocked = BlockedToolsRouter(router, frozenset({"send_voice_message"}))

        assert await blocked.call_tool("get_weather", {}, user_id="u") == "ok"

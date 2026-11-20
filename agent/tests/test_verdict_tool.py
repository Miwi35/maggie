"""MAG-886: `proaction_verdict` is offered by its own router and answered there, never by the API."""

import json
from unittest.mock import AsyncMock, MagicMock

from app.proactions.verdict import NOTED, PROACTION_VERDICT_TOOL, VerdictToolRouter


def _router() -> MagicMock:
    router = MagicMock()
    router.get_tool_definitions = AsyncMock(return_value=[{"name": "get_envelopes"}])
    router.call_tool = AsyncMock(return_value="ok")
    return router


async def test_the_definition_is_added_to_the_run_tools():
    names = [d["name"] for d in await VerdictToolRouter(_router()).get_tool_definitions()]

    assert names == ["get_envelopes", "proaction_verdict"]
    assert PROACTION_VERDICT_TOOL["input_schema"]["required"] == ["impact", "evidence", "topic"]


async def test_the_verdict_is_answered_locally_and_never_reaches_the_inner_router():
    router = _router()

    result = await VerdictToolRouter(router).call_tool(
        "proaction_verdict", {"impact": "low", "evidence": "solid", "topic": "a"}, user_id="u"
    )

    assert json.loads(result) == NOTED
    router.call_tool.assert_not_awaited()


async def test_another_tool_goes_through():
    router = _router()

    assert await VerdictToolRouter(router).call_tool("get_envelopes", {}, user_id="u") == "ok"
    router.call_tool.assert_awaited_once()

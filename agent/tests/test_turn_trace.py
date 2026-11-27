"""The trace of a turn (MAG-995): every streamed chat turn is kept in the journal with its calls, timed."""

import asyncio
import logging
from contextlib import ExitStack
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.config import settings
from app.db.journal_repository import journal_repo
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeToolUseBlock, FakeUsage
from app.redaction import MASK
from app.runtime.trace import TracingToolRouter
from tests.test_streaming import TestSummaryTrigger

RELEASE = "0123456789abcdef0123456789abcdef01234567"
SECRET = "s3cr3t-service-token-value"


def _two_calls_gateway(result: str = '{"items": ["Farine"]}'):
    """A turn of three steps: two tool calls, one per round, then the answer."""
    steps = [
        FakeMessage(
            content=[FakeToolUseBlock(id="toolu_1", name="get_grocery_list", input={"includeDeferred": False})],
            stop_reason="tool_use",
            usage=FakeUsage(input_tokens=10, output_tokens=5),
        ),
        FakeMessage(
            content=[FakeToolUseBlock(id="toolu_2", name="get_events", input={"date": "2026-11-24"})],
            stop_reason="tool_use",
            usage=FakeUsage(input_tokens=10, output_tokens=5),
        ),
        FakeMessage(
            content=[FakeTextBlock(text="De la farine, et rien à l'agenda.")],
            stop_reason="end_turn",
            usage=FakeUsage(input_tokens=10, output_tokens=5),
        ),
    ]
    gw = TestSummaryTrigger._gateway()
    gw.client.messages.stream = lambda **_kwargs: FakeStream(steps.pop(0))
    gw.tool_router.get_tool_definitions = AsyncMock(return_value=[{"name": "get_grocery_list"}, {"name": "get_events"}])
    gw.tool_router.call_tool = AsyncMock(return_value=result)
    return gw


async def _turn(gw, message: str = "Ma liste ?") -> list[dict]:
    with ExitStack() as stack:
        contexts = stack.enter_context(patch("app.llm.contexts.context_repo"))
        thread = stack.enter_context(patch("app.llm.streaming.context_repo"))
        messages = stack.enter_context(patch("app.llm.streaming.message_repo"))
        stack.enter_context(patch("app.llm.streaming.message_service", messages))
        stack.enter_context(
            patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": message}]))
        )
        skills = stack.enter_context(patch("app.prompt_sources.skill_index"))
        summarizer = stack.enter_context(patch("app.llm.streaming.context_summarizer"))
        contexts.find_active = AsyncMock(return_value=[])
        thread.append_tool_call = AsyncMock(return_value=None)
        messages.create = AsyncMock()
        messages.announce = AsyncMock()
        messages.checkpoint_turn = AsyncMock()
        skills.get_skills_index.return_value = ""
        skills.skills_for_moment.return_value = ""
        skills.refresh = AsyncMock()
        summarizer.maybe_summarize = AsyncMock(return_value=None)

        events = [event async for event in gw.chat_stream(message, "user-1", "01JMSGOWNER000000000000000")]
        await asyncio.gather(*gw._background, *gw._traces)
        return events


async def _chat_runs() -> list:
    return await journal_repo.find_by_user("user-1", datetime.now(UTC) - timedelta(days=1), kinds=("chat",))


class TestTheRouterEnvelope:
    async def test_the_router_envelope_times_each_call_in_order(self):
        router = MagicMock()
        router.call_tool = AsyncMock(side_effect=["un", "deux"])
        tracer = TracingToolRouter(router)

        assert await tracer.call_tool("get_events", {"date": "2026-11-24"}, user_id="user-1", origin={"kind": "x"}) == "un"
        assert await tracer.call_tool("get_weather", {}, user_id="user-1") == "deux"

        assert [(c["name"], c["input"], c["result"]) for c in tracer.calls] == [
            ("get_events", {"date": "2026-11-24"}, "un"),
            ("get_weather", {}, "deux"),
        ]
        assert all(isinstance(c["durationMs"], int) and c["durationMs"] >= 0 for c in tracer.calls)
        router.call_tool.assert_any_await("get_events", {"date": "2026-11-24"}, user_id="user-1", origin={"kind": "x"})

    async def test_the_router_envelope_keeps_a_call_that_raised(self):
        router = MagicMock()
        router.call_tool = AsyncMock(side_effect=RuntimeError("MCP down"))
        tracer = TracingToolRouter(router)

        with pytest.raises(RuntimeError, match="MCP down"):
            await tracer.call_tool("get_events", {})

        [call] = tracer.calls
        assert (call["name"], call["result"]) == ("get_events", "MCP down")
        assert call["durationMs"] >= 0


class TestAChatTurnIsKept:
    async def test_a_chat_turn_is_kept_with_its_calls_its_message_and_its_release(self, journal_db, monkeypatch):
        monkeypatch.setattr(settings, "sentry_release", RELEASE)

        await _turn(_two_calls_gateway())

        [run] = await _chat_runs()
        entry = run.to_dict()
        assert (entry["kind"], entry["status"]) == ("chat", "completed")
        assert entry["messageId"] == "01JMSGOWNER000000000000000"
        assert entry["release"] == RELEASE
        assert [call["name"] for call in entry["toolCalls"]] == ["get_grocery_list", "get_events"]
        assert entry["toolCalls"][1]["input"] == {"date": "2026-11-24"}
        assert entry["toolCalls"][0]["result"] == '{"items": ["Farine"]}'
        assert all(call["durationMs"] >= 0 for call in entry["toolCalls"])
        assert entry["response"] == "De la farine, et rien à l'agenda."
        assert (entry["inputTokens"], entry["outputTokens"]) == (30, 15)
        assert entry["model"]
        assert entry["durationMs"] >= 0
        assert entry["day"] == datetime.now(ZoneInfo("Europe/Paris")).date().isoformat()

    async def test_a_failed_chat_turn_is_kept_as_failed(self, journal_db):
        gw = TestSummaryTrigger._gateway()
        with patch("app.llm.streaming.client_for", AsyncMock(side_effect=RuntimeError("db blip"))):
            events = await _turn(gw)

        assert events[-1]["type"] == "RUN_FINISHED"
        [run] = await _chat_runs()
        assert (run.status, run.error) == ("failed", "db blip")

    async def test_a_known_secret_is_kept_masked(self, journal_db, monkeypatch):
        monkeypatch.setattr(settings, "service_token", SECRET)
        gw = _two_calls_gateway(result=f'{{"token": "{SECRET}"}}')

        await _turn(gw, message=f"Retiens {SECRET}")

        [run] = await _chat_runs()
        assert SECRET not in str(run.tool_calls)
        assert MASK in run.tool_calls[0]["result"]

    async def test_a_secret_in_an_argument_is_kept_masked(self, journal_db, monkeypatch):
        monkeypatch.setattr(settings, "service_token", SECRET)
        await journal_repo.record_chat(
            "user-1",
            "01JMSGOWNER000000000000000",
            "2026-11-24",
            datetime.now(UTC),
            12,
            calls=[{"name": "remember", "input": {"note": f"code {SECRET}"}, "result": "ok", "durationMs": 3}],
            response="C'est noté.",
            usage={},
            model="claude",
        )

        [run] = await _chat_runs()
        assert run.tool_calls[0]["input"] == {"note": f"code {MASK}"}

    async def test_a_journal_that_cannot_be_written_never_breaks_the_turn(self, caplog):
        caplog.set_level(logging.WARNING, logger="app.llm.streaming")
        with patch.object(journal_repo, "record_chat", AsyncMock(side_effect=RuntimeError("journal down"))):
            events = await _turn(_two_calls_gateway())

        assert events[-1]["type"] == "RUN_FINISHED"
        text = "".join(e.get("delta", "") for e in events if e["type"] == "TEXT_MESSAGE_CONTENT")
        assert text == "De la farine, et rien à l'agenda."
        assert "journal down" in caplog.text

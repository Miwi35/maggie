"""Error tracking (GlitchTip): nothing without a DSN; tagged signals with one.

The SDK runs for real here, on a transport that keeps the envelopes instead of sending
them, so what is asserted is what GlitchTip would receive.
"""

import json
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
import sentry_sdk
from sqlalchemy import select
from sentry_sdk.transport import Transport

from app.config import Settings
from app.db.message_repository import message_repo
from app.db.models import Message
from app.error_tracking import capture_signal, init_error_tracking
from app.llm.claim_guard import ClaimGuard, Verdict
from app.llm.turns import TurnRunner
from app.mcp.client import McpClient
from app.queue import proaction_consumer

DSN = "https://public@glitchtip.example.com/1"
# What the user said: it must never reach an event.
SECRET_TEXT = "rappelle-moi de prendre mon traitement"


class RecordingTransport(Transport):
    def __init__(self, options=None):
        super().__init__(options)
        self.events: list[dict] = []

    def capture_envelope(self, envelope):
        for item in envelope.items:
            if item.type == "event":
                self.events.append(json.loads(item.payload.get_bytes()))


@pytest.fixture()
def sent():
    transport = RecordingTransport()
    assert init_error_tracking(Settings(sentry_dsn=DSN, sentry_release="abc1234"), transport=transport)
    yield transport.events
    sentry_sdk.get_client().close()
    sentry_sdk.get_global_scope().set_client(None)


def only_event(events: list[dict]) -> dict:
    sentry_sdk.flush()
    assert len(events) == 1, events
    return events[0]


def assert_signal(event: dict, signal: str) -> None:
    assert event["level"] == "warning"
    assert event["tags"]["signal"] == signal
    assert event["tags"]["component"] == "agent"
    assert event["release"] == "abc1234"
    assert event["environment"] == "prod"
    assert "exception" not in event
    assert SECRET_TEXT not in json.dumps(event)


class TestWithoutDsn:
    def test_nothing_is_initialised_and_nothing_is_sent(self):
        transport = RecordingTransport()

        assert init_error_tracking(Settings(sentry_dsn=""), transport=transport) is False
        assert not sentry_sdk.get_client().is_active()

        capture_signal("claim_guard_retry", user_id="user-1")
        sentry_sdk.capture_message("anything")
        assert transport.events == []


class TestTheSignals:
    def test_an_unhandled_error_carries_the_component(self, sent):
        try:
            raise RuntimeError("boom")
        except RuntimeError as exc:
            sentry_sdk.capture_exception(exc)

        event = only_event(sent)
        assert event["tags"]["component"] == "agent"
        assert event["level"] == "error"

    def test_a_query_string_never_leaves(self, sent):
        with sentry_sdk.new_scope() as scope:
            scope.add_event_processor(
                lambda event, _hint: {**event, "request": {"url": "/agent/messages", "query_string": f"q={SECRET_TEXT}"}}
            )
            sentry_sdk.capture_message("boom")

        event = only_event(sent)
        assert "query_string" not in event["request"]
        assert SECRET_TEXT not in json.dumps(event)

    def test_a_relaunch_of_the_claim_guard(self, sent):
        guard = ClaimGuard([{"name": "schedule_proaction"}])

        verdict = guard.review("C'est noté, je te le rappellerai dans 10 minutes.")

        assert verdict is Verdict.RETRY
        event = only_event(sent)
        assert_signal(event, "claim_guard_retry")
        assert event["tags"]["claim"] == "reminder"

    @pytest.mark.asyncio
    async def test_an_mcp_tool_that_answers_an_error(self, sent):
        client = McpClient()
        client._send_request = AsyncMock(return_value={"isError": True, "content": [{"type": "text", "text": "x"}]})

        await client.call_tool("create_event", {"title": SECRET_TEXT}, user_id="user-1")

        event = only_event(sent)
        assert_signal(event, "mcp_tool_error")
        assert event["tags"]["tool"] == "create_event"
        assert event["tags"]["user_id"] == "user-1"
        assert event["tags"]["reason"] == "is_error"

    @pytest.mark.asyncio
    async def test_an_mcp_tool_that_answers_on_purpose_is_not_a_signal(self, sent):
        client = McpClient()
        client._send_request = AsyncMock(return_value={"content": [{"type": "text", "text": '{"error": "x"}'}]})

        await client.call_tool("create_event", {}, user_id="user-1")

        sentry_sdk.flush()
        assert sent == []

    @pytest.mark.asyncio
    async def test_a_failed_proaction(self, sent):
        proaction = type("P", (), {"id": "pro-1", "user_id": "user-1", "prompt": SECRET_TEXT})()
        gateway = AsyncMock()
        gateway.proaction.side_effect = ValueError(SECRET_TEXT)

        with patch.object(proaction_consumer, "proaction_repo", AsyncMock()):
            result = await proaction_consumer.execute_proaction(gateway, proaction)

        assert result["status"] == "failed"
        event = only_event(sent)
        assert_signal(event, "proaction_failed")
        assert event["tags"]["proaction_id"] == "pro-1"
        assert event["tags"]["error"] == "ValueError"

    @pytest.mark.asyncio
    async def test_a_message_left_without_answer(self, sent, chat_db):
        now = datetime.now(UTC)
        stale = await message_repo.create(
            user_id="user-1", role="user", content=SECRET_TEXT, turn_lease_until=now - timedelta(hours=2)
        )
        async with chat_db.session() as session:
            stored = (await session.execute(select(Message).where(Message.id == stale.id))).scalar_one()
            stored.created_at = now - timedelta(hours=2)
            await session.commit()

        await TurnRunner().resume_lost_turns(AsyncMock())

        event = only_event(sent)
        assert_signal(event, "unanswered_message")
        assert event["tags"]["message_id"] == stale.id
        assert event["tags"]["user_id"] == "user-1"

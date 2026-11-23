"""The loop retries a step the provider failed in a way that clears by itself, visibly (MAG-971)."""

from unittest.mock import AsyncMock, patch

import anthropic
import httpx
import pytest

from app.config import settings
from app.llm.provider_status import retry_delay, retryable
from app.llm.runner import run_tool_loop
from app.llm.streaming import API_ERROR_MESSAGE
from app.runtime.loop import LoopOptions, ModelFailed, ModelRetrying, StepEnded, StepStarted, TurnEnded, run_loop
from tests.test_loop_unchanged import TOOLS, ScriptedClient, ScriptedRouter, _ends, _text, _use, _uses, stream_turn
from tests.test_provider_unavailable import CREDIT_MESSAGE, _credit_error, _status_error

REQUEST = httpx.Request("POST", "https://api.anthropic.com/v1/messages")


def _overloaded(retry_after: str | None = "3") -> anthropic.APIStatusError:
    body = {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}
    headers = {"retry-after": retry_after} if retry_after is not None else {}
    response = httpx.Response(529, headers=headers, request=REQUEST, json=body)
    return anthropic.APIStatusError("Overloaded", response=response, body=body)


@pytest.fixture
def sleeps():
    with patch("app.runtime.loop.asyncio.sleep", AsyncMock()) as sleep:
        yield sleep


@pytest.fixture
def provider():
    with (
        patch("app.runtime.loop.note_provider_failure", AsyncMock()) as failure,
        patch("app.runtime.loop.note_provider_success", AsyncMock()),
    ):
        yield failure


async def _events(steps, *, streamed=False, max_iterations=5):
    make = LoopOptions.streaming if streamed else LoopOptions.blocking
    options = make(model="m", user_id="user-1", max_iterations=max_iterations)
    out = []
    try:
        async for event in run_loop(
            "system", [{"role": "user", "content": "salut"}], TOOLS,
            client=ScriptedClient(steps), tool_router=ScriptedRouter({"get_events": ["{}"]}), options=options,
        ):
            out.append(event)
    except anthropic.APIStatusError as error:
        out.append(error)
    return out


async def test_a_529_then_an_answer_streams_a_retrying_event_then_the_answer(sleeps, provider):
    turn = await stream_turn([_overloaded("3"), _ends(_text("Bonjour."))], {}, {})

    names = [event.get("name") or event["type"] for event in turn["events"]]
    retrying = next(event for event in turn["events"] if event.get("name") == "retrying")
    assert retrying["name"] == "retrying"
    assert retrying["value"] == {"attempt": 1, "attempts": 2, "delaySeconds": 3.0}
    assert names.index("retrying") < names.index("TEXT_MESSAGE_START")
    assert turn["created"][-1]["content"] == "Bonjour."
    sleeps.assert_awaited_once_with(3.0)


async def test_a_blocking_run_retries_a_529_and_returns_the_answer(sleeps, provider):
    client = ScriptedClient([_overloaded(), _ends(_text("Bonjour."))])

    result = await run_tool_loop("s", [{"role": "user", "content": "x"}], TOOLS, client=client, tool_router=None,
                                 user_id="u", model="m")

    assert result["response"] == "Bonjour."
    assert len(client.requests) == 2


async def test_a_blocking_loop_emits_one_retrying_event(sleeps, provider):
    events = await _events([_overloaded(), _ends(_text("Bonjour."))])

    assert [e for e in events if isinstance(e, ModelRetrying)] == [ModelRetrying(1, 2, 3.0, "overloaded")]


async def test_three_retryable_failures_end_on_the_error_path_of_today(sleeps, provider):
    streamed = await _events([_overloaded(), _overloaded(), _overloaded()], streamed=True)
    assert [type(e) for e in streamed if isinstance(e, (ModelRetrying, ModelFailed))] == [
        ModelRetrying, ModelRetrying, ModelFailed,
    ]
    assert streamed[-1].outcome.failed

    blocking = await _events([_overloaded(), _overloaded(), _overloaded()])
    assert sum(isinstance(e, ModelRetrying) for e in blocking) == 2
    assert isinstance(blocking[-1], anthropic.APIStatusError)


async def test_the_provider_is_marked_failing_only_after_the_last_attempt(sleeps, provider):
    await _events([_credit_error()], streamed=True)
    provider.assert_awaited_once()
    provider.reset_mock()

    await _events([_overloaded(), _ends(_text("Bonjour."))], streamed=True)
    provider.assert_not_awaited()

    await _events([_overloaded(), _overloaded(), _overloaded()], streamed=True)
    provider.assert_awaited_once()


async def test_a_non_retryable_error_is_not_retried_and_still_alerts_the_owner(sleeps, provider):
    with patch("app.llm.streaming.alert_owner", AsyncMock()) as alert:
        turn = await stream_turn([_credit_error()], {}, {})

    sleeps.assert_not_awaited()
    assert not any(event.get("name") == "retrying" for event in turn["events"])
    alert.assert_awaited_once()
    assert API_ERROR_MESSAGE in str(turn["events"])
    for status in (400, 401):
        events = await _events([_status_error(status, "nope")])
        assert not any(isinstance(e, ModelRetrying) for e in events)


async def test_a_step_that_already_streamed_text_is_not_retried(sleeps, provider):
    options = LoopOptions.streaming(model="m", user_id="user-1", max_iterations=5)
    out = [e async for e in run_loop("s", [{"role": "user", "content": "x"}], TOOLS,
                                      client=_BrokenAfterText(), tool_router=ScriptedRouter({}), options=options)]

    assert not any(isinstance(e, ModelRetrying) for e in out)
    assert any(isinstance(e, ModelFailed) for e in out)


class _BrokenAfterText:
    def __init__(self):
        from types import SimpleNamespace

        self.messages = SimpleNamespace(stream=lambda **kwargs: self)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def __aiter__(self):
        return self._events()

    async def _events(self):
        from types import SimpleNamespace

        yield SimpleNamespace(type="content_block_delta", delta=SimpleNamespace(text="Bon"))
        raise _overloaded()


async def test_a_retry_does_not_count_as_an_iteration(sleeps, provider):
    steps = [_overloaded(), _uses(_use("a")), _ends(_text("Fini."))]

    events = await _events(steps, max_iterations=2)

    assert [e for e in events if isinstance(e, TurnEnded)][0].outcome.response == "Fini."
    assert sum(isinstance(e, StepStarted) for e in events) == 2
    assert sum(isinstance(e, StepEnded) for e in events) == 2


async def test_the_loop_calls_the_real_client_without_sdk_retries(sleeps, provider):
    real = anthropic.AsyncAnthropic(api_key="x")
    seen = []

    def spy(**kwargs):
        seen.append(kwargs)
        scripted = ScriptedClient([_ends(_text("Bonjour."))])
        return scripted

    real.with_options = spy
    options = LoopOptions.blocking(model="m", user_id="u", max_iterations=2)
    out = [e async for e in run_loop("s", [{"role": "user", "content": "x"}], TOOLS, client=real,
                                      tool_router=None, options=options)]

    assert seen == [{"max_retries": 0}]
    assert out[-1].outcome.response == "Bonjour."


async def test_other_calls_keep_the_shared_client(sleeps, provider):
    from datetime import date

    from app.memory.recall_plan import plan_recall

    real = anthropic.AsyncAnthropic(api_key="x")
    with (
        patch.object(real, "with_options") as with_options,
        patch.object(real.messages, "create", AsyncMock(side_effect=ValueError("stop"))) as create,
    ):
        await plan_recall(real, "user-1", "salut", date(2026, 11, 23))

    create.assert_awaited_once()
    with_options.assert_not_called()


def test_overload_rate_limit_server_errors_and_lost_connections_are_retryable():
    assert retryable(_overloaded())
    assert retryable(anthropic.APIConnectionError(request=REQUEST))
    assert retryable(anthropic.APITimeoutError(request=REQUEST))
    for status in (408, 409, 429, 500, 503):
        assert retryable(_status_error(status, "x"))


def test_bad_requests_credit_and_auth_are_not_retryable():
    assert not retryable(_credit_error())
    assert not retryable(_status_error(400, "bad"))
    assert not retryable(_status_error(401, "key"))
    assert not retryable(ValueError("ours"))
    assert CREDIT_MESSAGE


def test_the_delay_follows_retry_after_capped_at_30_s():
    assert retry_delay(_overloaded("3"), 1) == 3.0
    assert retry_delay(_overloaded("120"), 1) == 30.0


def test_without_retry_after_the_delay_doubles_from_the_base():
    with patch.object(settings, "llm_retry_base_seconds", 1.0):
        assert retry_delay(_overloaded(None), 1) == 1.0
        assert retry_delay(_overloaded(None), 2) == 2.0
        assert retry_delay(_overloaded("soon"), 2) == 2.0

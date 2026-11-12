"""The user's timezone is read through MCP once per window, not once per place that needs it (MAG-543)."""

import json
from unittest.mock import AsyncMock
from zoneinfo import ZoneInfo

import pytest

from app import user_timezone
from app.user_timezone import TIMEZONE_FRESH_SECONDS, resolve_user_timezone

NEW_YORK = ZoneInfo("America/New_York")
TOKYO = ZoneInfo("Asia/Tokyo")


@pytest.fixture(autouse=True)
def nothing_known():
    user_timezone.known_timezones.clear()
    yield
    user_timezone.known_timezones.clear()


@pytest.fixture
def clock(monkeypatch):
    now = [1000.0]
    monkeypatch.setattr(user_timezone.time, "monotonic", lambda: now[0])
    return now


def answering(monkeypatch, *answers: str | Exception) -> AsyncMock:
    replies = [a if isinstance(a, Exception) else json.dumps({"timezone": a}) for a in answers]
    mock = AsyncMock(side_effect=replies)
    monkeypatch.setattr(user_timezone.mcp_client, "call_tool", mock)
    return mock


class TestOneReadPerTurn:
    async def test_the_places_of_one_turn_share_a_single_mcp_call(self, monkeypatch, clock):
        mock = answering(monkeypatch, "America/New_York")

        for _ in range(4):  # the prompt, the web search, the history, the time tool
            clock[0] += 0.5
            assert await resolve_user_timezone("user-1") == NEW_YORK

        mock.assert_awaited_once_with("get_user_timezone", {}, user_id="user-1")

    async def test_each_user_is_read_for_themselves(self, monkeypatch, clock):
        mock = answering(monkeypatch, "America/New_York", "Asia/Tokyo")

        assert await resolve_user_timezone("user-1") == NEW_YORK
        assert await resolve_user_timezone("user-2") == TOKYO
        assert await resolve_user_timezone("user-1") == NEW_YORK

        assert mock.await_count == 2

    async def test_a_changed_preference_is_seen_after_the_window(self, monkeypatch, clock):
        mock = answering(monkeypatch, "America/New_York", "Asia/Tokyo")
        assert await resolve_user_timezone("user-1") == NEW_YORK

        clock[0] += TIMEZONE_FRESH_SECONDS - 1
        assert await resolve_user_timezone("user-1") == NEW_YORK
        clock[0] += 2

        assert await resolve_user_timezone("user-1") == TOKYO
        assert mock.await_count == 2

    async def test_a_failed_read_is_retried_by_the_next_call_and_keeps_the_last_timezone(self, monkeypatch, clock):
        mock = answering(monkeypatch, "America/New_York", RuntimeError("API restarting"), "Asia/Tokyo")
        assert await resolve_user_timezone("user-1") == NEW_YORK

        clock[0] += TIMEZONE_FRESH_SECONDS + 1
        assert await resolve_user_timezone("user-1") == NEW_YORK  # unreadable: the last one is kept
        assert await resolve_user_timezone("user-1") == TOKYO  # and the outage was not remembered as fresh

        assert mock.await_count == 3


class TestTheWorkingHoursComeWithIt:
    """The same answer carries the working hours (MAG-790): no second call for them."""

    async def test_they_are_read_from_the_same_call(self, monkeypatch, clock):
        answer = {"timezone": "Europe/Paris", "workStartTime": "08:30", "workEndTime": "17:00", "workDays": [1, 2]}
        mock = AsyncMock(return_value=json.dumps(answer))
        monkeypatch.setattr(user_timezone.mcp_client, "call_tool", mock)
        user_timezone.known_work_hours.clear()

        await resolve_user_timezone("user-1")
        hours = await user_timezone.resolve_work_hours("user-1")

        assert hours == {"workStartTime": "08:30", "workEndTime": "17:00", "workDays": [1, 2]}
        mock.assert_awaited_once()

    async def test_never_read_is_empty(self, monkeypatch, clock):
        answering(monkeypatch, RuntimeError("API down"))
        user_timezone.known_work_hours.clear()

        assert await user_timezone.resolve_work_hours("user-1") == {}

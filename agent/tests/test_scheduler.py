import json
from collections import defaultdict
from datetime import UTC, datetime
from unittest.mock import AsyncMock
from zoneinfo import ZoneInfo

import pytest

from app.config import settings
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.queue import scheduler
from app.queue.scheduler import next_planning_time, plan_user, planning_schedule, resolve_user_timezone


@pytest.fixture(autouse=True)
def paris_at_five(monkeypatch):
    scheduler._known_timezones.clear()
    monkeypatch.setattr(settings, "planning_timezone", "Europe/Paris")
    monkeypatch.setattr(settings, "daily_planning_hour", 5)


def utc(*args: int) -> datetime:
    return datetime(*args, tzinfo=UTC)


class TestNextPlanningTime:
    """Planning fires at 05:00 Europe/Paris, expressed in UTC."""

    def test_later_today_when_before_the_hour(self):
        assert next_planning_time(utc(2026, 9, 29, 1, 0)) == utc(2026, 9, 29, 3, 0)

    @pytest.mark.parametrize(
        ("now", "expected"),
        [
            # last day of a 30-day month: day + 1 used to raise ValueError
            (utc(2026, 9, 30, 10, 0), utc(2026, 10, 1, 3, 0)),
            # last day of the year
            (utc(2026, 12, 31, 10, 0), utc(2027, 1, 1, 4, 0)),
            # last day of February, common year and leap year
            (utc(2027, 2, 28, 10, 0), utc(2027, 3, 1, 4, 0)),
            (utc(2028, 2, 28, 10, 0), utc(2028, 2, 29, 4, 0)),
            (utc(2028, 2, 29, 10, 0), utc(2028, 3, 1, 4, 0)),
        ],
    )
    def test_rolls_over_month_and_year_ends(self, now, expected):
        assert next_planning_time(now) == expected

    def test_exactly_at_the_hour_waits_for_tomorrow(self):
        assert next_planning_time(utc(2026, 9, 29, 3, 0)) == utc(2026, 9, 30, 3, 0)

    def test_follows_daylight_saving_time(self):
        # Paris goes from UTC+1 to UTC+2 on 2027-03-28 at 01:00Z: 05:00 local is 04:00Z, then 03:00Z.
        assert next_planning_time(utc(2027, 3, 26, 10, 0)) == utc(2027, 3, 27, 4, 0)
        assert next_planning_time(utc(2027, 3, 27, 10, 0)) == utc(2027, 3, 28, 3, 0)

    def test_local_date_decides_not_utc_date(self):
        # 23:30Z on 30 Sept is already 01:30 on 1 Oct in Paris: 05:00 that day is still ahead.
        assert next_planning_time(utc(2026, 9, 30, 23, 30)) == utc(2026, 10, 1, 3, 0)


NEW_YORK = ZoneInfo("America/New_York")


def timezones(monkeypatch, by_user: dict[str, str | Exception]) -> AsyncMock:
    """Make the MCP tool answer with each user's timezone (or fail)."""

    async def call_tool(name, arguments, user_id=None):
        assert name == "get_user_timezone"
        answer = by_user[user_id]
        if isinstance(answer, Exception):
            raise answer
        return answer if answer.startswith("{") else json.dumps({"timezone": answer})

    mock = AsyncMock(side_effect=call_tool)
    monkeypatch.setattr(scheduler.mcp_client, "call_tool", mock)
    return mock


class TestPlanningInTheUsersTimezone:
    def test_new_york_is_planned_at_five_new_york_time(self):
        # 2026-10-01 is in daylight saving time: New York is UTC-4, so 05:00 local is 09:00Z.
        assert next_planning_time(utc(2026, 10, 1, 6, 0), NEW_YORK) == utc(2026, 10, 1, 9, 0)
        assert next_planning_time(utc(2026, 10, 1, 9, 0), NEW_YORK) == utc(2026, 10, 2, 9, 0)

    def test_new_york_follows_its_own_daylight_saving_time(self):
        # New York leaves daylight saving time on 2026-11-01: UTC-5 afterwards.
        assert next_planning_time(utc(2026, 11, 1, 10, 0), NEW_YORK) == utc(2026, 11, 2, 10, 0)

    async def test_reads_the_timezone_through_the_mcp_tool_for_that_user(self, monkeypatch):
        mock = timezones(monkeypatch, {"user-ny": "America/New_York"})

        assert await resolve_user_timezone("user-ny") == NEW_YORK
        mock.assert_awaited_once_with("get_user_timezone", {}, user_id="user-ny")

    @pytest.mark.parametrize(
        "answer",
        [
            "Mars/Olympus_Mons",
            "",
            '{"error": "No user is bound to this MCP call."}',
            '{"timezone": null}',
            "not json",
            RuntimeError("MCP down"),
        ],
    )
    async def test_an_unusable_timezone_falls_back_to_paris(self, monkeypatch, answer):
        timezones(monkeypatch, {"user-x": answer})

        assert await resolve_user_timezone("user-x") == ZoneInfo("Europe/Paris")

    async def test_an_unreadable_preference_keeps_the_last_timezone_read(self, monkeypatch):
        timezones(monkeypatch, {"user-ny": "America/New_York"})
        assert await resolve_user_timezone("user-ny") == NEW_YORK

        for outage in (RuntimeError("API restarting"), '{"error": "No user is bound to this MCP call."}'):
            timezones(monkeypatch, {"user-ny": outage})
            assert await resolve_user_timezone("user-ny") == NEW_YORK

    async def test_an_invalid_timezone_replaces_the_last_one_read(self, monkeypatch):
        timezones(monkeypatch, {"user-x": "America/New_York"})
        await resolve_user_timezone("user-x")

        timezones(monkeypatch, {"user-x": "Nowhere/Land"})

        assert await resolve_user_timezone("user-x") == ZoneInfo("Europe/Paris")

    async def test_each_user_is_scheduled_on_their_own_wall_clock(self, agent_db, monkeypatch):
        await instruction_repo.store("user-ny", "Digest du matin à 7h")
        await instruction_repo.store("user-paris", "Digest du matin à 7h")
        timezones(monkeypatch, {"user-ny": "America/New_York", "user-paris": "Europe/Paris"})

        schedule = await planning_schedule(utc(2026, 10, 1, 1, 0))

        assert schedule == {"user-ny": utc(2026, 10, 1, 9, 0), "user-paris": utc(2026, 10, 1, 3, 0)}

    async def test_an_invalid_timezone_does_not_stop_the_others(self, agent_db, monkeypatch):
        await instruction_repo.store("user-bad", "Rule 1")
        await instruction_repo.store("user-ny", "Rule 2")
        timezones(monkeypatch, {"user-bad": "Nowhere/Land", "user-ny": "America/New_York"})

        schedule = await planning_schedule(utc(2026, 10, 1, 1, 0))

        assert schedule == {"user-bad": utc(2026, 10, 1, 3, 0), "user-ny": utc(2026, 10, 1, 9, 0)}


class TestWhoIsPlanned:
    """The users scheduled are those with a planning directive, once each, whatever else they stored."""

    @pytest.fixture(autouse=True)
    def everyone_in_paris(self, monkeypatch):
        timezones(monkeypatch, defaultdict(lambda: "Europe/Paris"))

    async def test_every_user_with_a_planning_directive_once(self, agent_db):
        await instruction_repo.store("user-a", "Rule 1")
        await instruction_repo.store("user-a", "Rule 2")
        await instruction_repo.store("user-b", "Rule 3")

        assert sorted(await planning_schedule(utc(2026, 10, 1, 1, 0))) == ["user-a", "user-b"]

    async def test_no_instructions_means_no_planning(self, agent_db):
        assert await planning_schedule(utc(2026, 10, 1, 1, 0)) == {}

    async def test_a_behaviour_preference_is_not_a_reason_to_plan(self, agent_db):
        """« Tutoie-moi » says nothing about when to act, so it must not wake the planner (MAG-22)."""
        await instruction_repo.store("talker", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

        assert await planning_schedule(utc(2026, 10, 1, 1, 0)) == {}

    async def test_a_user_with_both_is_still_planned_once(self, agent_db):
        await instruction_repo.store("user-42", "Digest du matin à 7h", kind=InstructionKind.PLANNING)
        await instruction_repo.store("user-42", "Tutoie-moi", kind=InstructionKind.BEHAVIOR)

        assert list(await planning_schedule(utc(2026, 10, 1, 1, 0))) == ["user-42"]

    async def test_plans_with_the_real_user_id(self):
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}

        await plan_user(gateway, "user-42")

        gateway.proaction.assert_awaited_once()
        assert gateway.proaction.await_args.args[1] == "user-42"
        assert gateway.proaction.await_args.kwargs == {"silent": True}

    async def test_one_failing_user_does_not_stop_the_others(self, agent_db, monkeypatch):
        await instruction_repo.store("user-a", "Rule 1")
        await instruction_repo.store("user-b", "Rule 2")
        gateway = AsyncMock()
        gateway.proaction.side_effect = [RuntimeError("boom"), {"response": "ok"}]
        monkeypatch.setattr(scheduler, "_utcnow", iter([utc(2026, 10, 1, 1, 0), utc(2026, 10, 1, 3, 0)]).__next__)
        monkeypatch.setattr(scheduler.asyncio, "sleep", AsyncMock())

        await scheduler.run_planning_cycle(gateway, utc(2026, 10, 1, 1, 0))

        assert gateway.proaction.await_count == 2


class TestPlanningCycle:
    @pytest.fixture
    def clock(self, monkeypatch):
        """Freeze `_utcnow` on a list of instants and record how long the cycle sleeps."""
        instants: list[datetime] = []
        sleeps: list[float] = []

        async def sleep(seconds):
            sleeps.append(seconds)

        monkeypatch.setattr(scheduler, "_utcnow", lambda: instants.pop(0))
        monkeypatch.setattr(scheduler.asyncio, "sleep", sleep)
        return instants, sleeps

    async def test_plans_new_york_at_nine_utc_and_leaves_paris_for_its_own_hour(self, agent_db, monkeypatch, clock):
        instants, sleeps = clock
        await instruction_repo.store("user-ny", "Rule 1")
        await instruction_repo.store("user-paris", "Rule 2")
        timezones(monkeypatch, {"user-ny": "America/New_York", "user-paris": "Europe/Paris"})
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}
        # 08:59:30Z: New York is 30 s away. Paris was planned at 03:00Z, its next moment is tomorrow.
        instants += [utc(2026, 10, 1, 8, 59, 30), utc(2026, 10, 1, 9, 0, 0)]

        checked_until = await scheduler.run_planning_cycle(gateway, utc(2026, 10, 1, 4, 0))

        assert sleeps == [30.0]
        assert [call.args[1] for call in gateway.proaction.await_args_list] == ["user-ny"]
        assert checked_until == utc(2026, 10, 1, 9, 0)

    async def test_nobody_due_means_a_refresh_sleep_and_no_planning(self, agent_db, monkeypatch, clock):
        instants, sleeps = clock
        await instruction_repo.store("user-ny", "Rule 1")
        timezones(monkeypatch, {"user-ny": "America/New_York"})
        gateway = AsyncMock()
        instants += [utc(2026, 10, 1, 10, 0), utc(2026, 10, 1, 10, 15)]

        await scheduler.run_planning_cycle(gateway, utc(2026, 10, 1, 10, 0))

        assert sleeps == [scheduler.PLANNING_REFRESH]
        gateway.proaction.assert_not_awaited()

    async def test_a_moment_reached_while_the_model_was_busy_is_still_planned(self, agent_db, monkeypatch, clock):
        instants, sleeps = clock
        await instruction_repo.store("user-ny", "Rule 1")
        timezones(monkeypatch, {"user-ny": "America/New_York"})
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}
        # The previous cycle checked up to 08:00Z, then planned someone else for a long while: it is 09:30Z now.
        instants += [utc(2026, 10, 1, 9, 30), utc(2026, 10, 1, 9, 30)]

        await scheduler.run_planning_cycle(gateway, utc(2026, 10, 1, 8, 0))

        assert sleeps == [0.0]
        gateway.proaction.assert_awaited_once()
        assert gateway.proaction.await_args.args[1] == "user-ny"

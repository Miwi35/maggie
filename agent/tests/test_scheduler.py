from datetime import UTC, datetime
from unittest.mock import AsyncMock

import pytest

from app.config import settings
from app.db.instruction_repository import instruction_repo
from app.queue.scheduler import next_planning_time, plan_all_users


@pytest.fixture(autouse=True)
def paris_at_five(monkeypatch):
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


class TestPlanAllUsers:
    async def test_plans_with_the_real_user_id_of_stored_instructions(self, agent_db):
        await instruction_repo.store("user-42", "Digest du matin à 7h")
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}

        await plan_all_users(gateway)

        gateway.proaction.assert_awaited_once()
        assert gateway.proaction.await_args.args[1] == "user-42"
        assert gateway.proaction.await_args.kwargs == {"silent": True}

    async def test_plans_every_user_once_and_skips_users_without_instructions(self, agent_db):
        await instruction_repo.store("user-a", "Rule 1")
        await instruction_repo.store("user-a", "Rule 2")
        await instruction_repo.store("user-b", "Rule 3")
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}

        await plan_all_users(gateway)

        planned = sorted(call.args[1] for call in gateway.proaction.await_args_list)
        assert planned == ["user-a", "user-b"]

    async def test_no_instructions_means_no_planning(self, agent_db):
        gateway = AsyncMock()

        await plan_all_users(gateway)

        gateway.proaction.assert_not_awaited()

    async def test_one_failing_user_does_not_stop_the_others(self, agent_db):
        await instruction_repo.store("user-a", "Rule 1")
        await instruction_repo.store("user-b", "Rule 2")
        gateway = AsyncMock()
        gateway.proaction.side_effect = [RuntimeError("boom"), {"response": "ok"}]

        await plan_all_users(gateway)

        assert gateway.proaction.await_count == 2

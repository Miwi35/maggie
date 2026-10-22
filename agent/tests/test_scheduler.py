import asyncio
import json
from collections import defaultdict
from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from zoneinfo import ZoneInfo

import pytest

from app.config import settings
from app.db.context_model import ContextStatus, ConversationContext
from app.db.context_repository import context_repo
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app import user_timezone
from app.proactions import api as proactions_api_module
from app.proactions.api import Proaction, ProactionApiUnreachable
from app.queue import scheduler
from app.queue.scheduler import next_planning_time, plan_user, planning_agenda, planning_schedule
from app.user_timezone import resolve_user_timezone
from tests.fake_proaction_api import FakeProactionApi


@pytest.fixture(autouse=True)
def paris_at_five(monkeypatch):
    user_timezone.known_timezones.clear()
    user_timezone.read_at.clear()
    monkeypatch.setattr(settings, "planning_timezone", "Europe/Paris")
    monkeypatch.setattr(settings, "daily_planning_hour", 5)


@pytest.fixture(autouse=True)
def no_agenda_for_the_recall(monkeypatch):
    monkeypatch.setattr(scheduler, "planning_agenda", AsyncMock(return_value=""))


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
    monkeypatch.setattr(user_timezone.mcp_client, "call_tool", mock)
    user_timezone.read_at.clear()  # a new scenario: nothing read before it counts as fresh
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
        assert gateway.proaction.await_args.kwargs == {"silent": True, "about": ""}

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


NOW = utc(2026, 10, 1, 12, 0)


def age(chat_db, context_id: str, **ago) -> None:
    """Make a context look quiet for `ago` as of NOW."""
    session = chat_db.session()
    session._session.get(ConversationContext, context_id).updated_at = NOW - timedelta(**ago)
    session._session.commit()
    session._session.close()


async def context_at(chat_db, user_id: str, label: str, status: ContextStatus = ContextStatus.ACTIVE, **ago):
    ctx = await context_repo.create(user_id, label)
    if status != ContextStatus.ACTIVE:
        await context_repo.update_status(str(ctx.id), status)
    age(chat_db, str(ctx.id), **ago)
    return str(ctx.id)


class TestContextLifecycle:
    """Active → dormant after N hours, → closed after M days, a summary before each (MAG-12)."""

    @pytest.fixture(autouse=True)
    def thresholds(self, monkeypatch):
        monkeypatch.setattr(settings, "context_dormant_after_hours", 24)
        monkeypatch.setattr(settings, "context_close_after_days", 14)

    @pytest.fixture()
    def summarizer(self, monkeypatch):
        summarize = AsyncMock(return_value="Un résumé.")
        monkeypatch.setattr(scheduler.context_summarizer, "summarize", summarize)
        return summarize

    async def status_of(self, context_id: str) -> ContextStatus:
        return (await context_repo.get(context_id)).status

    async def test_a_quiet_active_context_goes_dormant_with_a_summary(self, chat_db, summarizer):
        quiet = await context_at(chat_db, "user-1", "Courses", hours=25)
        chat_db.published.reset_mock()

        counts = await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(quiet) == ContextStatus.DORMANT
        summarizer.assert_awaited_once_with(quiet)
        assert counts == {"dormant": 1, "closed": 0}
        assert chat_db.published.await_args.args[1]["status"] == "dormant"

    async def test_a_recent_context_is_left_alone(self, chat_db, summarizer):
        recent = await context_at(chat_db, "user-1", "Courses", hours=23)

        counts = await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(recent) == ContextStatus.ACTIVE
        summarizer.assert_not_awaited()
        assert counts == {"dormant": 0, "closed": 0}

    async def test_a_dormant_context_stays_dormant_until_the_close_threshold(self, chat_db, summarizer):
        dormant = await context_at(chat_db, "user-1", "Budget", ContextStatus.DORMANT, days=13)

        counts = await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(dormant) == ContextStatus.DORMANT
        summarizer.assert_not_awaited()
        assert counts == {"dormant": 0, "closed": 0}

    async def test_a_long_quiet_dormant_context_is_closed_with_a_final_summary(self, chat_db, summarizer):
        dormant = await context_at(chat_db, "user-1", "Budget", ContextStatus.DORMANT, days=15)

        counts = await scheduler.run_context_lifecycle(NOW)

        ctx = await context_repo.get(dormant)
        assert ctx.status == ContextStatus.CLOSED
        assert ctx.closed_at is not None
        summarizer.assert_awaited_once_with(dormant)
        assert counts == {"dormant": 0, "closed": 1}

    async def test_an_active_context_quiet_past_the_close_threshold_closes_directly(self, chat_db, summarizer):
        """An agent that was down for weeks: one summary, one transition, no stop at dormant."""
        quiet = await context_at(chat_db, "user-1", "Courses", days=20)

        counts = await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(quiet) == ContextStatus.CLOSED
        summarizer.assert_awaited_once_with(quiet)
        assert counts == {"dormant": 0, "closed": 1}

    async def test_the_close_threshold_counts_from_the_last_message_not_from_going_dormant(self, chat_db, summarizer):
        quiet = await context_at(chat_db, "user-1", "Courses", hours=30)

        await scheduler.run_context_lifecycle(NOW)
        assert await self.status_of(quiet) == ContextStatus.DORMANT

        # 13 days after the dormant step the thread has been quiet 14 days and 6 hours.
        await scheduler.run_context_lifecycle(NOW + timedelta(days=12))
        assert await self.status_of(quiet) == ContextStatus.DORMANT
        await scheduler.run_context_lifecycle(NOW + timedelta(days=13))
        assert await self.status_of(quiet) == ContextStatus.CLOSED

    async def test_a_closed_context_is_not_touched_again(self, chat_db, summarizer):
        closed = await context_at(chat_db, "user-1", "Vieux", ContextStatus.CLOSED, days=40)
        chat_db.published.reset_mock()

        await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(closed) == ContextStatus.CLOSED
        summarizer.assert_not_awaited()
        chat_db.published.assert_not_awaited()

    async def test_every_user_s_contexts_are_handled(self, chat_db, summarizer):
        first = await context_at(chat_db, "user-1", "Courses", hours=30)
        second = await context_at(chat_db, "user-2", "Budget", hours=30)

        await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(first) == ContextStatus.DORMANT
        assert await self.status_of(second) == ContextStatus.DORMANT

    async def test_a_message_that_lands_during_the_summary_keeps_the_context_active(self, chat_db, summarizer):
        quiet = await context_at(chat_db, "user-1", "Courses", hours=30)

        async def user_comes_back(context_id):
            await context_repo.touch(context_id)

        summarizer.side_effect = user_comes_back

        counts = await scheduler.run_context_lifecycle(NOW)

        # `touch` stamps the real clock, which is later than the cycle's cutoff.
        assert await self.status_of(quiet) == ContextStatus.ACTIVE
        assert counts == {"dormant": 0, "closed": 0}

    async def test_one_failing_context_does_not_stop_the_others(self, chat_db, summarizer, monkeypatch):
        broken = await context_at(chat_db, "user-1", "Cassé", hours=30)
        healthy = await context_at(chat_db, "user-1", "Sain", hours=29)
        real_update = context_repo.update_status

        async def fail_for_broken(context_id, status, **kwargs):
            if context_id == broken:
                raise RuntimeError("db hiccup")
            return await real_update(context_id, status, **kwargs)

        monkeypatch.setattr(scheduler.context_repo, "update_status", fail_for_broken)

        counts = await scheduler.run_context_lifecycle(NOW)

        assert await self.status_of(broken) == ContextStatus.ACTIVE
        assert await self.status_of(healthy) == ContextStatus.DORMANT
        assert counts == {"dormant": 1, "closed": 0}

    async def test_the_loop_survives_a_failed_cycle(self, monkeypatch):
        cycles = AsyncMock(side_effect=[RuntimeError("db down"), {"dormant": 0, "closed": 0}])
        monkeypatch.setattr(scheduler, "run_context_lifecycle", cycles)
        sleeps = []

        async def sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) == 2:
                raise asyncio.CancelledError

        monkeypatch.setattr(scheduler.asyncio, "sleep", sleep)

        with pytest.raises(asyncio.CancelledError):
            await scheduler._context_lifecycle_loop()

        assert cycles.await_count == 2
        assert sleeps == [settings.context_lifecycle_interval_seconds] * 2

    async def test_the_scheduler_starts_the_lifecycle_loop(self, monkeypatch):
        monkeypatch.setattr(scheduler, "_tasks", [])
        started = []
        monkeypatch.setattr(scheduler, "_context_lifecycle_loop", lambda: started.append("lifecycle") or asyncio.sleep(0))
        monkeypatch.setattr(scheduler, "_execution_loop", lambda: asyncio.sleep(0))
        monkeypatch.setattr(scheduler, "_daily_planning_loop", lambda: asyncio.sleep(0))
        monkeypatch.setattr(scheduler, "_style_synthesis_loop", lambda: started.append("style") or asyncio.sleep(0))
        monkeypatch.setattr(scheduler, "_memory_encoding_loop", lambda: started.append("encoding") or asyncio.sleep(0))

        await scheduler.start_scheduler()

        assert started == ["lifecycle", "style", "encoding"]
        assert len(scheduler._tasks) == 5


class TestExecutionLoop:
    """One minute-grained loop: due proactions claimed on the API and queued, stale approvals retired (MAG-4, MAG-327)."""

    @pytest.fixture()
    def one_pass(self, monkeypatch):
        """Let the loop run exactly one iteration, then stop it at its sleep."""

        async def sleep(_seconds):
            raise asyncio.CancelledError

        monkeypatch.setattr(scheduler.asyncio, "sleep", sleep)

    @pytest.fixture()
    def repos(self, monkeypatch):
        claim_due = AsyncMock(return_value=[])
        record = AsyncMock()
        expire = AsyncMock(return_value=0)
        publish = AsyncMock()
        migrate = AsyncMock(return_value=True)
        monkeypatch.setattr(scheduler.proactions_api, "claim_due", claim_due)
        monkeypatch.setattr(scheduler.proactions_api, "record", record)
        monkeypatch.setattr(scheduler.pending_action_repo, "expire_overdue", expire)
        monkeypatch.setattr(scheduler, "publish_proaction", publish)
        monkeypatch.setattr(scheduler, "migrate_legacy_proactions", migrate)
        return SimpleNamespace(claim_due=claim_due, record=record, expire=expire, publish=publish, migrate=migrate)

    @staticmethod
    def _claimed(proaction_id: str) -> Proaction:
        return Proaction(id=proaction_id, user_id="user-1", prompt="Boire un verre d'eau", status="running")

    async def test_it_retires_the_approvals_nobody_answered(self, one_pass, repos):
        with pytest.raises(asyncio.CancelledError):
            await scheduler._execution_loop()

        repos.expire.assert_awaited_once()

    async def test_the_due_proactions_claimed_on_the_api_are_queued(self, repos):
        first, second = self._claimed("p-1"), self._claimed("p-2")
        repos.claim_due.return_value = [first, second]

        await scheduler.run_execution_tick()

        # Every user's, with the service token alone: no user named.
        repos.claim_due.assert_awaited_once_with()
        assert [call.args[0] for call in repos.publish.await_args_list] == [first, second]
        repos.record.assert_not_awaited()

    async def test_nothing_due_queues_nothing(self, repos):
        await scheduler.run_execution_tick()

        repos.publish.assert_not_awaited()

    async def test_an_unreachable_api_does_not_stop_the_loop_and_is_asked_again_next_minute(self, repos, monkeypatch):
        repos.claim_due.side_effect = [ProactionApiUnreachable("claim_due_proactions: no response"), [self._claimed("p-1")]]
        ticks = iter([None, asyncio.CancelledError()])

        async def sleep(_seconds):
            tick = next(ticks)
            if tick is not None:
                raise tick

        monkeypatch.setattr(scheduler.asyncio, "sleep", sleep)

        with pytest.raises(asyncio.CancelledError):
            await scheduler._execution_loop()

        assert repos.claim_due.await_count == 2
        assert repos.expire.await_count == 2
        repos.publish.assert_awaited_once()

    async def test_a_proaction_that_cannot_be_queued_is_recorded_failed_not_left_running(self, repos):
        claimed = self._claimed("p-1")
        repos.claim_due.return_value = [claimed, self._claimed("p-2")]
        repos.publish.side_effect = [RuntimeError("RabbitMQ not connected"), None]

        await scheduler.run_execution_tick()

        repos.record.assert_awaited_once()
        assert repos.record.await_args.args == (claimed, "failed")
        assert "RabbitMQ not connected" in repos.record.await_args.kwargs["error"]
        assert repos.publish.await_count == 2

    async def test_the_legacy_proactions_are_moved_first_and_a_failed_move_runs_the_rest(self, repos):
        repos.migrate.side_effect = RuntimeError("agent db down")
        repos.claim_due.return_value = [self._claimed("p-1")]

        await scheduler.run_execution_tick()

        repos.migrate.assert_awaited_once()
        repos.publish.assert_awaited_once()
        repos.expire.assert_awaited_once()

    async def test_a_failing_proaction_poll_does_not_skip_the_expiry(self, one_pass, repos):
        # The two have nothing to do with each other, so one being down must not leave
        # cards on the user's screen for ever.
        repos.claim_due.side_effect = RuntimeError("api down")

        with pytest.raises(asyncio.CancelledError):
            await scheduler._execution_loop()

        repos.expire.assert_awaited_once()

    async def test_a_failing_expiry_does_not_stop_the_loop(self, one_pass, repos):
        repos.expire.side_effect = RuntimeError("db down")

        with pytest.raises(asyncio.CancelledError):
            await scheduler._execution_loop()

        repos.claim_due.assert_awaited_once()


class TestExecutionThroughTheApi:
    """The loop against the simulated API: what the agenda holds is what runs (MAG-327)."""

    @pytest.fixture()
    def api(self, monkeypatch):
        fake = FakeProactionApi()
        monkeypatch.setattr(proactions_api_module, "mcp_client", fake)
        monkeypatch.setattr(scheduler, "migrate_legacy_proactions", AsyncMock(return_value=True))
        monkeypatch.setattr(scheduler.pending_action_repo, "expire_overdue", AsyncMock(return_value=0))
        return fake

    async def test_a_due_proaction_is_claimed_once_and_queued(self, api, monkeypatch):
        due = api.seed("user-1", at="2030-11-13T11:55:00+01:00")
        api.seed("user-1", at="2030-11-13T18:00:00+01:00")
        publish = AsyncMock()
        monkeypatch.setattr(scheduler, "publish_proaction", publish)

        await scheduler.run_execution_tick()
        await scheduler.run_execution_tick()

        assert [call.args[0].id for call in publish.await_args_list] == [due]
        assert api.proactions[due]["status"] == "running"

    async def test_a_proaction_deleted_from_the_agenda_before_its_time_never_runs(self, api, monkeypatch):
        deleted = api.seed("user-1", at="2030-11-13T11:55:00+01:00")
        await proactions_api_module.mcp_client.call_tool("cancel_proaction", {"id": deleted}, user_id="user-1")
        publish = AsyncMock()
        monkeypatch.setattr(scheduler, "publish_proaction", publish)

        await scheduler.run_execution_tick()

        publish.assert_not_awaited()


class TestDailyPlanningPrompt:
    """The planning poses the day's proactions from the instructions and the agenda, once (MAG-327)."""

    def test_it_names_the_day_and_reads_instructions_agenda_and_existing_proactions(self):
        prompt = scheduler.daily_planning_prompt(date(2026, 10, 14))

        assert "2026-10-14" in prompt
        assert 'kind="planning"' in prompt
        assert "get_events_by_date" in prompt
        assert 'list_proactions avec from="2026-10-14" et to="2026-10-14"' in prompt
        assert "sans recréer celles qui existent déjà" in prompt

    async def test_the_day_is_the_user_s_own(self, monkeypatch):
        # 23:30 UTC on the 13th is already the 14th in Paris.
        monkeypatch.setattr(scheduler, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris")))
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}

        await scheduler.generate_proactions(gateway, "user-1", dry_run=True, now=utc(2026, 10, 13, 23, 30))

        prompt = gateway.proaction.await_args.args[0]
        assert "2026-10-14" in prompt
        assert gateway.proaction.await_args.kwargs == {"silent": True, "dry_run": True, "about": ""}


class TestPlanningRecall:
    """The 5h planning recalls on the events of the coming day (MAG-16)."""

    async def test_the_events_of_the_day_are_what_the_recall_reads(self, monkeypatch):
        calls = AsyncMock(
            return_value=json.dumps(
                {
                    "date": "2026-10-14",
                    "events": [
                        {"summary": "Fête des mères", "location": "Chez Françoise", "description": None},
                        {"summary": "Dentiste", "location": None, "description": "Cabinet rue Haute"},
                    ],
                    "count": 2,
                }
            )
        )
        monkeypatch.setattr(scheduler.mcp_client, "call_tool", calls)

        text = await planning_agenda("user-1", date(2026, 10, 14))

        assert text == "Fête des mères Chez Françoise\nDentiste Cabinet rue Haute"
        assert calls.await_args.args[:2] == ("get_events_by_date", {"date": "2026-10-14"})
        assert calls.await_args.kwargs == {"user_id": "user-1"}

    async def test_an_api_that_does_not_answer_costs_the_recall_and_nothing_else(self, monkeypatch):
        monkeypatch.setattr(scheduler.mcp_client, "call_tool", AsyncMock(side_effect=RuntimeError("down")))

        assert await planning_agenda("user-1", date(2026, 10, 14)) == ""

    async def test_an_answer_that_is_not_json_is_ignored(self, monkeypatch):
        monkeypatch.setattr(scheduler.mcp_client, "call_tool", AsyncMock(return_value="Error: nope"))

        assert await planning_agenda("user-1", date(2026, 10, 14)) == ""

    async def test_a_slow_api_is_given_up_on(self, monkeypatch):
        async def slow(*args, **kwargs):
            await asyncio.sleep(5)

        monkeypatch.setattr(scheduler.mcp_client, "call_tool", slow)
        monkeypatch.setattr(scheduler, "PLANNING_AGENDA_TIMEOUT", 0.05)

        assert await planning_agenda("user-1", date(2026, 10, 14)) == ""

    async def test_the_planning_hands_the_events_to_the_gateway_as_what_it_is_about(self, monkeypatch):
        monkeypatch.setattr(scheduler, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris")))
        monkeypatch.setattr(scheduler, "planning_agenda", AsyncMock(return_value="Fête des mères"))
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}

        await scheduler.generate_proactions(gateway, "user-1", now=utc(2026, 10, 14, 3, 0))

        assert gateway.proaction.await_args.kwargs == {"silent": True, "about": "Fête des mères"}
        scheduler.planning_agenda.assert_awaited_once_with("user-1", date(2026, 10, 14))

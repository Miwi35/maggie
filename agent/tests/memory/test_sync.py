import asyncio
import logging
from datetime import timedelta

import pytest

from app import metrics
from app.memory.frontmatter import NoteDoc
from app.memory.sync import RETRY_BASE_SECONDS, MemorySync, TurnState
from tests.memory.conftest import RacyBucket


def passes(trigger: str, outcome: str) -> float:
    return metrics.MEMORY_SYNC_PASSES.labels(trigger=trigger, outcome=outcome)._value.get()


class TestPass:
    async def test_a_pass_flushes_then_reconciles_then_refreshes_the_summary(self, world):
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="Queued", body="offline"))
        world.bucket.down = False
        owner = world.seed(f"{world.user}/Owner.md", title="Owner")

        outcome = await world.sync.run_pass("manual")

        assert outcome.ok and outcome.trigger == "manual" and outcome.error is None
        assert outcome.flush.pushed == 1 and outcome.flush.remaining == 0
        assert outcome.report.indexed >= 1
        assert queued.path in world.bucket.objects and world.row(owner.id).title == "Owner"
        summary = world.bucket.objects[f"{world.user}/SOMMAIRE.md"].body.decode()
        assert "[Owner](Owner.md)" in summary and "[Queued](Queued.md)" in summary
        assert outcome.to_dict()["ok"] is True and outcome.to_dict()["outboxRemaining"] == 0
        assert world.sync.last_outcome is outcome

    async def test_a_pass_that_is_running_is_joined_not_doubled(self, make_world):
        bucket = RacyBucket()
        bucket.list_gate = asyncio.Event()
        world = make_world(bucket)

        first = asyncio.ensure_future(world.sync.run_pass("loop"))
        await bucket.list_started.wait()
        second = asyncio.ensure_future(world.sync.run_pass("manual"))
        for _ in range(10):
            await asyncio.sleep(0)
        bucket.list_gate.set()
        one, two = await asyncio.gather(first, second)

        assert one is two and one.trigger == "loop"
        assert bucket.count("list") == 1

    async def test_a_cancelled_waiter_does_not_kill_the_pass(self, make_world):
        bucket = RacyBucket()
        bucket.list_gate = asyncio.Event()
        world = make_world(bucket)

        waiter = asyncio.ensure_future(world.sync.run_pass("turn"))
        await bucket.list_started.wait()
        waiter.cancel()
        with pytest.raises(asyncio.CancelledError):
            await waiter
        bucket.list_gate.set()
        outcome = await world.sync._inflight

        assert outcome.ok

    async def test_the_summary_of_a_user_who_did_not_change_is_left_alone(self, world):
        world.seed(f"{world.user}/a.md")
        await world.sync.run_pass("loop")
        puts = world.bucket.count("put")

        await world.sync.run_pass("loop")

        assert world.bucket.count("put") == puts

    async def test_the_mass_deletion_guard_moves_the_metric(self, world):
        seeded = world.seed_many(world.user, 10)
        await world.sync.run_pass("loop")
        before = metrics.MEMORY_MASS_DELETION_BLOCKED._value.get()

        for item in seeded[:6]:
            world.bucket.remove(item.key)
        outcome = await world.sync.run_pass("loop")

        assert outcome.ok and outcome.report.blocked_deletions == {world.user: 6}
        assert metrics.MEMORY_MASS_DELETION_BLOCKED._value.get() == before + 1


class TestDegraded:
    async def test_a_failed_pass_degrades_logs_critical_and_counts(self, world, caplog):
        await world.sync.run_pass("loop")
        world.clock.advance(100)
        world.bucket.down = True
        failed_before = passes("loop", "failed")

        with caplog.at_level(logging.INFO):
            outcome = await world.sync.run_pass("loop")

        assert not outcome.ok and "bucket unavailable" in str(outcome.error)
        assert world.sync.degraded is True and world.sync.failures == 1
        assert world.sync.stale_age() == timedelta(seconds=100)
        assert [r.levelno for r in caplog.records if "unreachable" in r.message] == [logging.CRITICAL]
        assert passes("loop", "failed") == failed_before + 1
        assert metrics.MEMORY_SYNC_DEGRADED._value.get() == 1
        assert metrics.MEMORY_INDEX_AGE_SECONDS._value.get() == 100
        assert outcome.to_dict()["ok"] is False

    async def test_the_staleness_grows_with_the_clock_and_the_log_does_not_repeat_critical(self, world, caplog):
        await world.sync.run_pass("loop")
        world.bucket.down = True
        await world.sync.run_pass("loop")
        world.clock.advance(60)
        caplog.clear()

        with caplog.at_level(logging.INFO):
            await world.sync.run_pass("loop")

        assert world.sync.failures == 2 and world.sync.stale_age() == timedelta(seconds=60)
        assert [r.levelno for r in caplog.records if "unreachable" in r.message] == [logging.ERROR]

    async def test_the_next_success_recovers(self, world, caplog):
        await world.sync.run_pass("loop")
        world.bucket.down = True
        await world.sync.run_pass("loop")
        world.bucket.down = False
        ok_before = passes("loop", "ok")

        with caplog.at_level(logging.INFO):
            outcome = await world.sync.run_pass("loop")

        assert outcome.ok and world.sync.degraded is False and world.sync.failures == 0
        assert world.sync.stale_age() is None
        assert world.sync.last_success_at == world.clock()
        assert any("is back" in r.message for r in caplog.records)
        assert passes("loop", "ok") == ok_before + 1
        assert metrics.MEMORY_SYNC_DEGRADED._value.get() == 0
        assert metrics.MEMORY_INDEX_AGE_SECONDS._value.get() == 0

    async def test_a_bug_in_a_pass_fails_it_without_declaring_the_bucket_down(self, world, monkeypatch, caplog):
        async def broken(quiet: bool = False):
            raise RuntimeError("boom")

        monkeypatch.setattr(world.reconciler, "reconcile", broken)

        outcome = await world.sync.run_pass("loop")

        assert not outcome.ok and "RuntimeError: boom" in str(outcome.error)
        assert world.sync.degraded is False and world.sync.failures == 1

    async def test_booting_with_the_bucket_down_dates_the_index_from_its_own_entries(self, world):
        world.seed(f"{world.user}/a.md")
        await world.reconciler.reconcile()
        world.update_row(world.row_at(f"{world.user}/a.md").id, indexed_at=world.clock() - timedelta(seconds=600))
        booted = MemorySync(world.reconciler, world.store, world.summary, world.repo, clock=world.clock)
        assert booted.last_success_at is None
        world.bucket.down = True

        await booted.run_pass("loop")

        assert booted.degraded is True
        assert booted.last_success_at == world.clock() - timedelta(seconds=600)
        assert booted.stale_age() == timedelta(seconds=600)
        assert booted.retry_delay() == RETRY_BASE_SECONDS

    async def test_booting_with_the_bucket_down_and_no_index_has_no_age(self, world):
        world.bucket.down = True

        await world.sync.run_pass("loop")

        assert world.sync.degraded is True and world.sync.stale_age() is None

    async def test_the_retry_delay_backs_off_and_is_capped_by_the_interval(self, world):
        assert world.sync.retry_delay() == 60.0
        delays = []
        world.bucket.down = True
        for _ in range(6):
            await world.sync.run_pass("loop")
            delays.append(world.sync.retry_delay())

        assert delays == [5.0, 10.0, 20.0, 40.0, 60.0, 60.0]
        world.bucket.down = False
        await world.sync.run_pass("loop")
        assert world.sync.retry_delay() == 60.0


class TestTurn:
    async def test_a_recent_pass_means_no_bucket_call(self, world):
        await world.sync.run_pass("loop")
        lists = world.bucket.count("list")
        world.clock.advance(10)

        state = await world.sync.before_turn()

        assert state == TurnState(stale=False, stale_age=None, skipped=False)
        assert world.bucket.count("list") == lists

    async def test_an_old_pass_means_a_new_one(self, world):
        await world.sync.run_pass("loop")
        lists = world.bucket.count("list")
        world.clock.advance(31)

        state = await world.sync.before_turn()

        assert world.bucket.count("list") == lists + 1
        assert state == TurnState(False, None, False)
        assert world.sync.last_outcome.trigger == "turn"

    async def test_the_first_turn_runs_a_pass(self, world):
        await world.sync.before_turn()

        assert world.bucket.count("list") == 1

    async def test_a_turn_picks_up_what_the_owner_just_edited(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1")
        await world.sync.run_pass("loop")
        world.seed(seeded.key, body="v2", note_id=seeded.id)
        world.clock.advance(31)

        await world.sync.before_turn()

        assert world.row(seeded.id).body == "v2"

    async def test_a_pass_slower_than_the_turn_bound_is_skipped_not_stale(self, make_world):
        bucket = RacyBucket()
        bucket.list_gate = asyncio.Event()
        world = make_world(bucket, turn_timeout_seconds=0.01)

        state = await world.sync.before_turn()

        assert state.skipped is True and state.stale is False and state.stale_age is None
        bucket.list_gate.set()
        outcome = await world.sync._inflight
        assert outcome.ok, "the pass keeps running in the background"

    async def test_a_bucket_that_is_down_is_stale_with_its_age_and_not_skipped(self, world):
        await world.sync.run_pass("loop")
        world.clock.advance(50)
        world.bucket.down = True

        state = await world.sync.before_turn()

        assert state.stale is True and state.skipped is False
        assert state.stale_age == timedelta(seconds=50)

    async def test_a_degraded_index_stays_flagged_without_a_new_attempt_within_the_window(self, world):
        await world.sync.run_pass("loop")
        world.clock.advance(50)
        world.bucket.down = True
        await world.sync.before_turn()
        lists = world.bucket.count("list")
        world.clock.advance(5)

        state = await world.sync.before_turn()

        assert world.bucket.count("list") == lists
        assert state.stale is True and state.stale_age == timedelta(seconds=55) and state.skipped is False

    async def test_a_pass_that_raises_out_of_run_pass_is_skipped(self, world, monkeypatch):
        async def explode(trigger: str):
            raise RuntimeError("unexpected")

        monkeypatch.setattr(world.sync, "run_pass", explode)

        state = await world.sync.before_turn()

        assert state.skipped is True

    async def test_consolidation_always_runs_a_pass(self, world):
        await world.sync.run_pass("loop")
        lists = world.bucket.count("list")

        outcome = await world.sync.before_consolidation()

        assert outcome.ok and outcome.trigger == "consolidation"
        assert world.bucket.count("list") == lists + 1


class TestLoop:
    async def test_the_loop_survives_a_pass_that_raises_and_stops_cleanly(self, make_world, caplog):
        world = make_world(interval_seconds=0)
        calls: list[str] = []
        third = asyncio.Event()
        real = world.sync.run_pass

        async def run_pass(trigger: str):
            calls.append(trigger)
            if len(calls) == 3:
                third.set()
            if len(calls) == 1:
                raise RuntimeError("the pass blew up")
            return await real(trigger)

        world.sync.run_pass = run_pass  # type: ignore[method-assign]
        with caplog.at_level(logging.ERROR):
            world.sync.start()
            first_task = world.sync._loop_task
            world.sync.start()
            assert world.sync._loop_task is first_task, "a second start must not spawn a second loop"
            await asyncio.wait_for(third.wait(), timeout=5)
            await world.sync.stop()

        assert calls[:3] == ["loop", "loop", "loop"]
        assert world.sync._loop_task is None and first_task.done()
        assert any("loop iteration failed" in r.message for r in caplog.records)
        seen = len(calls)
        for _ in range(10):
            await asyncio.sleep(0)
        assert len(calls) == seen, "stopped means stopped"

    async def test_stop_without_start_is_harmless(self, world):
        await world.sync.stop()

        assert world.sync._loop_task is None

    async def test_the_loop_can_be_started_again_after_a_stop(self, make_world):
        world = make_world(interval_seconds=0)
        world.sync.start()
        await world.sync.stop()

        world.sync.start()

        assert world.sync._loop_task is not None and not world.sync._loop_task.done()
        await world.sync.stop()

    async def test_the_loop_reconciles(self, make_world):
        world = make_world(interval_seconds=0)
        seeded = world.seed(f"{world.user}/a.md")
        done = asyncio.Event()
        real = world.sync.run_pass

        async def run_pass(trigger: str):
            outcome = await real(trigger)
            done.set()
            return outcome

        world.sync.run_pass = run_pass  # type: ignore[method-assign]
        world.sync.start()
        await asyncio.wait_for(done.wait(), timeout=5)
        await world.sync.stop()

        assert world.row(seeded.id).title == "Titre"

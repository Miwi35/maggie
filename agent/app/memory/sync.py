"""Keeping the index and the bucket in step: the background loop, the turn-start pass, degraded mode.

One pass = flush the outbox, then reconcile, then refresh the summaries of the users who changed.
Passes never overlap. Three ways in, all through `run_pass`:

- the 60 s loop, started by the app and independent of RabbitMQ — bucket notifications are not
  used, so this is the only way an owner's edit reaches the index without a turn;
- `before_turn`: a pass only if the last attempt is older than 30 s, and the turn waits for it at
  most a couple of seconds. A pass that overruns keeps running in the background and the turn
  carries `skipped` — « not checked » — which is not `stale` — « checked and the bucket is down »;
- the manual trigger and the nightly consolidation (`before_consolidation`), which always run one.

A failed pass flips the service to degraded: the index keeps being served (marked stale, with its
age), writes go to the outbox, the log is critical and the metrics move, but readiness stays green —
a bucket outage must not take the agent out of service.
"""

import asyncio
import contextlib
import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from app import metrics
from app.db.memory_note_repository import MemoryNoteRepository, memory_note_repo
from app.memory.bucket import BucketUnavailable
from app.memory.reconciler import PassReport, Reconciler
from app.memory.store import FlushReport, NoteStore
from app.memory.summary import SummaryWriter

logger = logging.getLogger(__name__)

RETRY_BASE_SECONDS = 5.0


class RebuildBlocked(RuntimeError):
    """Writes are still queued: rebuilding would drop the only copy of them from the index."""


@dataclass
class PassOutcome:
    ok: bool
    trigger: str
    error: str | None = None
    report: PassReport | None = None
    flush: FlushReport | None = None

    def to_dict(self) -> dict:
        return {
            "ok": self.ok,
            "trigger": self.trigger,
            "error": self.error,
            "report": self.report.to_dict() if self.report else None,
            "outboxRemaining": self.flush.remaining if self.flush else None,
        }


@dataclass
class TurnState:
    """What a turn must tell the model about the memory it is reading."""

    stale: bool = False
    stale_age: timedelta | None = None
    skipped: bool = False


class MemorySync:
    def __init__(
        self,
        reconciler: Reconciler,
        store: NoteStore,
        summary: SummaryWriter,
        repo: MemoryNoteRepository | None = None,
        interval_seconds: float = 60.0,
        turn_max_age_seconds: float = 30.0,
        turn_timeout_seconds: float = 2.0,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.reconciler = reconciler
        self.store = store
        self.summary = summary
        self.repo = repo or memory_note_repo
        self.interval_seconds = interval_seconds
        self.turn_max_age = timedelta(seconds=turn_max_age_seconds)
        self.turn_timeout_seconds = turn_timeout_seconds
        self._clock = clock or (lambda: datetime.now(UTC))
        self.last_attempt_at: datetime | None = None
        self.last_success_at: datetime | None = None
        self.last_outcome: PassOutcome | None = None
        self.degraded = False
        self.failures = 0
        # Usage saved by a rebuild whose notes are not all back in the index yet.
        self._pending_usage: dict[str, tuple[datetime | None, int]] = {}
        self._pass_lock = asyncio.Lock()
        self._inflight: asyncio.Task[PassOutcome] | None = None
        self._loop_task: asyncio.Task[None] | None = None

    # ------------------------------------------------------------------ passes

    async def run_pass(self, trigger: str) -> PassOutcome:
        """One full pass. Joins the pass already running instead of starting a second one."""
        if self._inflight is not None and not self._inflight.done():
            return await asyncio.shield(self._inflight)
        self._inflight = asyncio.ensure_future(self._pass(trigger))
        return await asyncio.shield(self._inflight)

    async def _pass(self, trigger: str) -> PassOutcome:
        async with self._pass_lock:
            self.last_attempt_at = self._clock()
            try:
                flush = await self.store.flush_outbox()
                report = await self.reconciler.reconcile()
                await self._restore_pending_usage(final=not report.incomplete)
                for user_id in report.changed_users:
                    await self.summary.refresh(user_id)
            except BucketUnavailable as e:
                return await self._failed(trigger, f"bucket unavailable: {e}", critical=True)
            except Exception as e:
                logger.exception("Memory sync pass failed")
                return await self._failed(trigger, f"{type(e).__name__}: {e}", critical=False)
            return await self._succeeded(trigger, report, flush)

    async def _failed(self, trigger: str, error: str, critical: bool) -> PassOutcome:
        self.failures += 1
        if self.last_success_at is None:
            # Booting with the bucket already down: the index's own newest entry is how old « the last good state » is.
            try:
                self.last_success_at = await self.repo.last_indexed_at()
            except Exception:
                self.last_success_at = None
        if critical:
            if not self.degraded:
                logger.critical(f"Memory bucket unreachable, serving the index as stale and queuing writes: {error}")
            else:
                logger.error(f"Memory bucket still unreachable ({self.failures} failed passes): {error}")
            self.degraded = True
        metrics.MEMORY_SYNC_PASSES.labels(trigger=trigger, outcome="failed").inc()
        metrics.MEMORY_SYNC_DEGRADED.set(1 if self.degraded else 0)
        age = self.stale_age()
        metrics.MEMORY_INDEX_AGE_SECONDS.set(age.total_seconds() if age else 0)
        self.last_outcome = PassOutcome(False, trigger, error)
        return self.last_outcome

    async def _succeeded(self, trigger: str, report: PassReport, flush: FlushReport) -> PassOutcome:
        if self.degraded:
            logger.warning(f"Memory bucket is back after {self.failures} failed passes; index reconciled")
        self.degraded = False
        self.failures = 0
        self.last_success_at = self._clock()
        if report.blocked_deletions:
            metrics.MEMORY_MASS_DELETION_BLOCKED.inc()
        metrics.MEMORY_SYNC_PASSES.labels(trigger=trigger, outcome="ok").inc()
        metrics.MEMORY_SYNC_DEGRADED.set(0)
        metrics.MEMORY_INDEX_AGE_SECONDS.set(0)
        metrics.MEMORY_OUTBOX_PENDING.set(flush.remaining)
        self.last_outcome = PassOutcome(True, trigger, report=report, flush=flush)
        return self.last_outcome

    # ------------------------------------------------------------------- state

    def stale_age(self) -> timedelta | None:
        """How long the index has been out of step with the bucket — None while the last pass succeeded."""
        if not self.degraded:
            return None
        since = self.last_success_at
        return self._clock() - since if since else None

    async def before_turn(self) -> TurnState:
        """Called as a turn starts: reconcile if the last attempt is old enough, but never hold the turn long."""
        last = self.last_attempt_at
        if last is None or self._clock() - last >= self.turn_max_age:
            try:
                await asyncio.wait_for(self.run_pass("turn"), timeout=self.turn_timeout_seconds)
            except TimeoutError:
                logger.info("Memory reconciliation at turn start did not finish in time: continuing without it")
                return TurnState(stale=self.degraded, stale_age=self.stale_age(), skipped=True)
            except Exception:
                logger.exception("Memory reconciliation at turn start failed")
                return TurnState(stale=self.degraded, stale_age=self.stale_age(), skipped=True)
        return TurnState(stale=self.degraded, stale_age=self.stale_age())

    async def before_consolidation(self) -> PassOutcome:
        """The nightly consolidation reads the index to decide what to merge or forget: it must be current."""
        return await self.run_pass("consolidation")

    async def rebuild(self, user_id: str | None = None) -> PassReport:
        """Drop the index (one user's, or everyone's) and read it back from the bucket.

        The two usage fields are the only thing the bucket cannot give back, so they are saved and
        restored by note id. The bucket is reached once before anything is dropped: a rebuild
        against a bucket that is down must not leave an empty index behind.
        """
        async with self._pass_lock:
            flush = await self.store.flush_outbox()
            if flush.remaining:
                raise RebuildBlocked(f"{flush.remaining} queued writes must reach the bucket first")
            await self.reconciler.bucket.list_objects("")
            async with self.repo.session() as session:
                self._pending_usage.update(await self.repo.usage_snapshot(session, user_id))
                await self.repo.drop_index(session, user_id)
                await session.commit()
            try:
                report = await self.reconciler.reconcile(quiet=True)
            except BaseException:
                await self._restore_pending_usage()  # what is not back yet is restored by a later pass
                raise
            await self._restore_pending_usage(final=not report.incomplete)
            async with self.repo.session() as session:
                for owner in sorted(report.changed_users):
                    await self.repo.add_event(session, owner, "rebuild", None, None, {"indexed": report.indexed})
                await session.commit()
            for owner in report.changed_users:
                await self.summary.refresh(owner)
            self.last_attempt_at = self.last_success_at = self._clock()
            self.degraded = False
            self.failures = 0
            return report

    async def _restore_pending_usage(self, final: bool = False) -> None:
        """Put saved usage back on the rows that exist; `final` (a complete pass) forgets what has no note any more."""
        if not self._pending_usage:
            return
        async with self.repo.session() as session:
            restored = await self.repo.restore_usage(session, self._pending_usage)
            await session.commit()
        for note_id in restored:
            self._pending_usage.pop(note_id, None)
        if final:
            self._pending_usage.clear()

    # -------------------------------------------------------------------- loop

    def retry_delay(self) -> float:
        if self.failures == 0:
            return self.interval_seconds
        return min(self.interval_seconds, RETRY_BASE_SECONDS * 2 ** (self.failures - 1))

    async def loop(self) -> None:
        """Reconcile forever. Nothing a pass raises may end it."""
        await self._check_versioning()
        while True:
            try:
                await self.run_pass("loop")
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Memory sync loop iteration failed")
            await asyncio.sleep(self.retry_delay())

    async def _check_versioning(self) -> None:
        """Without versioning an overwrite or a delete in the bucket is final: say so, never refuse to boot over it."""
        try:
            status = await self.reconciler.bucket.versioning_status()
        except Exception as e:
            logger.info(f"Memory bucket versioning could not be checked: {e}")
            return
        if status != "Enabled":
            logger.warning(f"Memory bucket versioning is {status}: an overwritten or deleted note cannot be recovered")

    def start(self) -> None:
        if self._loop_task is None or self._loop_task.done():
            self._loop_task = asyncio.ensure_future(self.loop())

    async def stop(self) -> None:
        if self._loop_task is not None:
            self._loop_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._loop_task
            self._loop_task = None

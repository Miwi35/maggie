"""The night's own state (MAG-159): the pass in progress, the account of the ones that ended, what was read.

Outside the rebuildable index on purpose: emptying the index loses nothing, but a pass cut off must resume
where it stopped, and tomorrow must not read again what the night already worked on.
"""

import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.memory_index_model import MemoryNightRun, MemoryNightSeen

RUNNING = "running"
DONE = "done"


@dataclass
class NightRun:
    id: str
    user_id: str
    night: str
    status: str
    done: list[str] = field(default_factory=list)
    report: dict[str, Any] = field(default_factory=dict)
    calls: int = 0
    started_at: datetime | None = None
    updated_at: datetime | None = None
    finished_at: datetime | None = None


def _as_run(row: MemoryNightRun) -> NightRun:
    return NightRun(
        id=row.id,
        user_id=row.user_id,
        night=row.night,
        status=row.status,
        done=list(row.done or []),
        report=dict(row.report or {}),
        calls=int(row.calls or 0),
        started_at=row.started_at,
        updated_at=row.updated_at,
        finished_at=row.finished_at,
    )


class MemoryNightRepository:
    async def running(self, user_id: str) -> NightRun | None:
        """The pass that was cut off, if any: the oldest first, so nothing is left behind."""
        async with agent_session() as session:
            row = (
                await session.execute(
                    select(MemoryNightRun)
                    .where(MemoryNightRun.user_id == user_id, MemoryNightRun.status == RUNNING)
                    .order_by(MemoryNightRun.night)
                    .limit(1)
                )
            ).scalar_one_or_none()
            return _as_run(row) if row else None

    async def last_done(self, user_id: str) -> NightRun | None:
        async with agent_session() as session:
            row = (
                await session.execute(
                    select(MemoryNightRun)
                    .where(MemoryNightRun.user_id == user_id, MemoryNightRun.status == DONE)
                    .order_by(MemoryNightRun.night.desc(), MemoryNightRun.started_at.desc())
                    .limit(1)
                )
            ).scalar_one_or_none()
            return _as_run(row) if row else None

    async def has_night(self, user_id: str, night: str) -> bool:
        async with agent_session() as session:
            row = (
                await session.execute(
                    select(MemoryNightRun.id).where(MemoryNightRun.user_id == user_id, MemoryNightRun.night == night)
                )
            ).first()
            return row is not None

    async def start(self, user_id: str, night: str) -> NightRun:
        now = datetime.now(UTC)
        row = MemoryNightRun(
            id=uuid.uuid4().hex,
            user_id=user_id,
            night=night,
            status=RUNNING,
            done=[],
            report={},
            calls=0,
            started_at=now,
            updated_at=now,
        )
        async with agent_session() as session:
            session.add(row)
            await session.commit()
            return _as_run(row)

    async def save(self, run: NightRun) -> None:
        """Persist the progress of a pass: its done units, its report so far, its calls."""
        async with agent_session() as session:
            row = (await session.execute(select(MemoryNightRun).where(MemoryNightRun.id == run.id))).scalar_one()
            row.done = list(run.done)
            row.report = dict(run.report)
            row.calls = run.calls
            row.status = run.status
            row.updated_at = datetime.now(UTC)
            row.finished_at = run.finished_at
            await session.commit()

    async def seen(self, user_id: str, scope: str) -> dict[str, str]:
        """card id -> the content digest the night last worked on, for one scope."""
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryNightSeen.card_id, MemoryNightSeen.digest).where(
                    MemoryNightSeen.user_id == user_id, MemoryNightSeen.scope == scope
                )
            )
            return {card_id: digest for card_id, digest in rows.all()}

    async def mark_seen(self, user_id: str, card_id: str, scope: str, digest: str) -> None:
        async with agent_session() as session:
            row = (
                await session.execute(
                    select(MemoryNightSeen).where(
                        MemoryNightSeen.user_id == user_id,
                        MemoryNightSeen.card_id == card_id,
                        MemoryNightSeen.scope == scope,
                    )
                )
            ).scalar_one_or_none()
            now = datetime.now(UTC)
            if row is None:
                session.add(MemoryNightSeen(user_id=user_id, card_id=card_id, scope=scope, digest=digest, seen_at=now))
            else:
                row.digest, row.seen_at = digest, now
            await session.commit()


memory_night_repo = MemoryNightRepository()

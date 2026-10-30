from datetime import UTC, datetime

from sqlalchemy import select, update

from app.db.agent_engine import agent_session
from app.db.provider_state_model import SINGLE_ROW, ProviderState


async def _row(session) -> ProviderState | None:
    result = await session.execute(select(ProviderState).where(ProviderState.id == SINGLE_ROW))
    return result.scalar_one_or_none()


class ProviderStateRepository:
    async def get(self) -> tuple[str, datetime | None]:
        async with agent_session() as session:
            row = await _row(session)
        if row is None or row.status == "ok":
            return "ok", None
        return row.status, row.since

    async def mark_down(self, status: str) -> None:
        """`since` is kept while the same failure goes on, and restarts when the failure changes."""
        async with agent_session() as session:
            row = await _row(session)
            if row is None:
                session.add(ProviderState(id=SINGLE_ROW, status=status, since=datetime.now(UTC)))
            elif row.status != status:
                row.status = status
                row.since = datetime.now(UTC)
            await session.commit()

    async def mark_ok(self) -> None:
        """A write only when something was down: this runs after every successful model call."""
        async with agent_session() as session:
            await session.execute(
                update(ProviderState)
                .where(ProviderState.id == SINGLE_ROW, ProviderState.status != "ok")
                .values(status="ok", since=None)
            )
            await session.commit()


provider_state_repo = ProviderStateRepository()

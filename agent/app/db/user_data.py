from sqlalchemy import delete, func, select

from app.db.agent_engine import agent_session
from app.db.proaction_model import AgentBase


def _user_tables():
    # Every agent table that carries a `user_id`, whatever model adds one later; global tables (skills) have none.
    return [t for t in AgentBase.metadata.sorted_tables if "user_id" in t.c]


async def purge_user_data(user_id: str, *, dry_run: bool = False) -> dict[str, int]:
    """Delete (with `dry_run`, only count) one user's agent rows; returns the row count per table (MAG-249)."""
    counts: dict[str, int] = {}
    async with agent_session() as session:
        for table in _user_tables():
            counts[table.name] = (
                await session.execute(select(func.count()).select_from(table).where(table.c.user_id == user_id))
            ).scalar_one()
            if not dry_run and counts[table.name]:
                await session.execute(delete(table).where(table.c.user_id == user_id))
        if not dry_run:
            await session.commit()
    return counts

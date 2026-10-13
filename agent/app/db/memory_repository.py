import logging

from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.memory_model import Memory

logger = logging.getLogger(__name__)


class MemoryRepository:
    """What is left of the old memory table: read, to be turned into fiches. Nothing writes to it any more."""

    async def find_by_user(self, user_id: str) -> list[Memory]:
        async with agent_session() as session:
            stmt = select(Memory).where(Memory.user_id == user_id).order_by(Memory.created_at, Memory.id)
            return list((await session.execute(stmt)).scalars().all())

    async def user_ids(self) -> list[str]:
        async with agent_session() as session:
            rows = await session.execute(select(Memory.user_id).distinct())
            return [row[0] for row in rows.all()]


memory_repo = MemoryRepository()

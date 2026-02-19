import logging
from datetime import UTC, datetime

from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.memory_model import Memory, MemoryType

logger = logging.getLogger(__name__)


class MemoryRepository:
    """Async memory persistence in agent-owned DB."""

    async def store(self, user_id: str, content: str, memory_type: str, metadata: dict | None = None) -> Memory:
        async with agent_session() as session:
            memory = Memory(
                user_id=user_id,
                type=MemoryType(memory_type),
                content=content,
                metadata_=metadata,
            )
            session.add(memory)
            await session.commit()
            await session.refresh(memory)
            return memory

    async def search(self, user_id: str, query: str, memory_type: str | None = None) -> list[Memory]:
        """Search memories by content (case-insensitive substring match)."""
        async with agent_session() as session:
            stmt = select(Memory).where(Memory.user_id == user_id)
            if memory_type:
                stmt = stmt.where(Memory.type == MemoryType(memory_type))
            stmt = stmt.where(Memory.content.ilike(f"%{query}%"))
            stmt = stmt.order_by(Memory.created_at.desc())
            result = await session.execute(stmt)
            return list(result.scalars().all())

    async def find_by_user(self, user_id: str, memory_type: str | None = None) -> list[Memory]:
        """Get all memories for a user, optionally filtered by type."""
        async with agent_session() as session:
            stmt = select(Memory).where(Memory.user_id == user_id)
            if memory_type:
                stmt = stmt.where(Memory.type == MemoryType(memory_type))
            stmt = stmt.order_by(Memory.created_at.desc())
            result = await session.execute(stmt)
            return list(result.scalars().all())

    async def update(self, memory_id: str, content: str, metadata: dict | None = None) -> Memory | None:
        async with agent_session() as session:
            result = await session.execute(select(Memory).where(Memory.id == memory_id))
            memory = result.scalar_one_or_none()
            if memory:
                memory.content = content
                if metadata is not None:
                    memory.metadata_ = metadata
                memory.updated_at = datetime.now(UTC)
                await session.commit()
                await session.refresh(memory)
            return memory

    async def delete(self, memory_id: str) -> bool:
        async with agent_session() as session:
            result = await session.execute(select(Memory).where(Memory.id == memory_id))
            memory = result.scalar_one_or_none()
            if memory:
                await session.delete(memory)
                await session.commit()
                return True
            return False


memory_repo = MemoryRepository()

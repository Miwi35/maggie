import logging

from sqlalchemy import select, text

from app.db.engine import async_session, engine
from app.db.models import Base, Message
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class MessageRepository:
    """Async message persistence using SQLAlchemy + asyncpg.

    Every created message is automatically published to Mercure
    on topic /chat/{user_id} for real-time multi-device sync.
    """

    def __init__(self):
        self.publisher = MercurePublisher()

    async def ensure_table(self) -> None:
        """Create the agent_message table if it doesn't exist."""
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    async def create(self, user_id: str, role: str, content: str) -> Message:
        async with async_session() as session:
            msg = Message(user_id=user_id, role=role, content=content)
            session.add(msg)
            await session.commit()
            await session.refresh(msg)

        # Auto-publish to Mercure
        try:
            await self.publisher.publish(f"/chat/{user_id}", msg.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish message to Mercure: {e}")

        return msg

    async def find_recent(self, user_id: str, limit: int = 50) -> list[Message]:
        async with async_session() as session:
            result = await session.execute(
                select(Message).where(Message.user_id == user_id).order_by(Message.created_at.desc()).limit(limit)
            )
            messages = list(result.scalars().all())
            messages.reverse()  # chronological order
            return messages

    async def find_after(self, user_id: str, after: str | None = None) -> list[Message]:
        """Find messages after a given ISO timestamp (for incremental sync)."""
        async with async_session() as session:
            query = select(Message).where(Message.user_id == user_id)
            if after:
                query = query.where(Message.created_at > text(f"'{after}'::timestamptz"))
            result = await session.execute(query.order_by(Message.created_at.asc()))
            return list(result.scalars().all())


message_repo = MessageRepository()

import logging

from sqlalchemy import select, text

from app.db.agent_engine import agent_engine, agent_session
from app.db.models import Message
from app.db.proaction_model import AgentBase
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
        async with agent_engine.begin() as conn:
            await conn.run_sync(AgentBase.metadata.create_all)

    async def create(self, user_id: str, role: str, content: str, context_id: str | None = None) -> Message:
        async with agent_session() as session:
            msg = Message(user_id=user_id, role=role, content=content, context_id=context_id)
            session.add(msg)
            await session.commit()
            await session.refresh(msg)

        # Auto-publish to Mercure
        try:
            await self.publisher.publish(f"/chat/{user_id}", msg.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish message to Mercure: {e}")

        return msg

    async def find_recent(self, user_id: str, limit: int = 20) -> list[Message]:
        async with agent_session() as session:
            result = await session.execute(
                select(Message).where(Message.user_id == user_id).order_by(Message.created_at.desc()).limit(limit)
            )
            messages = list(result.scalars().all())
            messages.reverse()  # chronological order
            return messages

    async def find_before(self, user_id: str, before_id: str, limit: int = 20) -> list[Message]:
        """Load messages older than the given message ID (cursor-based pagination)."""
        async with agent_session() as session:
            # First get the target message's created_at
            target = await session.execute(select(Message).where(Message.id == before_id))
            target_msg = target.scalar_one_or_none()
            if target_msg is None:
                return []

            result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.created_at < target_msg.created_at)
                .order_by(Message.created_at.desc())
                .limit(limit)
            )
            messages = list(result.scalars().all())
            messages.reverse()  # chronological order
            return messages

    async def search(self, user_id: str, query: str, limit: int = 20) -> list[Message]:
        """Full-text search on message content using ILIKE."""
        async with agent_session() as session:
            result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.content.ilike(f"%{query}%"))
                .order_by(Message.created_at.desc())
                .limit(limit)
            )
            return list(result.scalars().all())

    async def find_around(self, user_id: str, message_id: str, limit: int = 40) -> dict:
        """Load ~limit/2 messages before and after a target message.

        Returns {"messages": [...], "targetIndex": N}.
        """
        half = limit // 2
        async with agent_session() as session:
            target = await session.execute(select(Message).where(Message.id == message_id))
            target_msg = target.scalar_one_or_none()
            if target_msg is None:
                return {"messages": [], "targetIndex": 0}

            # Messages before (desc, then reverse)
            before_result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.created_at < target_msg.created_at)
                .order_by(Message.created_at.desc())
                .limit(half)
            )
            before = list(before_result.scalars().all())
            before.reverse()

            # Messages after (asc)
            after_result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.created_at > target_msg.created_at)
                .order_by(Message.created_at.asc())
                .limit(half)
            )
            after = list(after_result.scalars().all())

            messages = [*before, target_msg, *after]
            target_index = len(before)

            return {"messages": messages, "targetIndex": target_index}

    async def update_context(self, message_id: str, context_id: str) -> None:
        """Retroactively tag a message with a context ID."""
        async with agent_session() as session:
            result = await session.execute(select(Message).where(Message.id == message_id))
            msg = result.scalar_one_or_none()
            if msg:
                msg.context_id = context_id
                await session.commit()

    async def find_after(self, user_id: str, after: str | None = None) -> list[Message]:
        """Find messages after a given ISO timestamp (for incremental sync)."""
        async with agent_session() as session:
            query = select(Message).where(Message.user_id == user_id)
            if after:
                query = query.where(Message.created_at > text(f"'{after}'::timestamptz"))
            result = await session.execute(query.order_by(Message.created_at.asc()))
            return list(result.scalars().all())


message_repo = MessageRepository()

from datetime import UTC, datetime

from sqlalchemy import func, select

from app.db.agent_engine import agent_session
from app.db.chat_read_state_model import ChatReadState
from app.db.models import Message


class UnknownMessage(Exception):
    """The message is not one of the user's."""


class ChatReadStateRepository:
    """The user's read position in the chat, and the unread count that follows from it (MAG-687)."""

    async def _unread(self, session, user_id: str, last_read: Message | None) -> int:
        query = select(func.count()).select_from(Message).where(Message.user_id == user_id, Message.role == "assistant")
        if last_read is not None:
            query = query.where(Message.created_at > last_read.created_at)
        return int((await session.execute(query)).scalar_one())

    async def _message(self, session, user_id: str, message_id: str | None) -> Message | None:
        if not message_id:
            return None
        result = await session.execute(select(Message).where(Message.id == message_id, Message.user_id == user_id))
        return result.scalar_one_or_none()

    async def get(self, user_id: str) -> dict:
        """`{lastReadMessageId, unreadCount}`; a first read starts at the latest message, so no giant badge."""
        async with agent_session() as session:
            state = (
                await session.execute(select(ChatReadState).where(ChatReadState.user_id == user_id))
            ).scalar_one_or_none()
            if state is None:
                latest = (
                    await session.execute(
                        select(Message).where(Message.user_id == user_id).order_by(Message.created_at.desc()).limit(1)
                    )
                ).scalar_one_or_none()
                state = ChatReadState(user_id=user_id, last_read_message_id=latest.id if latest else None)
                session.add(state)
                await session.commit()
            last_read = await self._message(session, user_id, state.last_read_message_id)
            count = await self._unread(session, user_id, last_read)
            return {"lastReadMessageId": state.last_read_message_id, "unreadCount": count}

    async def mark_read(self, user_id: str, message_id: str) -> dict:
        """Move the position to `message_id` if it is newer than the stored one; never back.

        Raises `UnknownMessage` for an id that is not the user's.
        """
        async with agent_session() as session:
            target = await self._message(session, user_id, message_id)
            if target is None:
                raise UnknownMessage(message_id)
            state = (
                await session.execute(select(ChatReadState).where(ChatReadState.user_id == user_id))
            ).scalar_one_or_none()
            current = await self._message(session, user_id, state.last_read_message_id if state else None)
            if state is None:
                state = ChatReadState(user_id=user_id)
                session.add(state)
            if current is None or target.created_at > current.created_at:
                state.last_read_message_id = target.id
                state.updated_at = datetime.now(UTC)
                current = target
            await session.commit()
            return {
                "lastReadMessageId": state.last_read_message_id,
                "unreadCount": await self._unread(session, user_id, current),
            }


chat_read_state_repo = ChatReadStateRepository()

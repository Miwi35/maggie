from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, String

from app.db.base import AgentBase


class ChatReadState(AgentBase):
    """How far a user has read the chat (MAG-687): one position per user, shared by web and phone."""

    __tablename__ = "agent_chat_read_state"

    user_id = Column(String(36), primary_key=True)
    # The last message the user has seen. Not a foreign key: a deleted message must not block the row.
    last_read_message_id = Column(String(26), nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))

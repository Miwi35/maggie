import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Index, String, Text

from app.db.proaction_model import AgentBase


class Message(AgentBase):
    """Chat message — owned by the agent, stored in the agent database."""

    __tablename__ = "agent_message"
    __table_args__ = (Index("idx_agent_message_user_created", "user_id", "created_at"),)

    id = Column(String(26), primary_key=True, default=lambda: str(uuid.uuid4().hex[:26]))
    user_id = Column(String(36), nullable=False)
    role = Column(String(20), nullable=False)  # user, assistant
    content = Column(Text, nullable=False)
    context_id = Column(String(32), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "role": self.role,
            "content": self.content,
            "contextId": self.context_id,
            "createdAt": self.created_at.isoformat() if self.created_at else "",
        }

import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Index, String, Text

from app.db.proaction_model import AgentBase

# The state of the turn answering a user's message (MAG-344). Empty on everything else: an
# assistant message, a message answered, one stored before the column existed.
TURN_RUNNING = "running"
TURN_EXPIRED = "expired"


class Message(AgentBase):
    """Chat message — owned by the agent, stored in the agent database."""

    __tablename__ = "agent_message"
    __table_args__ = (
        Index("idx_agent_message_user_created", "user_id", "created_at"),
        Index("idx_agent_message_client_key", "user_id", "client_key", unique=True),
    )

    id = Column(String(26), primary_key=True, default=lambda: str(uuid.uuid4().hex[:26]))
    user_id = Column(String(36), nullable=False)
    role = Column(String(20), nullable=False)  # user, assistant
    content = Column(Text, nullable=False)
    context_id = Column(String(32), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    # The client's idempotency key: the same key from the same user is the same message.
    client_key = Column(String(64), nullable=True)
    # `running` while a turn answers this message, `expired` once it was left too long
    # unanswered to be answered at all. The lease says which process is on it: a running
    # turn whose lease has lapsed belongs to a process that is gone.
    turn_status = Column(String(16), nullable=True)
    turn_lease_until = Column(DateTime(timezone=True), nullable=True)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "role": self.role,
            "content": self.content,
            "contextId": self.context_id,
            "createdAt": self.created_at.isoformat() if self.created_at else "",
        }

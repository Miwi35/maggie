import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Enum, Index, String, Text
from sqlalchemy.dialects.postgresql import JSONB

from app.db.proaction_model import AgentBase


class ContextStatus(enum.StrEnum):
    ACTIVE = "active"
    DORMANT = "dormant"
    CLOSED = "closed"


class ConversationContext(AgentBase):
    """A conversation topic/context managed by the agent."""

    __tablename__ = "conversation_context"
    __table_args__ = (Index("idx_context_user_status", "user_id", "status"),)

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    label = Column(String(200), nullable=False)
    status = Column(
        Enum(ContextStatus, native_enum=False, length=20),
        nullable=False,
        default=ContextStatus.ACTIVE,
    )
    tool_calls_log = Column(JSONB, nullable=False, default=list)
    # What the thread is about, in a few lines, rewritten by the fast model as the
    # thread grows (MAG-11). `summary_updated_at` is not decoration: it is where the
    # summary stops, so the next pass only has to read the messages after it.
    summary = Column(Text, nullable=True)
    summary_updated_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    updated_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    closed_at = Column(DateTime(timezone=True), nullable=True)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "userId": self.user_id,
            "label": self.label,
            "status": self.status.value if self.status else None,
            "toolCallsLog": self.tool_calls_log or [],
            "summary": self.summary,
            "summaryUpdatedAt": self.summary_updated_at.isoformat() if self.summary_updated_at else None,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "updatedAt": self.updated_at.isoformat() if self.updated_at else None,
            "closedAt": self.closed_at.isoformat() if self.closed_at else None,
        }

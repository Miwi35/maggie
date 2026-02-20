import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Enum, Index, String, Text
from sqlalchemy.orm import DeclarativeBase


class AgentBase(DeclarativeBase):
    """Base class for agent-owned models (stored in maggie_agent DB)."""

    pass


class ProactionStatus(enum.StrEnum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


class Proaction(AgentBase):
    """An autonomous scheduled task for the agent."""

    __tablename__ = "proaction"
    __table_args__ = (
        Index("idx_proaction_user", "user_id"),
        Index("idx_proaction_status_scheduled", "status", "scheduled_at"),
    )

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    prompt = Column(Text, nullable=False)
    status = Column(
        Enum(ProactionStatus, native_enum=False, length=20),
        nullable=False,
        default=ProactionStatus.PENDING,
    )
    scheduled_at = Column(DateTime(timezone=True), nullable=False)
    response = Column(Text, nullable=True)
    error = Column(Text, nullable=True)
    created_at = Column(
        DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC)
    )
    completed_at = Column(DateTime(timezone=True), nullable=True)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "userId": self.user_id,
            "prompt": self.prompt,
            "status": self.status.value if self.status else None,
            "scheduledAt": self.scheduled_at.isoformat() if self.scheduled_at else None,
            "response": self.response,
            "error": self.error,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "completedAt": self.completed_at.isoformat() if self.completed_at else None,
        }

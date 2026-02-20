import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Enum, Index, String, Text
from sqlalchemy.dialects.postgresql import JSON

from app.db.proaction_model import AgentBase


class MemoryType(enum.StrEnum):
    FACTUAL = "factual"
    EPISODIC = "episodic"


class Memory(AgentBase):
    """A persistent memory entry owned by the agent."""

    __tablename__ = "memory"
    __table_args__ = (
        Index("idx_memory_user", "user_id"),
        Index("idx_memory_user_type", "user_id", "type"),
    )

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    type = Column(
        Enum(MemoryType, native_enum=False, length=20),
        nullable=False,
        default=MemoryType.FACTUAL,
    )
    content = Column(Text, nullable=False)
    metadata_ = Column("metadata", JSON, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "userId": self.user_id,
            "type": self.type.value if self.type else None,
            "content": self.content,
            "metadata": self.metadata_,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "updatedAt": self.updated_at.isoformat() if self.updated_at else None,
        }

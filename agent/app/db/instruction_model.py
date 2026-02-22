import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, Index, String, Text

from app.db.proaction_model import AgentBase


class Instruction(AgentBase):
    """A proaction guideline stored by the user."""

    __tablename__ = "instruction"
    __table_args__ = (Index("idx_instruction_user", "user_id"),)

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String(36), nullable=False)
    content = Column(Text, nullable=False)
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
            "content": self.content,
            "createdAt": self.created_at.isoformat() if self.created_at else None,
            "updatedAt": self.updated_at.isoformat() if self.updated_at else None,
        }

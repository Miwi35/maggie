import uuid
from datetime import UTC, datetime

from sqlalchemy import Column, DateTime, String, Text
from sqlalchemy.dialects.postgresql import JSON

from app.db.proaction_model import AgentBase


class Skill(AgentBase):
    """A procedure taught to Maggie — global, shared by all users (see agent-os/standards/agent/architecture.md)."""

    __tablename__ = "skill"

    id = Column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    name = Column(String(200), nullable=False, unique=True)
    description = Column(Text, nullable=False, default="")
    tags = Column(JSON, nullable=False, default=list)
    content = Column(Text, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(UTC))
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )

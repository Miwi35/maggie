from sqlalchemy import Column, DateTime, Integer, String

from app.db.base import AgentBase

SINGLE_ROW = 1


class ProviderState(AgentBase):
    """Whether the model's provider answers (MAG-727): one row for the whole Anthropic account."""

    __tablename__ = "agent_provider_state"

    id = Column(Integer, primary_key=True, default=SINGLE_ROW)
    status = Column(String(16), nullable=False, default="ok")
    since = Column(DateTime(timezone=True), nullable=True)

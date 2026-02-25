from sqlalchemy import Column, String, Text

from app.db.proaction_model import AgentBase


class PersonalityConfig(AgentBase):
    """Per-user personality settings (stored in maggie_agent DB)."""

    __tablename__ = "agent_personality"

    user_id = Column(String(36), primary_key=True)
    name = Column(String(100), nullable=False, default="Maggie")
    language = Column(String(10), nullable=False, default="fr")
    backstory = Column(Text, nullable=False, default="")

from sqlalchemy import Column, String

from app.db.base import AgentBase


class UserSetting(AgentBase):
    """Per-user agent settings (stored in maggie_agent DB)."""

    __tablename__ = "agent_user_setting"

    user_id = Column(String(36), primary_key=True)
    tts_voice = Column(String(80), nullable=True)

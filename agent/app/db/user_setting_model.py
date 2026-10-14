from sqlalchemy import Column, DateTime, String

from app.db.base import AgentBase


class UserSetting(AgentBase):
    """Per-user agent settings (stored in maggie_agent DB)."""

    __tablename__ = "agent_user_setting"

    user_id = Column(String(36), primary_key=True)
    tts_voice = Column(String(80), nullable=True)
    # Up to when the style synthesis has read this user's thumbs (MAG-23): the next one only
    # reads what was rated since.
    style_synthesized_at = Column(DateTime(timezone=True), nullable=True)

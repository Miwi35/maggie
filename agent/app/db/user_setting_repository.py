from datetime import datetime

from sqlalchemy import select

from app.db.agent_engine import agent_engine, agent_session
from app.db.base import AgentBase
from app.db.user_setting_model import UserSetting


class UserSettingRepository:
    async def ensure_table(self) -> None:
        async with agent_engine.begin() as conn:
            await conn.run_sync(AgentBase.metadata.create_all)

    async def get(self, user_id: str) -> UserSetting | None:
        async with agent_session() as session:
            result = await session.execute(select(UserSetting).where(UserSetting.user_id == user_id))
            return result.scalar_one_or_none()

    async def set_tts_voice(self, user_id: str, voice: str) -> UserSetting:
        async with agent_session() as session:
            result = await session.execute(select(UserSetting).where(UserSetting.user_id == user_id))
            setting = result.scalar_one_or_none()
            if setting is None:
                setting = UserSetting(user_id=user_id, tts_voice=voice)
                session.add(setting)
            else:
                setting.tts_voice = voice
            await session.commit()
            await session.refresh(setting)
            return setting


    async def set_style_synthesized_at(self, user_id: str, until: datetime) -> None:
        """Record up to when the style synthesis has read this user's thumbs (MAG-23)."""
        async with agent_session() as session:
            result = await session.execute(select(UserSetting).where(UserSetting.user_id == user_id))
            setting = result.scalar_one_or_none()
            if setting is None:
                session.add(UserSetting(user_id=user_id, style_synthesized_at=until))
            else:
                setting.style_synthesized_at = until
            await session.commit()


user_setting_repo = UserSettingRepository()

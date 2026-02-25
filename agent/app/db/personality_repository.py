from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.personality_model import PersonalityConfig


class PersonalityRepository:
    async def get(self, user_id: str) -> PersonalityConfig | None:
        async with agent_session() as session:
            result = await session.execute(select(PersonalityConfig).where(PersonalityConfig.user_id == user_id))
            return result.scalar_one_or_none()

    async def upsert(self, user_id: str, data: dict) -> PersonalityConfig:
        async with agent_session() as session:
            result = await session.execute(select(PersonalityConfig).where(PersonalityConfig.user_id == user_id))
            row = result.scalar_one_or_none()
            if row is None:
                row = PersonalityConfig(user_id=user_id, **data)
                session.add(row)
            else:
                for key, value in data.items():
                    setattr(row, key, value)
            await session.commit()
            await session.refresh(row)
            return row


personality_repo = PersonalityRepository()

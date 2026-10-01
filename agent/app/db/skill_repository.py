import logging
from datetime import UTC, datetime

from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.skill_model import Skill

logger = logging.getLogger(__name__)


class SkillRepository:
    """Async skill persistence in the agent-owned DB (`maggie_agent`)."""

    async def list_all(self) -> list[Skill]:
        async with agent_session() as session:
            result = await session.execute(select(Skill).order_by(Skill.name))
            return list(result.scalars().all())

    async def get(self, name: str) -> Skill | None:
        async with agent_session() as session:
            result = await session.execute(select(Skill).where(Skill.name == name))
            return result.scalar_one_or_none()

    async def upsert(self, name: str, description: str, tags: list[str], content: str) -> Skill:
        async with agent_session() as session:
            result = await session.execute(select(Skill).where(Skill.name == name))
            skill = result.scalar_one_or_none()
            if skill is None:
                skill = Skill(name=name, description=description, tags=tags, content=content)
                session.add(skill)
            else:
                skill.description = description
                skill.tags = tags
                skill.content = content
                skill.updated_at = datetime.now(UTC)
            await session.commit()
            await session.refresh(skill)
            return skill

    async def update(
        self,
        name: str,
        description: str | None = None,
        tags: list[str] | None = None,
        content: str | None = None,
    ) -> Skill | None:
        """Apply only the given fields; None if the skill does not exist."""
        async with agent_session() as session:
            result = await session.execute(select(Skill).where(Skill.name == name))
            skill = result.scalar_one_or_none()
            if skill is None:
                return None
            if description is not None:
                skill.description = description
            if tags is not None:
                skill.tags = tags
            if content is not None:
                skill.content = content
            skill.updated_at = datetime.now(UTC)
            await session.commit()
            await session.refresh(skill)
            return skill

    async def delete(self, name: str) -> bool:
        async with agent_session() as session:
            result = await session.execute(select(Skill).where(Skill.name == name))
            skill = result.scalar_one_or_none()
            if skill is None:
                return False
            await session.delete(skill)
            await session.commit()
            return True


skill_repo = SkillRepository()

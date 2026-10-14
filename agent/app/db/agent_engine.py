from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker

from app.config import settings

agent_engine = create_async_engine(settings.async_agent_database_url, echo=False)
agent_session = sessionmaker(agent_engine, class_=AsyncSession, expire_on_commit=False)


async def ensure_agent_tables() -> None:
    """Create the agent's tables that do not exist yet; the models must have been imported."""
    from app.db.base import AgentBase

    async with agent_engine.begin() as conn:
        await conn.run_sync(AgentBase.metadata.create_all)

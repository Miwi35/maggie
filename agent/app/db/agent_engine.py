from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker

from app.config import settings

agent_engine = create_async_engine(settings.async_agent_database_url, echo=False)
agent_session = sessionmaker(agent_engine, class_=AsyncSession, expire_on_commit=False)

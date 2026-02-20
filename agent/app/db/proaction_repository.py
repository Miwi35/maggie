import logging
from datetime import UTC, datetime

from sqlalchemy import select

from app.db.agent_engine import agent_engine, agent_session
from app.db.proaction_model import AgentBase, Proaction, ProactionStatus
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class ProactionRepository:
    """Async proaction persistence in agent-owned DB + Mercure auto-publish."""

    def __init__(self):
        self.publisher = MercurePublisher()

    async def ensure_table(self) -> None:
        """Create the proaction table if it doesn't exist."""
        async with agent_engine.begin() as conn:
            await conn.run_sync(AgentBase.metadata.create_all)

    async def create(self, user_id: str, prompt: str, scheduled_at: datetime) -> Proaction:
        async with agent_session() as session:
            proaction = Proaction(
                user_id=user_id,
                prompt=prompt,
                scheduled_at=scheduled_at,
            )
            session.add(proaction)
            await session.commit()
            await session.refresh(proaction)

        try:
            await self.publisher.publish(f"/proactions/{user_id}", proaction.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish proaction to Mercure: {e}")

        return proaction

    async def find_due(self) -> list[Proaction]:
        """Find all pending proactions whose scheduled_at is in the past."""
        now = datetime.now(UTC)
        async with agent_session() as session:
            result = await session.execute(
                select(Proaction)
                .where(Proaction.status == ProactionStatus.PENDING)
                .where(Proaction.scheduled_at <= now)
                .order_by(Proaction.scheduled_at.asc())
            )
            return list(result.scalars().all())

    async def find_by_user(self, user_id: str) -> list[Proaction]:
        """Find all proactions for a given user, most recent first."""
        async with agent_session() as session:
            result = await session.execute(
                select(Proaction).where(Proaction.user_id == user_id).order_by(Proaction.created_at.desc())
            )
            return list(result.scalars().all())

    async def get(self, proaction_id: str) -> Proaction | None:
        async with agent_session() as session:
            result = await session.execute(select(Proaction).where(Proaction.id == proaction_id))
            return result.scalar_one_or_none()

    async def mark_running(self, proaction_id: str) -> None:
        async with agent_session() as session:
            result = await session.execute(select(Proaction).where(Proaction.id == proaction_id))
            proaction = result.scalar_one_or_none()
            if proaction:
                proaction.status = ProactionStatus.RUNNING
                await session.commit()

    async def mark_completed(self, proaction_id: str, response: str) -> None:
        async with agent_session() as session:
            result = await session.execute(select(Proaction).where(Proaction.id == proaction_id))
            proaction = result.scalar_one_or_none()
            if proaction:
                proaction.status = ProactionStatus.COMPLETED
                proaction.response = response
                proaction.completed_at = datetime.now(UTC)
                await session.commit()
                await session.refresh(proaction)

        if proaction:
            try:
                await self.publisher.publish(f"/proactions/{proaction.user_id}", proaction.to_dict())
            except Exception as e:
                logger.warning(f"Failed to publish proaction update: {e}")

    async def mark_failed(self, proaction_id: str, error: str) -> None:
        async with agent_session() as session:
            result = await session.execute(select(Proaction).where(Proaction.id == proaction_id))
            proaction = result.scalar_one_or_none()
            if proaction:
                proaction.status = ProactionStatus.FAILED
                proaction.error = error
                proaction.completed_at = datetime.now(UTC)
                await session.commit()
                await session.refresh(proaction)

        if proaction:
            try:
                await self.publisher.publish(f"/proactions/{proaction.user_id}", proaction.to_dict())
            except Exception as e:
                logger.warning(f"Failed to publish proaction update: {e}")


proaction_repo = ProactionRepository()

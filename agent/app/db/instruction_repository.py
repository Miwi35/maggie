import logging
from datetime import UTC, datetime

from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.instruction_model import Instruction
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class InstructionRepository:
    """Async instruction persistence in agent-owned DB + Mercure auto-publish."""

    def __init__(self):
        self.publisher = MercurePublisher()

    async def store(self, user_id: str, content: str) -> Instruction:
        async with agent_session() as session:
            instruction = Instruction(user_id=user_id, content=content)
            session.add(instruction)
            await session.commit()
            await session.refresh(instruction)

        try:
            await self.publisher.publish(f"/instructions/{user_id}", instruction.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish instruction to Mercure: {e}")

        return instruction

    async def find_by_user(self, user_id: str) -> list[Instruction]:
        """Get all instructions for a user, most recent first."""
        async with agent_session() as session:
            result = await session.execute(
                select(Instruction).where(Instruction.user_id == user_id).order_by(Instruction.created_at.desc())
            )
            return list(result.scalars().all())

    async def update(self, user_id: str, instruction_id: str, content: str) -> Instruction | None:
        """Update an instruction owned by user_id; None if it does not exist or belongs to someone else."""
        async with agent_session() as session:
            result = await session.execute(
                select(Instruction).where(Instruction.id == instruction_id, Instruction.user_id == user_id)
            )
            instruction = result.scalar_one_or_none()
            if instruction:
                instruction.content = content
                instruction.updated_at = datetime.now(UTC)
                await session.commit()
                await session.refresh(instruction)

        if instruction:
            try:
                await self.publisher.publish(f"/instructions/{instruction.user_id}", instruction.to_dict())
            except Exception as e:
                logger.warning(f"Failed to publish instruction update: {e}")

        return instruction

    async def delete(self, user_id: str, instruction_id: str) -> bool:
        """Delete an instruction owned by user_id; False if it does not exist or belongs to someone else."""
        async with agent_session() as session:
            result = await session.execute(
                select(Instruction).where(Instruction.id == instruction_id, Instruction.user_id == user_id)
            )
            instruction = result.scalar_one_or_none()
            if not instruction:
                return False
            inst_id = instruction.id
            await session.delete(instruction)
            await session.commit()

        try:
            await self.publisher.publish(f"/instructions/{user_id}", {"id": inst_id, "deleted": True})
        except Exception as e:
            logger.warning(f"Failed to publish instruction deletion: {e}")

        return True


instruction_repo = InstructionRepository()

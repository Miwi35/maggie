from datetime import UTC, datetime

from sqlalchemy import select

from app.db.agent_engine import agent_session
from app.db.style_proposal_model import StyleProposal, StyleProposalStatus


class StyleProposalRepository:
    """The directives the style synthesis proposes, and the owner's answer to each (MAG-23)."""

    async def store(self, user_id: str, content: str, reason: str | None = None) -> StyleProposal:
        async with agent_session() as session:
            proposal = StyleProposal(user_id=user_id, content=content, reason=reason)
            session.add(proposal)
            await session.commit()
            await session.refresh(proposal)
            return proposal

    async def get(self, user_id: str, proposal_id: str) -> StyleProposal | None:
        """One of the user's proposals; `None` for another user's, like for an unknown id."""
        async with agent_session() as session:
            result = await session.execute(
                select(StyleProposal).where(StyleProposal.id == proposal_id, StyleProposal.user_id == user_id)
            )
            return result.scalar_one_or_none()

    async def find_by_user(self, user_id: str, status: StyleProposalStatus | None = None) -> list[StyleProposal]:
        """The user's proposals, newest first — of one status, or all of them."""
        async with agent_session() as session:
            query = select(StyleProposal).where(StyleProposal.user_id == user_id)
            if status is not None:
                query = query.where(StyleProposal.status == status)
            result = await session.execute(query.order_by(StyleProposal.created_at.desc()))
            return list(result.scalars().all())

    async def decide(
        self, user_id: str, proposal_id: str, status: StyleProposalStatus, content: str | None = None
    ) -> StyleProposal | None:
        """Accept or refuse a pending proposal; `None` if it is not the user's or no longer pending.

        `content` is the wording the owner settled on, when they reworded it before accepting.
        """
        async with agent_session() as session:
            result = await session.execute(
                select(StyleProposal).where(
                    StyleProposal.id == proposal_id,
                    StyleProposal.user_id == user_id,
                    StyleProposal.status == StyleProposalStatus.PENDING,
                )
            )
            proposal = result.scalar_one_or_none()
            if proposal is None:
                return None
            proposal.status = status
            proposal.decided_at = datetime.now(UTC)
            if content:
                proposal.content = content
            await session.commit()
            await session.refresh(proposal)
            return proposal

    async def reopen(self, user_id: str, proposal_id: str) -> None:
        """Put a decided proposal back to pending — an acceptance whose directive could not be written."""
        async with agent_session() as session:
            result = await session.execute(
                select(StyleProposal).where(StyleProposal.id == proposal_id, StyleProposal.user_id == user_id)
            )
            proposal = result.scalar_one_or_none()
            if proposal is not None:
                proposal.status = StyleProposalStatus.PENDING
                proposal.decided_at = None
                await session.commit()


style_proposal_repo = StyleProposalRepository()

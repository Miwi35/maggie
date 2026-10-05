import logging
from datetime import UTC, datetime

from sqlalchemy import select, update

from app.db.agent_engine import agent_session
from app.db.pending_action_model import PendingAction, PendingActionStatus
from app.mercure import topics
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)

# What the user may answer. `expired` and `failed` are written by the agent, not chosen.
DECIDABLE = (PendingActionStatus.APPROVED, PendingActionStatus.DENIED, PendingActionStatus.FAILED)


class PendingActionRepository:
    """The actions waiting for the user's answer, and their echo on Mercure.

    Every change is published on the user's `approvals` topic, privately — the
    web and the phone both show the card, so whichever surface answers it, the
    other has to stop offering it. The table itself is created by the shared
    `create_all` (see `main.py`, which imports the model for that).
    """

    def __init__(self):
        self.publisher = MercurePublisher()

    async def _publish(self, action: PendingAction) -> None:
        try:
            await self.publisher.publish(topics.for_user(topics.APPROVALS, str(action.user_id)), action.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish pending action to Mercure: {e}")

    async def create(
        self,
        user_id: str,
        tool_name: str,
        arguments: dict,
        *,
        source: str,
        context_id: str | None = None,
    ) -> PendingAction:
        """Hold a call back, or hand back the identical one already waiting.

        The model is told « do not retry », but a new turn asking for the same
        deletion again is not a retry — and two cards for one intention would
        make the user answer twice, the second answer running the call twice.

        Read-then-insert, so two identical `ask` calls landing in the same instant
        (a chat turn and a proaction) can still produce two cards. What makes a
        double execution impossible is `decide` claiming its row, not this.
        """
        existing = await self.find_identical_pending(user_id, tool_name, arguments)
        if existing is not None:
            logger.info(f"Pending action {existing.id} reused for {tool_name}")
            return existing

        async with agent_session() as session:
            action = PendingAction(
                user_id=user_id,
                tool_name=tool_name,
                arguments=arguments or {},
                source=source,
                context_id=context_id,
            )
            session.add(action)
            await session.commit()
            await session.refresh(action)

        logger.info(f"Pending action {action.id} created for {tool_name} (source={source})")
        await self._publish(action)
        return action

    async def find_identical_pending(self, user_id: str, tool_name: str, arguments: dict) -> PendingAction | None:
        """The user's live action for exactly this call, if there is one.

        The arguments are compared in Python: they are `JSONB` in Postgres and plain
        `JSON` on the SQLite the tests run on, and equality on a JSON column does not
        mean the same thing in both.
        """
        now = datetime.now(UTC)
        async with agent_session() as session:
            result = await session.execute(
                select(PendingAction).where(
                    PendingAction.user_id == user_id,
                    PendingAction.tool_name == tool_name,
                    PendingAction.status == PendingActionStatus.PENDING,
                    PendingAction.expires_at > now,
                )
            )
            wanted = arguments or {}
            return next((a for a in result.scalars().all() if (a.arguments or {}) == wanted), None)

    async def get_for_user(self, user_id: str, action_id: str) -> PendingAction | None:
        """One action, read with its owner in the query — another user's id simply finds nothing."""
        async with agent_session() as session:
            result = await session.execute(
                select(PendingAction).where(PendingAction.id == action_id, PendingAction.user_id == user_id)
            )
            return result.scalar_one_or_none()

    async def find_pending(self, user_id: str) -> list[PendingAction]:
        """The user's unanswered actions, oldest first — the order the cards are shown in."""
        async with agent_session() as session:
            result = await session.execute(
                select(PendingAction)
                .where(PendingAction.user_id == user_id, PendingAction.status == PendingActionStatus.PENDING)
                .order_by(PendingAction.created_at.asc())
            )
            return list(result.scalars().all())

    async def decide(
        self,
        action_id: str,
        status: PendingActionStatus,
        result: str | None = None,
    ) -> PendingAction | None:
        """Claim the action and write the answer. None if it is gone or no longer pending.

        One conditional UPDATE, like `ProactionRepository.claim`: two tabs clicking
        Autoriser at the same instant, or the scheduler expiring the action while the
        user answers it, must not both get a decision — the second would run the call
        a second time. Read-then-write would leave that window open, and the process
        runs with several workers.
        """
        if status not in DECIDABLE:
            raise ValueError(f"A decision is one of {[s.value for s in DECIDABLE]}, got {status!r}")

        async with agent_session() as session:
            claimed = await session.execute(
                update(PendingAction)
                .where(PendingAction.id == action_id, PendingAction.status == PendingActionStatus.PENDING)
                .values(status=status, result=result, decided_at=datetime.now(UTC))
            )
            await session.commit()
            if claimed.rowcount != 1:
                return None

            found = await session.execute(select(PendingAction).where(PendingAction.id == action_id))
            action = found.scalar_one()

        await self._publish(action)
        return action

    async def expire_overdue(self) -> int:
        """Retire the actions nobody answered in time, and tell the clients so the cards go.

        Called from the scheduler's execution loop. Each one is published, which is
        why it reads the rows instead of a bulk UPDATE: a card left on screen for a
        dead action is one the user clicks for nothing. Two workers sweeping at once
        may publish the same `expired` twice, which costs a client one redundant
        event — and never an execution, since `decide` claims its row.
        """
        now = datetime.now(UTC)
        async with agent_session() as session:
            found = await session.execute(
                select(PendingAction).where(
                    PendingAction.status == PendingActionStatus.PENDING, PendingAction.expires_at <= now
                )
            )
            overdue = list(found.scalars().all())
            for action in overdue:
                action.status = PendingActionStatus.EXPIRED
                action.decided_at = now
            if overdue:
                await session.commit()
                for action in overdue:
                    await session.refresh(action)

        for action in overdue:
            await self._publish(action)
        if overdue:
            logger.info(f"Expired {len(overdue)} pending action(s)")
        return len(overdue)


pending_action_repo = PendingActionRepository()

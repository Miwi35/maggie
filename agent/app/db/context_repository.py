import logging
from datetime import UTC, datetime

from sqlalchemy import delete, select, text

from app.db.agent_engine import agent_engine, agent_session
from app.db.context_model import ContextStatus, ConversationContext
from app.mercure import topics
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class ContextRepository:
    """Async context persistence with Mercure auto-publishing."""

    def __init__(self):
        self.publisher = MercurePublisher()

    async def run_migrations(self) -> None:
        """Add the columns `create_all` cannot add, since it only ever creates whole tables.

        Every one of these is `IF NOT EXISTS` and runs on every boot: the agent database
        has no migration tool, so this function *is* the migration history.
        """
        async with agent_engine.begin() as conn:
            await conn.execute(text("ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS context_id VARCHAR(32)"))
            # MAG-11 — the thread's summary, and where it stops.
            await conn.execute(text("ALTER TABLE conversation_context ADD COLUMN IF NOT EXISTS summary TEXT"))
            await conn.execute(
                text("ALTER TABLE conversation_context ADD COLUMN IF NOT EXISTS summary_updated_at TIMESTAMPTZ")
            )
            # MAG-22 — what a directive is for. Everything stored before this column
            # existed was written as a planning rule, which is what the default says.
            await conn.execute(
                text("ALTER TABLE instruction ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'planning'")
            )
            # MAG-7 — the readable sentence of a held action. Null on the older ones.
            await conn.execute(text("ALTER TABLE agent_pending_action ADD COLUMN IF NOT EXISTS summary TEXT"))
            # MAG-344 — the idempotency key of a message, and the turn answering it.
            await conn.execute(text("ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS client_key VARCHAR(64)"))
            await conn.execute(text("ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS turn_status VARCHAR(16)"))
            await conn.execute(text("ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS turn_lease_until TIMESTAMPTZ"))
            await conn.execute(text("ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS turn_screen_context TEXT"))
            await conn.execute(
                text(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_message_client_key "
                    "ON agent_message (user_id, client_key)"
                )
            )

    async def create(self, user_id: str, label: str) -> ConversationContext:
        async with agent_session() as session:
            ctx = ConversationContext(user_id=user_id, label=label)
            session.add(ctx)
            await session.commit()
            await session.refresh(ctx)

        try:
            await self.publisher.publish(topics.for_user(topics.CONTEXTS, user_id), ctx.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish context to Mercure: {e}")

        return ctx

    async def update_status(
        self, context_id: str, status: ContextStatus, idle_before: datetime | None = None
    ) -> ConversationContext | None:
        """Move a context to `status`, and tell the Mind panel.

        `idle_before` is for the lifecycle (MAG-12): the context only moves if nobody has
        spoken in it since that instant. The final summary is a model call that takes
        seconds, and a message routed into the thread meanwhile must not be put to sleep.
        Returns `None` when the context is gone or has been spoken in since.
        """
        async with agent_session() as session:
            query = select(ConversationContext).where(ConversationContext.id == context_id)
            if idle_before is not None:
                query = query.where(ConversationContext.updated_at < idle_before)
            # Locked, so a `touch` committing meanwhile is waited for and then fails the
            # `idle_before` check instead of being overwritten (a no-op on SQLite).
            ctx = (await session.execute(query.with_for_update())).scalar_one_or_none()
            if ctx is None:
                return None

            # `updated_at` stays: it is when the thread was last spoken in, and the close
            # threshold, the router's ranking and the panel all read it as that.
            ctx.status = status
            if status == ContextStatus.CLOSED:
                ctx.closed_at = datetime.now(UTC)
            await session.commit()
            await session.refresh(ctx)

        try:
            await self.publisher.publish(topics.for_user(topics.CONTEXTS, ctx.user_id), ctx.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish context update to Mercure: {e}")

        return ctx

    async def touch(self, context_id: str) -> ConversationContext | None:
        """A message was routed into this context: it was just spoken in, and it is awake (MAG-12).

        Resets the idle clock the lifecycle reads, and brings a dormant context back to
        active. A closed one is reopened too: the scheduler can close a thread between the
        router listing it and this call, and the message is already on its way in.

        Only a change of status is published — the stream that routed the message already
        told the panel about the thread, and a publication per message would be noise.
        """
        async with agent_session() as session:
            # Locked like the lifecycle's transition, so the status read here is the one that
            # will be overwritten: a stale "active" would leave a sleeping thread asleep.
            result = await session.execute(
                select(ConversationContext).where(ConversationContext.id == context_id).with_for_update()
            )
            ctx = result.scalar_one_or_none()
            if ctx is None:
                return None

            woke = ctx.status != ContextStatus.ACTIVE
            ctx.status = ContextStatus.ACTIVE
            ctx.closed_at = None
            ctx.updated_at = datetime.now(UTC)
            await session.commit()
            await session.refresh(ctx)

        if woke:
            try:
                await self.publisher.publish(topics.for_user(topics.CONTEXTS, ctx.user_id), ctx.to_dict())
            except Exception as e:
                logger.warning(f"Failed to publish context wake-up to Mercure: {e}")

        return ctx

    async def find_idle(self, statuses: list[ContextStatus], before: datetime) -> list[ConversationContext]:
        """The contexts of every user, in one of `statuses`, nobody has spoken in since `before` (MAG-12)."""
        async with agent_session() as session:
            result = await session.execute(
                select(ConversationContext)
                .where(ConversationContext.status.in_(statuses), ConversationContext.updated_at < before)
                .order_by(ConversationContext.updated_at.asc())
            )
            return list(result.scalars().all())

    async def set_summary(
        self, context_id: str, summary: str, covers_up_to: datetime | None = None
    ) -> ConversationContext | None:
        """Store a thread's summary and the instant it covers up to (MAG-11).

        `covers_up_to` is the last message the summary was written from, not the moment
        it was written: the model call takes a second, and the summary fires exactly when
        the user is likely to be typing again. Stamping "now" would mark a message that
        arrived mid-call as covered, and every later pass would skip it. `None` means now,
        for a caller with no message to point at.

        `updated_at` is deliberately left alone: a summary is written *about* the
        conversation, not *in* it, and the context router ranks contexts by how recently
        they were spoken in. Stamping it here would make a summarized thread look like
        the freshest one.
        """
        async with agent_session() as session:
            result = await session.execute(select(ConversationContext).where(ConversationContext.id == context_id))
            ctx = result.scalar_one_or_none()
            if ctx is None:
                return None

            ctx.summary = summary
            ctx.summary_updated_at = covers_up_to or datetime.now(UTC)
            await session.commit()
            await session.refresh(ctx)

        # The summary is written in the background, after the stream the user was
        # watching has closed — so this publication is the only thing that puts it on an
        # open Mind panel.
        try:
            await self.publisher.publish(topics.for_user(topics.CONTEXTS, ctx.user_id), ctx.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish context summary to Mercure: {e}")

        return ctx

    async def append_tool_call(self, context_id: str, tool_call: dict) -> None:
        async with agent_session() as session:
            result = await session.execute(select(ConversationContext).where(ConversationContext.id == context_id))
            ctx = result.scalar_one_or_none()
            if ctx is None:
                return

            log = list(ctx.tool_calls_log or [])
            log.append(tool_call)
            ctx.tool_calls_log = log
            ctx.updated_at = datetime.now(UTC)
            await session.commit()

    async def find_active(self, user_id: str) -> list[ConversationContext]:
        """Return active + dormant contexts (for Mind Panel)."""
        async with agent_session() as session:
            result = await session.execute(
                select(ConversationContext)
                .where(
                    ConversationContext.user_id == user_id,
                    ConversationContext.status.in_([ContextStatus.ACTIVE, ContextStatus.DORMANT]),
                )
                .order_by(ConversationContext.updated_at.desc())
            )
            return list(result.scalars().all())

    async def find_by_user(self, user_id: str) -> list[ConversationContext]:
        async with agent_session() as session:
            result = await session.execute(
                select(ConversationContext)
                .where(ConversationContext.user_id == user_id)
                .order_by(ConversationContext.created_at.desc())
            )
            return list(result.scalars().all())

    async def delete_by_user(self, user_id: str) -> int:
        """Remove every thread of one user, and return how many there were."""
        async with agent_session() as session:
            result = await session.execute(delete(ConversationContext).where(ConversationContext.user_id == user_id))
            await session.commit()
            return result.rowcount

    async def get(self, context_id: str) -> ConversationContext | None:
        async with agent_session() as session:
            result = await session.execute(select(ConversationContext).where(ConversationContext.id == context_id))
            return result.scalar_one_or_none()


context_repo = ContextRepository()

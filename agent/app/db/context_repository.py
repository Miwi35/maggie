import logging
from datetime import UTC, datetime

from sqlalchemy import select, text

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

    async def update_status(self, context_id: str, status: ContextStatus) -> ConversationContext | None:
        async with agent_session() as session:
            result = await session.execute(select(ConversationContext).where(ConversationContext.id == context_id))
            ctx = result.scalar_one_or_none()
            if ctx is None:
                return None

            ctx.status = status
            ctx.updated_at = datetime.now(UTC)
            if status == ContextStatus.CLOSED:
                ctx.closed_at = datetime.now(UTC)
            await session.commit()
            await session.refresh(ctx)

        try:
            await self.publisher.publish(topics.for_user(topics.CONTEXTS, ctx.user_id), ctx.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish context update to Mercure: {e}")

        return ctx

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

    async def get(self, context_id: str) -> ConversationContext | None:
        async with agent_session() as session:
            result = await session.execute(select(ConversationContext).where(ConversationContext.id == context_id))
            return result.scalar_one_or_none()


context_repo = ContextRepository()

import logging
from datetime import UTC, datetime

from sqlalchemy import delete, func, or_, select, text, update

from app.db.agent_engine import agent_engine, agent_session
from app.db.models import TURN_EXPIRED, TURN_RUNNING, Message
from app.db.proaction_model import AgentBase
from app.mercure import topics
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class MessageRepository:
    """Async message persistence using SQLAlchemy + asyncpg.

    Every created message is published to Mercure on the user's chat topic
    (app.mercure.topics), the streamed ones included: a second tab or the phone
    only learns of an exchange that way. The clients recognise their own echo.
    """

    def __init__(self):
        self.publisher = MercurePublisher()

    async def ensure_table(self) -> None:
        """Create the agent_message table if it doesn't exist."""
        async with agent_engine.begin() as conn:
            await conn.run_sync(AgentBase.metadata.create_all)

    async def create(
        self,
        user_id: str,
        role: str,
        content: str,
        context_id: str | None = None,
        *,
        message_id: str | None = None,
        client_key: str | None = None,
        turn_lease_until: datetime | None = None,
        turn_screen_context: str | None = None,
        blocks: list[dict] | None = None,
    ) -> Message:
        """Store a message and publish it on the user's chat topic.

        `message_id` lets a streamed answer be stored under the id its stream announced: the
        device that streamed it then recognises the Mercure echo of the same message.

        A `turn_lease_until` says the message opens a turn that is running (MAG-344), and
        until when its process holds it. `client_key` is the client's idempotency key: a
        second message of the same user with the same key raises `IntegrityError`.

        `blocks` is what the turn did before it answered — its `tool_use` / `tool_result`
        rounds (MAG-211). Stored for the next model call only: the published payload is
        `to_dict()`, which does not carry them, so no client sees a thing change.
        """
        async with agent_session() as session:
            msg = Message(
                user_id=user_id,
                role=role,
                content=content,
                context_id=context_id,
                client_key=client_key,
                # `None` rather than `[]` for a turn that called nothing: the column then
                # says « no round », not « a round that is empty ».
                blocks=blocks or None,
            )
            if turn_lease_until is not None:
                msg.turn_status = TURN_RUNNING
                msg.turn_lease_until = turn_lease_until
                msg.turn_screen_context = turn_screen_context
            if message_id:
                msg.id = message_id
            session.add(msg)
            await session.commit()
            await session.refresh(msg)

        try:
            await self.publisher.publish(topics.for_user(topics.CHAT, user_id), msg.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish message to Mercure: {e}")

        return msg

    async def get(self, message_id: str) -> Message | None:
        async with agent_session() as session:
            result = await session.execute(select(Message).where(Message.id == message_id))
            return result.scalar_one_or_none()

    async def find_by_client_key(self, user_id: str, client_key: str) -> Message | None:
        """The message this user already sent under this idempotency key, if any."""
        async with agent_session() as session:
            result = await session.execute(
                select(Message).where(Message.user_id == user_id, Message.client_key == client_key)
            )
            return result.scalar_one_or_none()

    async def finish_turn(self, message_id: str) -> None:
        """The turn answering this message is over: it is a message like any other again."""
        async with agent_session() as session:
            await session.execute(
                update(Message)
                .where(Message.id == message_id, Message.turn_status == TURN_RUNNING)
                .values(turn_status=None, turn_lease_until=None, turn_screen_context=None)
            )
            await session.commit()

    async def extend_lease(self, message_id: str, until: datetime) -> None:
        async with agent_session() as session:
            await session.execute(
                update(Message)
                .where(Message.id == message_id, Message.turn_status == TURN_RUNNING)
                .values(turn_lease_until=until)
            )
            await session.commit()

    async def release_leases(self, message_ids: list[str]) -> None:
        """Let go of turns this process was running, so another one takes them up at once."""
        if not message_ids:
            return
        async with agent_session() as session:
            await session.execute(
                update(Message)
                .where(Message.id.in_(message_ids), Message.turn_status == TURN_RUNNING)
                .values(turn_lease_until=datetime.now(UTC))
            )
            await session.commit()

    async def expire_stale_turns(self, older_than: datetime) -> list[tuple[str, str]]:
        """Mark unanswered the messages whose turn was lost and that are too old to answer now.

        Returns the `(message id, user id)` of each one, for the error tracker.
        """
        async with agent_session() as session:
            result = await session.execute(
                update(Message)
                .where(
                    Message.turn_status == TURN_RUNNING,
                    Message.created_at < older_than,
                    or_(Message.turn_lease_until.is_(None), Message.turn_lease_until < datetime.now(UTC)),
                )
                .values(turn_status=TURN_EXPIRED, turn_lease_until=None, turn_screen_context=None)
                .returning(Message.id, Message.user_id)
                .execution_options(synchronize_session=False)
            )
            expired = [(row.id, row.user_id) for row in result.all()]
            await session.commit()
            return expired

    async def claim_resumable_turns(self, since: datetime, lease_until: datetime, limit: int = 20) -> list[Message]:
        """The messages whose turn was lost, no older than `since`, now leased to the caller.

        Claimed one by one with a conditional update: two agent processes sweeping at the
        same moment never both get the same message.
        """
        now = datetime.now(UTC)
        lapsed = or_(Message.turn_lease_until.is_(None), Message.turn_lease_until < now)
        claimed: list[Message] = []
        async with agent_session() as session:
            result = await session.execute(
                select(Message)
                .where(Message.turn_status == TURN_RUNNING, Message.created_at >= since, lapsed)
                .order_by(Message.created_at.asc())
                .limit(limit)
            )
            for msg in result.scalars().all():
                won = await session.execute(
                    update(Message)
                    .where(Message.id == msg.id, Message.turn_status == TURN_RUNNING, lapsed)
                    .values(turn_lease_until=lease_until)
                    .execution_options(synchronize_session=False)
                )
                if won.rowcount:
                    claimed.append(msg)
            await session.commit()
        return claimed

    async def find_recent(self, user_id: str, limit: int = 20) -> list[Message]:
        async with agent_session() as session:
            result = await session.execute(
                select(Message).where(Message.user_id == user_id).order_by(Message.created_at.desc()).limit(limit)
            )
            messages = list(result.scalars().all())
            messages.reverse()  # chronological order
            return messages

    async def delete_by_user(self, user_id: str) -> int:
        """Remove every message of one user, and return how many there were."""
        async with agent_session() as session:
            result = await session.execute(delete(Message).where(Message.user_id == user_id))
            await session.commit()
            return result.rowcount

    async def find_last(self, user_id: str, exclude_id: str | None = None) -> Message | None:
        """The newest message of the user, whatever its role — `exclude_id` skips the one being answered (MAG-10)."""
        async with agent_session() as session:
            query = select(Message).where(Message.user_id == user_id)
            if exclude_id:
                query = query.where(Message.id != exclude_id)
            result = await session.execute(query.order_by(Message.created_at.desc()).limit(1))
            return result.scalars().first()

    async def find_before(self, user_id: str, before_id: str, limit: int = 20) -> list[Message]:
        """Load messages older than the given message ID (cursor-based pagination)."""
        async with agent_session() as session:
            # First get the target message's created_at
            target = await session.execute(select(Message).where(Message.id == before_id))
            target_msg = target.scalar_one_or_none()
            if target_msg is None:
                return []

            result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.created_at < target_msg.created_at)
                .order_by(Message.created_at.desc())
                .limit(limit)
            )
            messages = list(result.scalars().all())
            messages.reverse()  # chronological order
            return messages

    async def search(self, user_id: str, query: str, limit: int = 20) -> list[Message]:
        """Full-text search on message content using ILIKE."""
        async with agent_session() as session:
            result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.content.ilike(f"%{query}%"))
                .order_by(Message.created_at.desc())
                .limit(limit)
            )
            return list(result.scalars().all())

    async def find_around(self, user_id: str, message_id: str, limit: int = 40) -> dict:
        """Load ~limit/2 messages before and after a target message.

        Returns {"messages": [...], "targetIndex": N}.
        """
        half = limit // 2
        async with agent_session() as session:
            target = await session.execute(select(Message).where(Message.id == message_id))
            target_msg = target.scalar_one_or_none()
            if target_msg is None:
                return {"messages": [], "targetIndex": 0}

            # Messages before (desc, then reverse)
            before_result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.created_at < target_msg.created_at)
                .order_by(Message.created_at.desc())
                .limit(half)
            )
            before = list(before_result.scalars().all())
            before.reverse()

            # Messages after (asc)
            after_result = await session.execute(
                select(Message)
                .where(Message.user_id == user_id, Message.created_at > target_msg.created_at)
                .order_by(Message.created_at.asc())
                .limit(half)
            )
            after = list(after_result.scalars().all())

            messages = [*before, target_msg, *after]
            target_index = len(before)

            return {"messages": messages, "targetIndex": target_index}

    async def find_by_context(
        self, context_id: str, since: datetime | None = None, limit: int = 100, user_id: str | None = None
    ) -> list[Message]:
        """The messages of one thread, oldest first — a summary (MAG-11) and a history (MAG-13) read this.

        `since` is the instant the last summary covers up to, so a re-summary only reads
        what is new. The limit is a floor under the cost of a thread nobody ever closed:
        it keeps the newest messages, which is why the query orders descending and the
        list is reversed afterwards.

        `user_id` narrows the thread to its owner. The context id a history is built around
        comes from a model's answer, and the router only hands back one of the user's own
        contexts (MAG-203) — this is the belt to that braces, and it costs nothing: the
        thread is loaded with the owner checked in the query rather than trusted.
        """
        async with agent_session() as session:
            query = select(Message).where(Message.context_id == context_id)
            if user_id is not None:
                query = query.where(Message.user_id == user_id)
            if since is not None:
                query = query.where(Message.created_at > since)
            result = await session.execute(query.order_by(Message.created_at.desc()).limit(limit))
            messages = list(result.scalars().all())
            messages.reverse()  # chronological order
            return messages

    async def count_by_context(self, context_id: str, since: datetime | None = None) -> int:
        """How many messages a thread has gained since `since` — the summary threshold."""
        async with agent_session() as session:
            query = select(func.count()).select_from(Message).where(Message.context_id == context_id)
            if since is not None:
                query = query.where(Message.created_at > since)
            result = await session.execute(query)
            return int(result.scalar_one() or 0)

    async def update_context(self, message_id: str, context_id: str) -> None:
        """Retroactively tag a message with a context ID."""
        async with agent_session() as session:
            result = await session.execute(select(Message).where(Message.id == message_id))
            msg = result.scalar_one_or_none()
            if msg:
                msg.context_id = context_id
                await session.commit()

    async def find_after(self, user_id: str, after: str | None = None) -> list[Message]:
        """Find messages after a given ISO timestamp (for incremental sync)."""
        async with agent_session() as session:
            query = select(Message).where(Message.user_id == user_id)
            if after:
                query = query.where(Message.created_at > text(f"'{after}'::timestamptz"))
            result = await session.execute(query.order_by(Message.created_at.asc()))
            return list(result.scalars().all())


message_repo = MessageRepository()

"""A chat turn that goes to its end whoever is listening (MAG-344).

The turn used to live in the generator of the streamed response: the client leaving
cancelled it, and an agent restarting lost it. On 7 Oct. two « rappelle-moi de me servir un
café » were stored and never answered; hours later the history replayed one of them as a
pending request and a reminder nobody wanted was scheduled.

Now the turn is a background task and the response only follows it through a queue. The
user's message carries the turn's state (`turn_status`, `turn_lease_until`): a running turn
renews its lease while it works, and a message whose lease has lapsed belongs to a process
that is gone — it is taken up again if it is recent, and marked unanswered if it is not.
"""

import asyncio
import contextlib
import logging
from collections.abc import AsyncGenerator
from datetime import UTC, datetime, timedelta

from app.config import settings
from app.db.message_repository import message_repo
from app.db.models import TURN_RUNNING, Message
from app.error_tracking import UNANSWERED_MESSAGE, capture_error, capture_signal
from app.llm.image import ChatImage
from app.llm.streaming import StreamingGateway, answer_message_id

logger = logging.getLogger(__name__)

# A turn that does not renew its lease within this is a turn nobody is running.
LEASE = timedelta(seconds=30)
HEARTBEAT_SECONDS = 10
# A response with nothing to say (a slow tool) says so this often: a proxy or a network that
# sees a silent connection cuts it, and the client takes the cut for a failed turn (MAG-638).
KEEPALIVE_SECONDS = 10
SWEEP_SECONDS = 30
# How long a repeated request waits for the turn it duplicates before letting go.
REPLAY_WAIT_SECONDS = 120
REPLAY_POLL_SECONDS = 1.0
# How long a stop waits for the turn it cancels to wind down (a tool already acting is let finish).
STOP_WAIT_SECONDS = 15

_END = object()


def lease_deadline() -> datetime:
    return datetime.now(UTC) + LEASE


class TurnRunner:
    """Runs the turns in the background and lets a response follow them."""

    def __init__(self) -> None:
        # Held: the event loop keeps only a weak reference to a running task.
        self._tasks: dict[str, asyncio.Task] = {}
        # The turns the user stopped (MAG-320): told apart from a shutdown, which hands the turn over.
        self._stopped: set[str] = set()
        self._sweeper: asyncio.Task | None = None

    async def idle(self) -> None:
        """Wait until no turn is running — tests, and a clean shutdown."""
        while self._tasks:
            await asyncio.gather(*self._tasks.values(), return_exceptions=True)
            # Tasks already done are only forgotten by their callbacks, which need a turn of the loop.
            await asyncio.sleep(0)

    def start(
        self,
        gateway: StreamingGateway,
        *,
        user_id: str,
        message_id: str,
        message: str,
        screen_context: str | None = None,
        image: ChatImage | None = None,
        carried_over: str | None = None,
        follow: bool = True,
    ) -> AsyncGenerator[dict | None, None] | None:
        """Launch the turn answering `message_id`; the events come back to whoever follows it.

        `carried_over` is what the turn this message replaces had already done (MAG-320).
        """
        queue: asyncio.Queue | None = asyncio.Queue() if follow else None
        task = asyncio.create_task(
            self._run(gateway, user_id, message_id, message, screen_context, image, carried_over, queue)
        )
        self._tasks[message_id] = task
        task.add_done_callback(lambda _: self._forget(message_id))
        return _events(queue) if queue is not None else None

    async def _run(
        self,
        gateway: StreamingGateway,
        user_id: str,
        message_id: str,
        message: str,
        screen_context: str | None,
        image: ChatImage | None,
        carried_over: str | None,
        queue: asyncio.Queue | None,
    ) -> None:
        heartbeat = asyncio.create_task(self._renew_lease(message_id))
        try:
            async for event in gateway.chat_stream(
                message,
                user_id,
                message_id,
                screen_context=screen_context,
                image=image,
                carried_over=carried_over,
                stopped=lambda: message_id in self._stopped,
            ):
                if queue is not None:
                    queue.put_nowait(event)
            await self._settle(message_id)
        except asyncio.CancelledError:
            # Stopped by the user: the turn is over, and must not be taken up again once its
            # lease lapses. A shutdown cancels without that, and hands the turn over instead.
            if message_id in self._stopped:
                await self._settle(message_id)
            raise
        except Exception as exc:
            # The turn stays running with its lease running out: it is taken up again if
            # the message is recent, and expires otherwise.
            logger.exception(f"Turn answering {message_id} failed")
            capture_error(exc, message_id=message_id, user_id=user_id)
        finally:
            heartbeat.cancel()
            if queue is not None:
                queue.put_nowait(_END)

    async def stop(self, message_id: str) -> bool:
        """Cancel the turn answering `message_id`, and wait until it has wound down (MAG-320).

        A tool already acting is let finish and recorded, so what was done is known; nothing
        starts after. `False` when no turn of this process is running for it.
        """
        task = self._tasks.get(message_id)
        if task is None or task.done():
            return False
        # Forgotten when the task ends, not when this wait does: a tool slower than the wait is
        # still being let finish, and its turn must still know the user stopped it.
        self._stopped.add(message_id)
        task.cancel()
        await asyncio.wait({task}, timeout=STOP_WAIT_SECONDS)
        return True

    def _forget(self, message_id: str) -> None:
        self._tasks.pop(message_id, None)
        self._stopped.discard(message_id)

    @staticmethod
    async def _settle(message_id: str) -> None:
        try:
            await message_repo.finish_turn(message_id)
        except Exception as exc:
            logger.warning(f"Could not mark the turn of {message_id} as finished: {exc}")

    @staticmethod
    async def _renew_lease(message_id: str) -> None:
        while True:
            await asyncio.sleep(HEARTBEAT_SECONDS)
            try:
                await message_repo.extend_lease(message_id, lease_deadline())
            except Exception as exc:
                logger.warning(f"Could not renew the lease of {message_id}: {exc}")

    async def replay(self, user_id: str, message: Message) -> AsyncGenerator[dict | None, None]:
        """The events of a message that was already received: its answer, once there is one.

        Another request — or another process — owns the turn, so this one only waits for
        the answer to be stored. `None` is a pause the response turns into a keep-alive.
        """
        yield {"type": "RUN_STARTED", "runId": message.id}
        answer_id = answer_message_id(message.id)
        waited = 0.0
        while waited <= REPLAY_WAIT_SECONDS:
            # In this order: a turn stores its answer before it stops being running, so a
            # message no longer running whose answer is not there never had one.
            current = await message_repo.get(message.id)
            answer = await message_repo.get(answer_id)
            if answer is not None:
                yield {"type": "TEXT_MESSAGE_START", "messageId": answer.id, "role": "assistant"}
                yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": answer.id, "delta": answer.content}
                yield {"type": "TEXT_MESSAGE_END", "messageId": answer.id}
                break
            if current is None or current.turn_status != TURN_RUNNING:
                break
            yield None
            await asyncio.sleep(REPLAY_POLL_SECONDS)
            waited += REPLAY_POLL_SECONDS
        yield {"type": "RUN_FINISHED", "runId": message.id}

    async def resume_lost_turns(self, gateway: StreamingGateway) -> int:
        """Answer the messages whose turn was lost, and give up on the ones that are too old."""
        window = timedelta(minutes=settings.turn_resume_minutes)
        now = datetime.now(UTC)
        expired = await message_repo.expire_stale_turns(older_than=now - window)
        if expired:
            logger.warning(f"{len(expired)} message(s) left unanswered for more than {window}: marked unanswered")
        for message_id, user_id in expired:
            capture_signal(UNANSWERED_MESSAGE, message_id=message_id, user_id=user_id)

        resumed = 0
        for message in await message_repo.claim_resumable_turns(since=now - window, lease_until=lease_deadline()):
            if message.id in self._tasks:
                continue
            if await message_repo.get(answer_message_id(message.id)) is not None:
                # The answer was stored, and the process went down before it said so.
                await self._settle(message.id)
                continue
            logger.warning(f"Taking up the turn answering {message.id}, lost with its process")
            self.start(
                gateway,
                user_id=message.user_id,
                message_id=message.id,
                message=message.content,
                # The screenshot is gone with the process that held it (MAG-214): the
                # history tells the model there was one.
                screen_context=message.turn_screen_context,
                follow=False,
            )
            resumed += 1
        return resumed

    def start_sweeper(self, gateway: StreamingGateway) -> None:
        """Look for lost turns now and then: a process that is shut down hands its turns to the next."""
        self._sweeper = asyncio.create_task(self._sweep(gateway))

    async def _sweep(self, gateway: StreamingGateway) -> None:
        while True:
            try:
                await self.resume_lost_turns(gateway)
            except Exception as exc:
                logger.warning(f"Could not look for lost turns: {exc}")
            await asyncio.sleep(SWEEP_SECONDS)

    async def shutdown(self) -> None:
        """Stop the turns this process runs and hand their messages over at once."""
        if self._sweeper is not None:
            self._sweeper.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._sweeper
        running = list(self._tasks.items())
        for _, task in running:
            task.cancel()
        await asyncio.gather(*(task for _, task in running), return_exceptions=True)
        try:
            await message_repo.release_leases([message_id for message_id, _ in running])
        except Exception as exc:
            logger.warning(f"Could not hand over the running turns: {exc}")


async def _events(queue: asyncio.Queue) -> AsyncGenerator[dict | None, None]:
    """The turn's events; `None` is a pause the response turns into a keep-alive."""
    while True:
        try:
            event = await asyncio.wait_for(queue.get(), KEEPALIVE_SECONDS)
        except TimeoutError:
            yield None
            continue
        if event is _END:
            return
        yield event


turn_runner = TurnRunner()

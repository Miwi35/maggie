import json
import logging
import time
from datetime import UTC, datetime

import aio_pika

from app.db.journal_repository import journal_repo
from app.db.message_repository import message_repo
from app.error_tracking import PROACTION_FAILED, capture_signal
from app.llm.context_summary import context_summarizer
from app.llm.gateway import LLMGateway
from app.mercure import topics
from app.mercure.publisher import MercurePublisher
from app.proactions import api as proactions_api
from app.proactions.api import Proaction
from app.queue.connection import PROACTION_QUEUE, get_channel
from app.queue.message_notification import notify_message
from app.trips import is_silent_trip_run
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

publisher = MercurePublisher()


async def _record(proaction: Proaction, status: str, *, response: str | None = None, error: str | None = None) -> None:
    """Record the outcome on the API, then tell the user's screens (the admin interrupts on a completed one).

    A record that fails is logged, never raised: the message is already in the chat, and running the
    proaction again would say it twice. It stays `running` on the API, where it shows.
    """
    try:
        recorded = await proactions_api.record(proaction, status, response=response, error=error)
    except Exception as e:
        logger.error(f"Could not record proaction {proaction.id} as {status}: {e}")
        return

    try:
        await publisher.publish(topics.for_user(topics.PROACTIONS, proaction.user_id), recorded.to_dict())
    except Exception as e:
        logger.warning(f"Failed to publish proaction update: {e}")


async def _journal(
    proaction: Proaction, started_at: datetime, clock: float, *, result: dict | None = None, error: str | None = None
) -> None:
    """Keep the run in the journal (MAG-688); never raises — the message is delivered whether it is kept or not."""
    try:
        day = started_at.astimezone(await resolve_user_timezone(proaction.user_id)).date().isoformat()
        await journal_repo.record_proaction(
            proaction.user_id,
            proaction.id,
            proaction.prompt,
            proaction.summary,
            day,
            started_at,
            int((time.monotonic() - clock) * 1000),
            result=result,
            error=error,
        )
    except Exception as e:
        logger.error(f"Could not keep proaction {proaction.id} in the journal: {e}")


async def execute_proaction(gateway: LLMGateway, proaction: Proaction, *, dry_run: bool = False) -> dict:
    """Run one claimed proaction to its message — the queue consumer's and the manual trigger's one path.

    The proaction comes claimed (running) from the API (MAG-327):
    1. Calls LLMGateway.proaction() with the proaction's prompt
    2. Stores what came out in the conversation thread it belongs to (MAG-14)
    3. Records it on the API as completed (with response) or failed (with error)
    4. Keeps the run in the journal (MAG-688): its tool calls, duration and tokens

    With `dry_run` (MAG-249) only step 1 happens, with the tools that write simulated: the proaction is
    not claimed and keeps its status, nothing is stored, and the result says which tools were simulated.
    """
    proaction_id = proaction.id

    if dry_run:
        result = await gateway.proaction(proaction.prompt, proaction.user_id, dry_run=True, summary=proaction.summary)
        return {
            "status": proaction.status,
            "message": result["response"],
            "toolCalls": result.get("tool_calls", []),
            "simulatedTools": result.get("simulated_tools", []),
        }

    started_at = datetime.now(UTC)
    clock = time.monotonic()
    result: dict | None = None
    try:
        result = await gateway.proaction(proaction.prompt, proaction.user_id, summary=proaction.summary)
        # A trip check or replan (MAG-657) is a log, not a message: the departure alert is the API's.
        if result["response"] and not is_silent_trip_run(proaction.summary):
            # Stored in the thread the gateway routed it to. Without a thread the
            # message is an orphan: the reply it invites is routed against a
            # conversation that never mentions it, and the summary of the thread
            # it belonged to never learns Maggie spoke (MAG-14).
            context_id = result.get("context_id")
            stored = await message_repo.create(
                user_id=proaction.user_id,
                role="assistant",
                content=result["response"],
                context_id=context_id,
                # A proaction acts before it speaks, and the reply it invites is routed
                # into the same thread — so what it looked up is replayed there too
                # (MAG-211).
                blocks=result.get("blocks"),
            )
            # Out of any conversation: the user is told, never failing a proaction already delivered.
            await notify_message(
                proaction.user_id, stored.id, result["response"], has_audio=bool(getattr(stored, "audio", None))
            )
    except Exception as e:
        error_msg = str(e)
        logger.error(f"Proaction {proaction_id} failed: {error_msg}")
        await _journal(proaction, started_at, clock, result=result, error=error_msg or type(e).__name__)
        await _record(proaction, "failed", error=error_msg)
        # The error type only: its message may quote the prompt or a tool result.
        capture_signal(PROACTION_FAILED, proaction_id=proaction_id, user_id=proaction.user_id, error=type(e).__name__)
        return {"status": "failed", "message": None, "error": error_msg, "toolCalls": []}

    await _journal(proaction, started_at, clock, result=result)
    await _record(proaction, "completed", response=result["response"])

    # Awaited, unlike on the streamed path: nobody is holding a stream open here, and the
    # reply to a reminder is the next thing to be routed against this thread's summary.
    # After the record: the message is delivered, a summary that fails does not undo it.
    context_id = result.get("context_id")
    if result["response"] and context_id:
        try:
            await context_summarizer.maybe_summarize(context_id)
        except Exception as e:
            logger.warning(f"Could not summarize thread {context_id} after proaction {proaction_id}: {e}")
    logger.info(f"Proaction {proaction_id} completed")
    return {"status": "completed", "message": result["response"], "toolCalls": result.get("tool_calls", [])}


def read_message(body: bytes) -> Proaction | None:
    """The claimed proaction a queue message carries, or None for anything else.

    Before MAG-327 a message carried a bare id of the agent's own table: one left in the queue across
    the deploy is dropped, its proaction having been moved to the API still pending.
    """
    try:
        data = json.loads(body.decode())
        return Proaction.from_api(data) if isinstance(data, dict) and data.get("userId") else None
    except (ValueError, KeyError):
        return None


async def start_consumer() -> None:
    """Start consuming proaction messages from the queue; each message carries one claimed proaction."""
    channel = get_channel()
    queue = await channel.declare_queue(PROACTION_QUEUE, durable=True)
    gateway = LLMGateway()

    async def on_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
        async with message.process():
            proaction = read_message(message.body)
            if proaction is None:
                logger.warning(f"Not a claimed proaction, dropped: {message.body[:100]!r}")
                return

            logger.info(f"Consuming proaction {proaction.id}")
            await execute_proaction(gateway, proaction)

    await queue.consume(on_message)
    logger.info("Proaction consumer started")

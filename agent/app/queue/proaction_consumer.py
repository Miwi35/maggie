import json
import logging
import time
from datetime import UTC, datetime

import aio_pika

from app.bilan import bilan_block, is_bilan, is_retired_relance
from app.db.journal_repository import journal_repo
from app.db.message_repository import message_repo
from app.db.models import SCREEN_DIGEST
from app.digest import is_digest, run_digest
from app.error_tracking import PROACTION_FAILED, capture_signal
from app.llm.context_summary import context_summarizer
from app.llm.gateway import LLMGateway
from app.mercure import topics
from app.mercure.publisher import MercurePublisher
from app.proactions import api as proactions_api
from app.proactions.api import Proaction
from app.proactions.triage import KIND_CHECK, Delivery, decide
from app.proactions.verdict import read_verdict
from app.queue.connection import PROACTION_QUEUE, get_channel
from app.queue.message_notification import message_link, message_preview, notify_message
from app.trips import is_retired_trip_check, is_silent_trip_run
from app.tts.voice_message import message_screen
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

publisher = MercurePublisher()


async def _record(
    proaction: Proaction, status: str, *, response: str | None = None, error: str | None = None, **trace
) -> None:
    """Record the outcome on the API, then tell the user's screens (the admin interrupts on a completed one
    the run delivered as an interruption).

    `trace` is how the run reached the owner (MAG-886): `delivery`, `topic_key`, `verdict`, `notice`,
    `message_link`. A completed run recorded as an interruption is what makes the API notify the owner.

    A record that fails is logged, never raised: the message is already in the chat, and running the
    proaction again would say it twice. It stays `running` on the API, where it shows.
    """
    try:
        recorded = await proactions_api.record(proaction, status, response=response, error=error, **trace)
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


def voice_screen(tool_calls: list[dict]) -> str | None:
    """The screen the run's vocal was meant for (MAG-709): the written message beside it belongs there too.

    Read from what the model asked, not from what the tool answered: a vocal that could not be made
    (speech or bucket down) leaves the written digest where it belongs.
    """
    for call in tool_calls:
        arguments = call.get("input")
        if (
            call.get("name") == "send_voice_message"
            and isinstance(arguments, dict)
            and (screen := message_screen(arguments.get("screen")))
        ):
            return screen
    return None


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
        result = await gateway.proaction(
            proaction.prompt,
            proaction.user_id,
            dry_run=True,
            summary=proaction.summary,
            kind=proaction.kind,
            signaled_topics=proaction.signaled_topics,
        )
        return {
            "status": proaction.status,
            "message": result["response"],
            "toolCalls": result.get("tool_calls", []),
            "simulatedTools": result.get("simulated_tools", []),
        }

    if is_retired_trip_check(proaction.summary):
        # Programmed before MAG-730 moved the checks to the API's cron: nothing left to do, no model woken.
        await _record(proaction, "completed", response="")
        return {"status": "completed", "message": None, "toolCalls": []}

    started_at = datetime.now(UTC)
    clock = time.monotonic()
    result: dict | None = None
    trace: dict = {}
    verdict = None
    try:
        if is_retired_relance(proaction.summary):
            # Laid down before MAG-826 removed the nudge: nothing left to say.
            await _record(proaction, "completed")
            return {"status": "completed", "message": None, "toolCalls": []}
        if is_digest(proaction.summary):
            # One message, read back by the code and voiced by it (MAG-807).
            result, stored = await run_digest(gateway, proaction)
            if stored is not None:
                await notify_message(
                    proaction.user_id,
                    stored.id,
                    stored.content,
                    has_audio=bool(getattr(stored, "audio", None)),
                    screen=SCREEN_DIGEST,
                )
            result_is_digest = True
        else:
            result_is_digest = False
            result = await gateway.proaction(
                proaction.prompt,
                proaction.user_id,
                summary=proaction.summary,
                kind=proaction.kind,
                signaled_topics=proaction.signaled_topics,
            )
        # A trip check or replan (MAG-657) is a log, not a message: the departure alert is the API's.
        # The digest has its own path and its own notification.
        delivers = not result_is_digest and not is_silent_trip_run(proaction.summary)
        delivery = Delivery.NONE
        if delivers:
            # A reminder always interrupts; a check is triaged on Maggie's verdict (MAG-886).
            verdict = read_verdict(result.get("tool_calls") or []) if proaction.kind == KIND_CHECK else None
            delivery = decide(
                proaction.kind, result["response"], verdict, list(proaction.signaled_topics), datetime.now(UTC)
            )
            if is_bilan(proaction.summary) and delivery is Delivery.INTERRUPT:
                # The evening review is a message, not a request (owner, 21 Nov.): it reaches the owner as a
                # `message` notification, never as an interruption.
                delivery = Delivery.THREAD
            trace = {
                "delivery": delivery.value,
                "topic_key": verdict.topic if verdict else None,
                "verdict": verdict.to_record() if verdict else None,
            }
        if delivery is not Delivery.NONE:
            # Stored in the thread the gateway routed it to. Without a thread the
            # message is an orphan: the reply it invites is routed against a
            # conversation that never mentions it, and the summary of the thread
            # it belonged to never learns Maggie spoke (MAG-14).
            context_id = result.get("context_id")
            if proaction.kind == KIND_CHECK:
                context_id = await gateway.proaction_context(result["response"], proaction.user_id)
                result["context_id"] = context_id
            screen = voice_screen(result.get("tool_calls") or [])
            ui_blocks = result.get("ui_blocks")
            if is_bilan(proaction.summary):
                tz = await resolve_user_timezone(proaction.user_id)
                ui_blocks = [*(ui_blocks or []), bilan_block(started_at.astimezone(tz).date())]
            stored = await message_repo.create(
                user_id=proaction.user_id,
                role="assistant",
                content=result["response"],
                context_id=context_id,
                # A proaction acts before it speaks, and the reply it invites is routed
                # into the same thread — so what it looked up is replayed there too
                # (MAG-211).
                blocks=result.get("blocks"),
                ui_blocks=ui_blocks,
                screen=screen,
            )
            # An interruption is announced by the API once the run is recorded (one sentence and the link
            # to this message); a message left in the thread tells nobody (MAG-886), except the review,
            # which notifies itself as a `message` like the digest.
            if is_bilan(proaction.summary):
                await notify_message(
                    proaction.user_id,
                    stored.id,
                    stored.content,
                    has_audio=bool(getattr(stored, "audio", None)),
                )
            trace["message_link"] = message_link(stored.id, screen)
            trace["notice"] = (verdict.headline if verdict else None) or message_preview(
                result["response"], has_audio=bool(getattr(stored, "audio", None))
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
    await _record(proaction, "completed", response=result["response"], **trace)

    # Awaited, unlike on the streamed path: nobody is holding a stream open here, and the
    # reply to a reminder is the next thing to be routed against this thread's summary.
    # After the record: the message is delivered, a summary that fails does not undo it.
    context_id = result.get("context_id")
    if result["response"].strip() and context_id:
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

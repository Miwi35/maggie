"""The morning digest as one message, read back by the code before it goes out (MAG-807, MAG-843).

The model writes the text from what it read. The code lists the facts that text owes (`app.digest_facts`) and,
if some are missing, tells the model once in its own thread; the text goes out as the model wrote it, a fact
still missing is only signalled. The vocal is made from the final text: one `agent_message` on the digest
screen, with its audio when the voice answers.
"""

import logging
from datetime import date, datetime, time, tzinfo

from app.db.models import SCREEN_DIGEST, Message
from app.digest_facts import empty_sentences, missing_facts, required_facts, signal
from app.domain.conversation.service import message_service
from app.error_tracking import DIGEST_FACTS_MISSING, DIGEST_SAYS_EMPTY, capture_signal
from app.rendezvous import DEFAULT_AFTERNOON_POINT_TIME, DIGEST_SUMMARY
from app.tts.voice_message import VoiceMessageError, send_voice_message
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

DIGEST_AVAILABLE = "Votre digest du matin est disponible."


def is_digest(summary: str | None) -> bool:
    return summary == DIGEST_SUMMARY


def digest_block(text: str, day: date, tz: tzinfo) -> dict:
    """The block that lets a client draw the digest card: it expires at the afternoon point, local time."""
    expires = datetime.combine(day, time.fromisoformat(DEFAULT_AFTERNOON_POINT_TIME), tzinfo=tz)
    return {"type": "digest", "day": day.isoformat(), "text": text, "expiresAt": expires.isoformat(), "openedAt": None}


async def run_digest(gateway, proaction) -> tuple[dict, Message | None]:
    """Run the digest proaction: the result, whose `response` is the text as sent, and the one message stored."""
    user_id = proaction.user_id
    tz = await resolve_user_timezone(user_id)
    day = datetime.now(tz).date()

    def review(text: str, tool_calls: list[dict]) -> str | None:
        missing = missing_facts(text, required_facts(tool_calls, day))
        empty = empty_sentences(text)
        return signal(missing, empty) if missing or empty else None

    result = await gateway.proaction(
        proaction.prompt,
        user_id,
        summary=proaction.summary,
        blocked_tools=frozenset({"send_voice_message"}),
        review=review,
    )
    text = (result.get("response") or "").strip()
    if not text or result.get("error"):
        return result, None

    missing = missing_facts(text, required_facts(result.get("tool_calls") or [], day))
    if missing:
        logger.warning(f"Digest of {user_id} still misses {len(missing)} fact(s) after the signal")
        capture_signal(DIGEST_FACTS_MISSING, user_id=user_id, facts=str(len(missing)))
    empty = empty_sentences(text)
    if empty:
        logger.warning(f"Digest of {user_id} still says what is empty after the signal: {len(empty)} sentence(s)")
        capture_signal(DIGEST_SAYS_EMPTY, user_id=user_id, sentences=str(len(empty)))
    result["response"] = text

    context_id = result.get("context_id")
    blocks = [digest_block(text, day, tz)]
    try:
        stored = await send_voice_message(
            user_id, text, context_id, screen=SCREEN_DIGEST, content=DIGEST_AVAILABLE, ui_blocks=blocks
        )
    except VoiceMessageError as e:
        logger.warning(f"Digest of {user_id} without its vocal: {e}")
        stored = await message_service.create(
            user_id=user_id,
            role="assistant",
            content=DIGEST_AVAILABLE,
            context_id=context_id,
            blocks=result.get("blocks"),
            ui_blocks=blocks,
            screen=SCREEN_DIGEST,
        )
    return result, stored

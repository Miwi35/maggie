"""The morning digest as one message, read back by the code before it goes out (MAG-807).

The model writes the text from what it read. The code lists the facts that text owes (`app.digest_facts`), asks
for one rewrite if some are missing, then adds a plain sentence for those still missing, and makes the
vocal from the final text: one `agent_message` on the digest screen, with its audio when the voice answers.
"""

import logging
from datetime import date, datetime, time, tzinfo

from app.db.message_repository import message_repo
from app.db.models import SCREEN_DIGEST, Message
from app.digest_facts import Fact, missing_facts, required_facts
from app.error_tracking import DIGEST_FACTS_MISSING, capture_signal
from app.llm.client import client_for
from app.llm.runner import run_tool_loop
from app.rendezvous import DEFAULT_AFTERNOON_POINT_TIME, DIGEST_SUMMARY
from app.tts.voice_message import VoiceMessageError, send_voice_message
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

DIGEST_AVAILABLE = "Votre digest du matin est disponible."

REWRITE_SYSTEM = (
    "RELECTURE DU DIGEST : tu relis le digest du matin que tu viens d'écrire. Il oublie des faits obligatoires. "
    "Réécris-le en entier, en y disant chacun d'eux (les heures dites « 9 h 15 », dans la phrase du fait). "
    "Même ordre : météo, déroulé de la journée, tâches sans heure. Aucune question, aucun Markdown, une idée par "
    "phrase, « monsieur » une seule fois, vouvoiement. Réponds par le texte du digest, rien d'autre."
)


def is_digest(summary: str | None) -> bool:
    return summary == DIGEST_SUMMARY


def digest_block(text: str, day: date, tz: tzinfo) -> dict:
    """The block that lets a client draw the digest card: it expires at the afternoon point, local time."""
    expires = datetime.combine(day, time.fromisoformat(DEFAULT_AFTERNOON_POINT_TIME), tzinfo=tz)
    return {"type": "digest", "day": day.isoformat(), "text": text, "expiresAt": expires.isoformat(), "openedAt": None}


def _rewrite_request(text: str, missing: list[Fact]) -> str:
    lines = "\n".join(f"- {fact.fallback}" for fact in missing)
    return f"Ton digest :\n{text}\n\nFaits à dire (chacun avec son heure dans la même phrase) :\n{lines}"


async def _rewrite(gateway, user_id: str, model: str | None, text: str, missing: list[Fact]) -> str:
    result = await run_tool_loop(
        REWRITE_SYSTEM,
        [{"role": "user", "content": _rewrite_request(text, missing)}],
        None,
        client=await client_for(user_id, gateway.client),
        tool_router=gateway.tool_router,
        user_id=user_id,
        model=model or "",
        call_type="proaction",
        source="proaction",
    )
    return (result.get("response") or "").strip()


async def run_digest(gateway, proaction) -> tuple[dict, Message | None]:
    """Run the digest proaction: the result, whose `response` is the text as sent, and the one message stored."""
    user_id = proaction.user_id
    result = await gateway.proaction(proaction.prompt, user_id, summary=proaction.summary)
    text = (result.get("response") or "").strip()
    if not text or result.get("error"):
        return result, None

    tz = await resolve_user_timezone(user_id)
    day = datetime.now(tz).date()
    facts = required_facts(result.get("tool_calls") or [], day)
    missing = missing_facts(text, facts)
    if missing:
        try:
            rewritten = await _rewrite(gateway, user_id, result.get("model"), text, missing)
        except Exception as e:
            logger.warning(f"Digest of {user_id} not rewritten, falling back to sentences: {e}")
            rewritten = ""
        text = rewritten or text
        missing = missing_facts(text, facts)
    if missing:
        text = " ".join([text, *(fact.fallback for fact in missing)])
        capture_signal(DIGEST_FACTS_MISSING, user_id=user_id, facts=str(len(missing)))
    result["response"] = text

    context_id = result.get("context_id")
    blocks = [digest_block(text, day, tz)]
    try:
        stored = await send_voice_message(
            user_id, text, context_id, screen=SCREEN_DIGEST, content=DIGEST_AVAILABLE, ui_blocks=blocks
        )
    except VoiceMessageError as e:
        logger.warning(f"Digest of {user_id} without its vocal: {e}")
        stored = await message_repo.create(
            user_id=user_id,
            role="assistant",
            content=DIGEST_AVAILABLE,
            context_id=context_id,
            blocks=result.get("blocks"),
            ui_blocks=blocks,
            screen=SCREEN_DIGEST,
        )
    return result, stored

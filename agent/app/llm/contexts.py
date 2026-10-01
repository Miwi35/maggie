"""The threads a user has open, for every path that writes in one (MAG-11, MAG-14).

Two things live here, because the streamed chat and the proactions both need them and a
second copy of either would drift apart:

  - `active_contexts_section()` — the open threads as a system-prompt block: every
    label, and the summary of the few most recently spoken in. This is what a proaction
    used to run without, so a reminder landed in the conversation knowing nothing of it.
  - `resolve_context()` — the fast-model router that places a piece of text in an open
    thread or opens a new one, with the ownership check a model's answer needs (MAG-203).

Neither raises. A thread list the database would not give up costs the model what the
conversation was about; it must not cost the user the answer, or the reminder.
"""

import json
import logging
import time
from datetime import UTC, datetime, timedelta

from app.config import settings
from app.db.context_model import ContextStatus
from app.db.context_repository import context_repo
from app.metrics import record_llm_usage

logger = logging.getLogger(__name__)

# How many open threads carry their summary into the system prompt. Beyond this the
# label alone goes in: a summary is ~5 lines, and the owner can have a dozen threads open.
MAX_SUMMARIZED_CONTEXTS = 5

ROUTER_SYSTEM_PROMPT = (
    "Tu es un routeur de contexte. Analyse le message et les contextes existants.\n"
    "Réponds UNIQUEMENT avec un JSON valide, sans explication :\n"
    '- Si le message correspond à un contexte existant : {"context_id": "<id>"}\n'
    '- Si c\'est un nouveau sujet : {"context_id": null, "label": "<label court>"}\n'
    "Le label doit être court (3-5 mots max), en français.\n"
    "Préfère les contextes récents, mais un ancien contexte reste valide si le sujet correspond."
)


async def active_contexts_section(user_id: str) -> str:
    """The user's open threads as a system-prompt section, or an empty string if there are none.

    What each thread is about comes from its summary (MAG-11), and this is what a thread
    older than `max_conversation_history` leaves behind: without it, a context the
    conversation has moved on from is a label and nothing else.
    """
    try:
        contexts = await context_repo.find_active(user_id)
    except Exception as e:
        logger.warning(f"Could not load the open threads: {e}")
        return ""

    if not contexts:
        return ""

    lines = ["\n\nContextes de conversation en cours :"]
    for rank, ctx in enumerate(contexts):
        status_icon = "●" if ctx.status == ContextStatus.ACTIVE else "◐"
        lines.append(f"- {status_icon} {ctx.label} ({ctx.status.value})")
        # Every open thread keeps its label — the list is what tells Maggie a subject is
        # still open. Only the most recently spoken-in ones carry their summary with
        # them, because that is where the tokens are and `find_active` already orders by
        # `updated_at`.
        if ctx.summary and rank < MAX_SUMMARIZED_CONTEXTS:
            lines.append(f"  Résumé : {ctx.summary}")
    return "\n".join(lines)


def _age(idle: timedelta) -> str:
    """How long a thread has been quiet, as the router reads it."""
    seconds = idle.total_seconds()
    if seconds < 60:
        return "à l'instant"
    if seconds < 3600:
        return f"il y a {int(seconds // 60)}min"
    if seconds < 86400:
        return f"il y a {int(seconds // 3600)}h"
    return f"il y a {idle.days}j"


async def resolve_context(client, text: str, user_id: str) -> dict | None:
    """Place `text` in one of the user's open threads, or open a new one for it.

    Returns what the Mind panel reads off a `context_update` event — `action`, `id`,
    `label`, `status` and the thread's `summary` — or `None` when there is no model to
    ask or the call failed, which leaves the caller without a thread rather than without
    an answer.

    `text` is whatever is about to be written in the thread: the user's message on the
    chat path, and on the proaction path the message Maggie is about to send unprompted,
    since there is no user message to route (MAG-14).
    """
    if client is None:
        return None

    try:
        contexts = await context_repo.find_active(user_id)
    except Exception as e:
        logger.warning(f"Could not load the open threads to route into: {e}")
        return None

    now = datetime.now(UTC)
    context_lines = [
        f'- id="{ctx.id}" label="{ctx.label}" dernière activité={_age(now - ctx.updated_at)}' for ctx in contexts
    ]
    context_list = "\n".join(context_lines) if context_lines else "(aucun)"

    try:
        t0 = time.monotonic()
        response = await client.messages.create(
            model=settings.anthropic_fast_model,
            max_tokens=200,
            system=ROUTER_SYSTEM_PROMPT,
            messages=[{"role": "user", "content": f"Contextes existants :\n{context_list}\n\nMessage : {text}"}],
        )
        duration = time.monotonic() - t0
        record_llm_usage(
            model=settings.anthropic_fast_model,
            call_type="context_resolve",
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            duration_seconds=duration,
        )

        answer = response.content[0].text.strip()
        # Handle markdown-wrapped JSON
        if answer.startswith("```"):
            answer = answer.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        result = json.loads(answer)

        context_id = result.get("context_id")
        # The id comes from the model, not from us: only one of this user's own contexts
        # may be written on the message. Anything else — a hallucinated id, one copied
        # from another user's thread — is a new topic (MAG-203).
        existing = next((c for c in contexts if str(c.id) == str(context_id)), None) if context_id else None
        if context_id and existing is None:
            logger.warning(f"Context router returned an id outside the user's contexts: {context_id!r}")
            context_id = None

        if context_id:
            logger.info(f"Context resolved: existing '{existing.label}' ({context_id})")
            # What keeps a thread from going dormant, and what wakes one that has (MAG-12).
            # Best-effort like the rest: the thread is resolved either way.
            try:
                await context_repo.touch(str(existing.id))
            except Exception as e:
                logger.warning(f"Could not mark context {context_id} as spoken in: {e}")
            # The summary travels with the event: the Mind panel replaces the whole
            # context when one arrives, so leaving it out would blank the line the panel
            # is showing on the very next message (MAG-11).
            return {
                "action": "matched",
                "id": str(existing.id),
                "label": existing.label,
                "status": "active",
                "summary": existing.summary,
            }

        label = result.get("label") or text[:60]
        ctx = await context_repo.create(user_id, label)
        logger.info(f"Context resolved: new '{label}' -> {ctx.id}")
        return {"action": "created", "id": str(ctx.id), "label": label, "status": "active", "summary": None}

    except Exception as e:
        logger.warning(f"Context resolution failed, continuing without context: {e}")
        return None

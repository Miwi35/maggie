"""What a conversation thread was about, kept in a few lines by the fast model (MAG-11).

A context used to hold a label and a log of tool calls, so the only memory of a thread
was its raw messages — and the model never sees more than the last
`max_conversation_history` of them, every thread mixed together. Past that line a
conversation simply stopped existing, and a thread Maggie was not currently in
contributed nothing at all.

This module writes the summary that survives both. It is deliberately all best-effort:
it runs *after* the answer the user was waiting for has been sent, so there is nobody to
report a failure to, and a thread with a stale summary is worth far more than a chat that
raised.

Two entry points, and the difference matters:

  - `maybe_summarize()` is the one the streaming gateway calls on every message. It
    counts first and usually does nothing.
  - `summarize()` always calls the model. It is what MAG-12's scheduler will call when a
    thread goes dormant or is closed, where "a few messages short of the threshold" is
    not a reason to leave a thread without a summary forever.
"""

import logging
import time

from app.config import settings
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.llm.client import create_llm_client, llm_configured
from app.metrics import record_llm_usage, usage_kwargs

logger = logging.getLogger(__name__)

# Enough for the five lines asked for below, and a hard stop on a model that decides to
# retell the conversation instead.
MAX_SUMMARY_TOKENS = 400

# How many of a thread's messages one pass reads. Only reached by a thread with no
# summary yet — afterwards each pass reads what came in since the last one.
MAX_MESSAGES_PER_PASS = 100

SYSTEM_PROMPT = (
    "Tu résumes un fil de conversation entre un utilisateur et son assistante personnelle.\n"
    "Écris en français, à la troisième personne, 5 lignes au maximum.\n"
    "Garde ce qui sert à reprendre le fil plus tard : les décisions prises, les demandes "
    "en cours, les préférences exprimées, les chiffres et les dates citées.\n"
    "Jette les politesses et les formulations. N'invente rien, ne commente pas, "
    "ne réponds qu'avec le résumé."
)


def _transcript(messages: list) -> str:
    """The thread as the model reads it. The role prefix is what tells a request from an answer."""
    lines = []
    for message in messages:
        speaker = "Utilisateur" if message.role == "user" else "Maggie"
        lines.append(f"{speaker} : {message.content}")
    return "\n".join(lines)


class ContextSummarizer:
    """Keeps `conversation_context.summary` up to date, with the cheap model."""

    def __init__(self):
        self.client = create_llm_client() if llm_configured() else None

    async def maybe_summarize(self, context_id: str) -> str | None:
        """Summarize the thread only if it has grown by the threshold since its last summary."""
        if self.client is None:
            return None

        try:
            ctx = await context_repo.get(context_id)
            if ctx is None:
                return None

            new_messages = await message_repo.count_by_context(context_id, since=ctx.summary_updated_at)
            if new_messages < settings.context_summary_every_messages:
                return None
        except Exception as e:
            logger.warning(f"Could not decide whether to summarize context {context_id}: {e}")
            return None

        return await self.summarize(context_id)

    async def summarize(self, context_id: str) -> str | None:
        """Rewrite a thread's summary from its previous one and the messages since.

        Returns the stored summary, or `None` when there was nothing to summarize or
        anything went wrong — never raises.
        """
        if self.client is None:
            return None

        try:
            ctx = await context_repo.get(context_id)
            if ctx is None:
                logger.warning(f"Cannot summarize unknown context {context_id}")
                return None

            messages = await message_repo.find_by_context(
                context_id, since=ctx.summary_updated_at, limit=MAX_MESSAGES_PER_PASS
            )
            if not messages:
                return None

            summary = await self._ask(ctx.label, ctx.summary, _transcript(messages))
            if not summary:
                return None

            await context_repo.set_summary(context_id, summary)
            logger.info(f"Summarized context {context_id} from {len(messages)} new messages")
            return summary

        except Exception as e:
            logger.warning(f"Failed to summarize context {context_id}: {e}")
            return None

    async def _ask(self, label: str, previous: str | None, transcript: str) -> str:
        """One call to the fast model. The previous summary goes in, so a pass stays incremental."""
        parts = [f"Fil : {label}"]
        if previous:
            parts.append(f"Résumé actuel :\n{previous}")
            parts.append(f"Nouveaux messages :\n{transcript}")
            parts.append("Réécris le résumé en intégrant les nouveaux messages.")
        else:
            parts.append(f"Messages :\n{transcript}")
            parts.append("Résume ce fil.")

        t0 = time.monotonic()
        try:
            response = await self.client.messages.create(
                model=settings.anthropic_fast_model,
                max_tokens=MAX_SUMMARY_TOKENS,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": "\n\n".join(parts)}],
            )
        except Exception:
            record_llm_usage(
                model=settings.anthropic_fast_model,
                call_type="context_summary",
                input_tokens=0,
                output_tokens=0,
                duration_seconds=time.monotonic() - t0,
                status="error",
            )
            raise

        record_llm_usage(
            model=settings.anthropic_fast_model,
            call_type="context_summary",
            duration_seconds=time.monotonic() - t0,
            **usage_kwargs(response.usage),
        )

        # The first text block, as the context router reads it too — a model that
        # answered with nothing but whitespace is the same as a model that failed.
        return "".join(getattr(block, "text", "") for block in response.content).strip()


context_summarizer = ContextSummarizer()

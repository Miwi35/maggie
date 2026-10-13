"""The owner's thumbs, read back as behaviour directives Maggie *proposes* (MAG-23).

A thumb up or down on an answer says something about how she talks — too long, too
formal, just right. Once enough of them have piled up, the fast model reads them along
with what was asked and answered, and suggests a few directives of the `behavior` kind.

None of them is applied: they are stored as proposals, and only the owner's « Accepter »
turns one into a directive (spec « Personnalité chaleureuse » — rien n'est appliqué
d'office). A refused proposal is kept and shown to the next synthesis, so it is not
offered again.

Best-effort like the context summary: it runs in the background, nobody waits on it,
and a failure leaves the thumbs unread for the next pass rather than raising.
"""

import json
import logging
import time

from app.config import settings
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.message_repository import message_repo
from app.db.style_proposal_model import StyleProposal, StyleProposalStatus
from app.db.style_proposal_repository import style_proposal_repo
from app.db.user_setting_repository import user_setting_repo
from app.llm.client import create_llm_client, llm_configured
from app.metrics import record_llm_usage, usage_kwargs

logger = logging.getLogger(__name__)

MAX_PROPOSALS = 3
MAX_SYNTHESIS_TOKENS = 600
# Each exchange is cut: what a style judgement needs is the tone and the length, which the
# first lines show, and a pasted document must not make the call bigger than the thumbs.
MAX_EXCHANGE_CHARS = 500
# How many rated answers one pass reads at most; anything older waits for the next pass.
MAX_RATED_PER_PASS = 40

SYSTEM_PROMPT = (
    "Tu analyses les retours d'un utilisateur sur les réponses de son assistante personnelle, "
    "pour en tirer des directives de style.\n"
    "Chaque retour est un pouce haut (la réponse lui a plu) ou un pouce bas (elle ne lui a pas plu), "
    "parfois avec un commentaire.\n"
    "Propose au plus 3 directives sur la manière de répondre (ton, longueur, tutoiement, emojis, "
    "structure), chacune à l'impératif, en une phrase, adressée à l'assistante (« Réponds en deux phrases "
    "au plus », « Évite les listes à puces »). Uniquement ce que plusieurs retours ou un commentaire "
    "explicite justifient ; rien sur le fond des réponses ni sur un sujet précis.\n"
    "Ne propose rien que les directives actuelles ou les propositions déjà faites disent déjà, "
    "ni rien qui contredise une proposition refusée.\n"
    'Réponds uniquement par un tableau JSON : [{"directive": "…", "raison": "…"}], la raison en une '
    "ligne citant les retours qui la fondent. Rien à proposer : []."
)


def _cut(text: str | None) -> str:
    text = (text or "").strip()
    return text if len(text) <= MAX_EXCHANGE_CHARS else text[:MAX_EXCHANGE_CHARS] + " […]"


def _parse(answer: str) -> list[dict]:
    """The proposals in the model's answer — the JSON array, wherever it sits in the text."""
    start, end = answer.find("["), answer.rfind("]")
    if start == -1 or end < start:
        raise ValueError(f"No JSON array in the style synthesis answer: {answer[:200]!r}")
    items = json.loads(answer[start : end + 1])
    if not isinstance(items, list):
        raise ValueError("The style synthesis answer is not a list")
    proposals = []
    for item in items:
        if not isinstance(item, dict):
            continue
        content = str(item.get("directive") or "").strip()
        if content:
            proposals.append({"content": content, "reason": str(item.get("raison") or "").strip() or None})
    return proposals[:MAX_PROPOSALS]


class StyleSynthesizer:
    """Turns a user's new thumbs into pending style proposals, with the cheap model."""

    def __init__(self):
        self.client = create_llm_client() if llm_configured() else None

    async def synthesize(self, user_id: str, *, min_feedback: int | None = None) -> list[StyleProposal]:
        """Read the thumbs given since the last pass and store what the model proposes.

        Returns the proposals stored — none when there were too few new thumbs, the model
        saw nothing to propose, or anything failed. Never raises.
        """
        if self.client is None:
            return []
        threshold = settings.style_synthesis_min_feedback if min_feedback is None else min_feedback

        try:
            setting = await user_setting_repo.get(user_id)
            since = setting.style_synthesized_at if setting else None
            rated = await message_repo.find_rated(user_id, since=since, limit=MAX_RATED_PER_PASS)
            if not rated or len(rated) < threshold:
                return []

            exchanges = []
            for answer in rated:
                question = await message_repo.find_question_before(answer)
                thumb = "pouce haut" if answer.feedback == "up" else "pouce bas"
                lines = [f"Retour : {thumb}"]
                if answer.feedback_comment:
                    lines.append(f"Commentaire : {_cut(answer.feedback_comment)}")
                if question is not None:
                    lines.append(f"Utilisateur : {_cut(question.content)}")
                lines.append(f"Assistante : {_cut(answer.content)}")
                exchanges.append("\n".join(lines))

            directives = await instruction_repo.find_by_user(user_id, kind=InstructionKind.BEHAVIOR)
            proposals = await style_proposal_repo.find_by_user(user_id)
            proposed = await self._ask(
                exchanges,
                current=[d.content for d in directives],
                pending=[p.content for p in proposals if p.status != StyleProposalStatus.REJECTED],
                rejected=[p.content for p in proposals if p.status == StyleProposalStatus.REJECTED],
            )

            stored = [await style_proposal_repo.store(user_id, p["content"], p["reason"]) for p in proposed]
            # Up to the last thumb read, not "now": a thumb given during the call is not read yet.
            await user_setting_repo.set_style_synthesized_at(user_id, rated[-1].feedback_at)
            logger.info(f"Style synthesis for {user_id}: {len(rated)} thumbs read, {len(stored)} proposals")
            return stored
        except Exception as e:
            logger.warning(f"Style synthesis failed for user {user_id}: {e}")
            return []

    async def _ask(self, exchanges: list[str], *, current: list[str], pending: list[str], rejected: list[str]) -> list[dict]:
        parts = []
        if current:
            parts.append("Directives actuelles :\n" + "\n".join(f"- {c}" for c in current))
        if pending:
            parts.append("Déjà proposées :\n" + "\n".join(f"- {c}" for c in pending))
        if rejected:
            parts.append("Refusées par l'utilisateur :\n" + "\n".join(f"- {c}" for c in rejected))
        parts.append("Retours :\n\n" + "\n\n".join(exchanges))

        t0 = time.monotonic()
        try:
            response = await self.client.messages.create(
                model=settings.anthropic_fast_model,
                max_tokens=MAX_SYNTHESIS_TOKENS,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": "\n\n".join(parts)}],
            )
        except Exception:
            record_llm_usage(
                model=settings.anthropic_fast_model,
                call_type="style_synthesis",
                input_tokens=0,
                output_tokens=0,
                duration_seconds=time.monotonic() - t0,
                status="error",
            )
            raise

        record_llm_usage(
            model=settings.anthropic_fast_model,
            call_type="style_synthesis",
            duration_seconds=time.monotonic() - t0,
            **usage_kwargs(response.usage),
        )
        return _parse("".join(getattr(block, "text", "") for block in response.content))


style_synthesizer = StyleSynthesizer()

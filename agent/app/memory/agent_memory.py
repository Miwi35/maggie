import asyncio
import logging
from dataclasses import dataclass
from datetime import date, datetime

from app.config import settings
from app.db.memory_index_repository import CardView
from app.memory import legacy
from app.memory.recall import Recalled, recall_for_turn
from app.memory.service import memory_service
from app.metrics import MEMORY_BLOCK_TOKENS

logger = logging.getLogger(__name__)

MAX_RESUME = 300
MAX_MOMENTS = 2
MAX_MOMENT = 140
MAX_PEOPLE = 4

PROFILE_HEAD = (
    "Ton profil : ce que tu sais d'essentiel, toujours là (une fiche par ligne). Ouvre la fiche qui concerne "
    "la question avec memory_open avant d'affirmer quoi que ce soit, et cherche avec memory_search — en "
    "choisissant des termes associés — ce qui n'est pas dans cette liste. Une fiche marquée ❓ porte un doute "
    "que tu poses au propriétaire quand le sujet s'y prête, puis tu la réécris avec sa réponse :"
)
RECALLED_HEAD = (
    "Souvenirs revenus à propos de ce que dit le propriétaire (la carte seule : ouvre la fiche avec "
    "memory_open si tu as besoin du détail). Appuie-toi dessus avant de poser une question ou de chercher : "
    "une habitude répond à la question, un goût oriente la recherche, jamais vers ce qui n'a aucun rapport. "
    "Ne les cite pas s'ils ne changent rien à ta réponse :"
)


@dataclass(frozen=True)
class MemoryBlocks:
    """What memory adds to a prompt: the notice (volatile), the permanent profile and the recalled cartes."""

    notice: str = ""
    profile: str = ""
    recalled: str = ""


async def _nothing() -> list[Recalled]:
    return []


def _estimated_tokens(text: str) -> int:
    return len(text) // 4


def _short(text: str, limit: int) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def render_recalled(found: list[Recalled]) -> str:
    """The cartes that came back, about 80 tokens each: where, what it is, who, and its latest moments."""
    if not found:
        return ""
    lines = [RECALLED_HEAD]
    for card in found:
        line = f"- {card.path}"
        if card.resume:
            line += f" — {_short(card.resume, MAX_RESUME)}"
        if card.personnes:
            line += f" Avec : {', '.join(card.personnes[:MAX_PEOPLE])}."
        for moment in card.moments[-MAX_MOMENTS:]:
            line += f" Moment : {_short(moment, MAX_MOMENT)}."
        lines.append(line)
    return "\n".join(lines)


def render_profile(cards: list[CardView], usage: dict[str, int], limit: int) -> str:
    """The permanent profile, written from the fiches: the most used first to stay, listed by path.

    Chosen by use (what Maggie opened most), listed by path: the text only changes when the set of fiches
    kept or one of them changes, so the cached block stays cached from one turn to the next.
    """
    fiches = [c for c in cards if c.kind == "fiche" and not c.archived]
    if not fiches:
        return ""
    kept = sorted(fiches, key=lambda c: (-usage.get(c.card_id, 0), c.path))[:limit]
    lines = [PROFILE_HEAD]
    for card in sorted(kept, key=lambda c: c.path):
        line = f"- {card.path}" + (f" — {_short(card.resume, MAX_RESUME)}" if card.resume else "")
        if card.question:
            ask = _short(card.question, MAX_RESUME)
            line += f" ❓ À demander au propriétaire, à la prochaine occasion naturelle : {ask}"
        lines.append(line)
    if len(fiches) > len(kept):
        lines.append(f"- … et {len(fiches) - len(kept)} autres : memory_search.")
    return "\n".join(lines)


class AgentMemory:
    """What the prompt says of the memory folder: the permanent profile, what came back, and whether it is in step."""

    async def get_memory_context(self, user_id: str) -> str:
        """The profile and the notice as one text — for the sub-agents, which have no recall of their own."""
        blocks = await self.blocks(user_id)
        return "".join(f"\n\n{part}" for part in (blocks.profile,) if part) + blocks.notice

    async def blocks(
        self,
        user_id: str,
        text: str = "",
        *,
        source: str = "chat",
        context_id: str | None = None,
        today: date | None = None,
    ) -> MemoryBlocks:
        """Levels 1 and 2 of the recall for one prompt: the profile, and the cartes `text` calls back (bounded)."""
        notice = await self._folder_notice(user_id)
        if memory_service.enabled:
            await legacy.import_once(user_id)
        if not memory_service.enabled:
            return MemoryBlocks(notice=notice)
        # Level 2 is bounded on its own (`memory_recall_timeout_seconds`) and runs beside the profile read, so the
        # two never add up. Catching the folder up with its bucket (`_folder_notice`) keeps its own timeout.
        profile, found = await asyncio.gather(
            self._profile(user_id),
            recall_for_turn(user_id, text, today or datetime.now().date(), source=source, context_id=context_id)
            if text.strip()
            else _nothing(),
        )
        recalled = render_recalled(found)
        MEMORY_BLOCK_TOKENS.labels(block="profile").observe(_estimated_tokens(profile))
        MEMORY_BLOCK_TOKENS.labels(block="recalled").observe(_estimated_tokens(recalled))
        return MemoryBlocks(notice=notice, profile=profile, recalled=recalled)

    async def _profile(self, user_id: str) -> str:
        """The permanent profile, read from the index: a fiche written one turn ago is there."""
        try:
            cards, usage = await asyncio.wait_for(
                asyncio.gather(memory_service.cards(user_id), memory_service.usage(user_id)),
                settings.memory_turn_sync_timeout_seconds,
            )
            return render_profile(cards, usage, settings.memory_profile_max)
        except Exception as e:
            logger.warning(f"Could not build the memory profile: {e}")
            return ""

    async def _folder_notice(self, user_id: str) -> str:
        """Catch the memory folder up with its bucket, then say so if it could not be (never fails a turn)."""
        try:
            await memory_service.before_turn(user_id)
            notice = await memory_service.notice(user_id)
        except Exception as e:
            logger.warning(f"Could not check the memory folder: {e}")
            return ""
        return f"\n\n{notice}" if notice else ""

"""Forgetting what was only circumstance: set aside, never deleted."""

from datetime import timedelta

from app.config import settings
from app.db.memory_index_repository import IndexedFile, memory_index_repo
from app.db.memory_night_repository import memory_night_repo
from app.memory import carte as carte_mod
from app.memory import misses
from app.memory.carte import ARCHIVED_KEY
from app.memory.encoder import ProposalError
from app.memory.night_passes.common import (
    FORGET_BATCH,
    SCOPE_FORGOTTEN,
    SCOPE_TIDIED,
    Pass,
    _claims_text,
    _dicts,
    _line,
    last_claim_date,
)

FORGET_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : tu décides ce qui n'a plus à être évoqué spontanément. "
    "Une fiche qui n'était que de circonstance (un détail d'un soir, un projet passé, un rendez-vous dépassé) "
    "est mise de côté : le fichier et la conversation d'origine restent, Maggie ne s'en sert plus d'elle-même.\n"
    "Tu juges d'après le contenu et ces signaux : l'âge de la dernière affirmation, le nombre de fois où Maggie a "
    "ouvert la fiche, ce qui la pointe. Un fait durable (un goût, une personne, une habitude, un lieu) reste, même "
    "ancien ou jamais ouvert. En cas de doute, garde.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"decisions": [{"chemin": "…", "action": "garder|oublier", "motif": "…"}]}'
)


async def run(p: Pass) -> None:
    cutoff = p.today - timedelta(days=settings.memory_night_forget_after_days)
    week = "-".join(str(x) for x in p.today.isocalendar()[:2])
    seen = await memory_night_repo.seen(p.user_id, SCOPE_FORGOTTEN)
    tidied = await memory_night_repo.seen(p.user_id, SCOPE_TIDIED)
    usage = await p.service.usage(p.user_id)
    # A fiche the owner expected Maggie to remember is not circumstance: its case keeps it out of the forgetting.
    expected = {c.attendue_id for c in await misses.cases(p.service, p.user_id) if c.attendue_id}
    candidates = []
    for fiche in await p.service.fiches(p.user_id):
        last = last_claim_date(fiche)
        if fiche.archived or fiche.unreadable or last is None or last > cutoff:
            continue
        if fiche.card_id in expected:
            continue
        if seen.get(fiche.card_id) == f"{fiche.content_hash}:{week}":
            continue
        candidates.append((fiche, last))
    for start in range(0, len(candidates), FORGET_BATCH):
        batch = candidates[start : start + FORGET_BATCH]
        lines = []
        for fiche, last in batch:
            pointers = len(await memory_index_repo.linked_from(p.user_id, fiche.card_id))
            lines.append(
                f"### {fiche.path}\n{fiche.resume}\n{_claims_text(fiche)}\n"
                f"Signaux : dernière affirmation il y a {(p.today - last).days} jours · ouverte "
                f"{usage.get(fiche.card_id, 0)} fois · pointée par {pointers} fichier(s)"
            )
        try:
            proposal = await p.ask(
                FORGET_SYSTEM, "Fiches à juger :\n\n" + "\n\n".join(lines) + "\n\nRéponds par le JSON."
            )
        except ProposalError as e:
            p.failed(f"oubli:{start}", str(e))
            continue
        by_path = {f.path: f for f, _ in batch}
        for decision in _dicts(proposal.get("decisions")):
            if _line(decision.get("chemin")) not in by_path:
                continue
            if _line(decision.get("action")).casefold() not in ("oublier", "archiver", "mettre de côté"):
                continue
            await _archive(p, by_path[_line(decision["chemin"])], _line(decision.get("motif")), tidied)
        for fiche, _ in batch:
            current = await p.service.indexed(p.user_id, fiche.path)
            digest = (current or fiche).content_hash
            await memory_night_repo.mark_seen(p.user_id, fiche.card_id, SCOPE_FORGOTTEN, f"{digest}:{week}")


async def _archive(p: Pass, fiche: IndexedFile, why: str, tidied: dict[str, str]) -> None:
    """Out of the profile and the spontaneous recall; the file, its sources and the search stay."""
    text = await p.service.read(p.user_id, fiche.path) or ""
    parsed = carte_mod.parse(text)
    parsed.carte.extra[ARCHIVED_KEY] = p.today.isoformat()
    await p.service.write(
        p.user_id,
        fiche.path,
        carte_mod.render(parsed.carte, parsed.body),
        reason=p.reason(f"mise de côté, plus évoquée d'elle-même — {why or 'détail de circonstance'}"),
    )
    current = await p.service.indexed(p.user_id, fiche.path)
    if current is not None and tidied.get(fiche.card_id) == fiche.content_hash:
        await memory_night_repo.mark_seen(p.user_id, fiche.card_id, SCOPE_TIDIED, current.content_hash)
    p.note("archivees", {"chemin": fiche.path, "motif": why})

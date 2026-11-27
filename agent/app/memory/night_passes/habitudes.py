"""Once a week, the habits that the agenda, the errands, the meals and the transactions show (MAG-18)."""

import hashlib
from typing import Any

from app.config import settings
from app.db.memory_index_repository import IndexedFile
from app.db.memory_night_repository import memory_night_repo
from app.memory import carte as carte_mod
from app.memory import fiches, habits, paths
from app.memory.carte import UnreadableFile
from app.memory.encoder import ProposalError
from app.memory.night_passes.common import (
    FICHES_LISTED,
    HABIT_FICHE_CLAIMS,
    HABITS_WEEK,
    MAX_PROPOSALS,
    SCOPE_HABITS,
    Pass,
    _dicts,
    _fiche_line,
    _items,
    _line,
    _stem,
    _strings,
)
from app.memory.proposals import union_terms


async def run(p: Pass) -> None:
    """Once per ISO week: what the agenda, the errands, the meals and the transactions show, as `observé` fiches."""
    iso = p.today.isocalendar()
    week = f"{iso.year}-W{iso.week:02d}"
    seen = await memory_night_repo.seen(p.user_id, SCOPE_HABITS)
    if seen.get(HABITS_WEEK) == week:
        return
    try:
        await p.keeper.mcp.ensure_connected()
        obs = await habits.observe(p.keeper.mcp, p.user_id, p.today, p.tz)
    except Exception as e:
        p.failed("habitudes", f"données illisibles : {e}")
        return
    if not obs.ok:
        p.failed("habitudes", "aucune donnée de l'application n'a répondu")
        return
    listed = await p.service.fiches(p.user_id)
    mine = habits.existing_habits([f for f in listed if not f.archived and not f.unreadable])
    if obs.items or mine:
        others = "\n".join(_fiche_line(f) for f in listed[:FICHES_LISTED] if f not in mine)
        request = habits.build_request(obs, habits.existing_block(mine), others)
        system = habits.HABITS_SYSTEM.replace("{proofs}", str(settings.memory_habits_min_proofs))
        try:
            proposal = await p.ask(system, request)
        except ProposalError as e:
            p.failed("habitudes", str(e))
            return
        for item in _items(proposal.get("habitudes"))[:MAX_PROPOSALS]:
            try:
                await _write_habit(p, obs, item, mine, seen)
            except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                p.note("laissees", {"unite": "habitude", "pourquoi": str(e)})
        for item in _dicts(proposal.get("cessees"))[:MAX_PROPOSALS]:
            try:
                await _cease_habit(p, obs, item, mine)
            except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
                p.note("laissees", {"unite": "habitude cessée", "pourquoi": str(e)})
    if obs.down:
        # A module that did not answer is read again tomorrow, not in a week.
        p.note("reste", f"habitudes : {', '.join(sorted(obs.down))} illisible(s), relu(s) à la prochaine nuit")
        return
    await memory_night_repo.mark_seen(p.user_id, HABITS_WEEK, SCOPE_HABITS, week)


async def _write_habit(
    p: Pass, obs: habits.Observation, item: Any, mine: list[IndexedFile], seen: dict[str, str]
) -> None:
    if not isinstance(item, dict):
        return
    title = habits.clean(_line(item.get("titre")), 160)
    if not title:
        return
    service, user_id = p.service, p.user_id
    folder = service.folder(user_id)
    wanted = fiches.norm(title)
    by_path = {f.path: f for f in mine}
    found = by_path.get(_line(item.get("chemin"))) or next(
        (f for f in mine if wanted in {c.key for c in fiches.parse_body(f.body).known}), None
    )
    # A habit already proved only needs the new proofs it is given; a new one needs the minimum.
    proven = found is not None and wanted in {c.key for c in fiches.parse_body(found.body).known}
    proofs, why = habits.proofs_of(obs, item.get("preuves"), needed=1 if proven else settings.memory_habits_min_proofs)
    if why:
        p.note("laissees", {"unite": title, "pourquoi": why})
        return
    deleted_key = "habit:" + hashlib.sha1(wanted.encode()).hexdigest()[:16]
    dossiers = _strings(item.get("dossiers"))
    if found is not None:
        rel = found.path
    else:
        if len(dossiers) > fiches.MAX_FOLDERS:
            raise fiches.FicheError(f"At most {fiches.MAX_FOLDERS} nested folders.")
        rel = paths.fiche_relative(dossiers, title, [])
    exists = folder.exists(rel)
    if not exists and deleted_key in seen:
        p.note("laissees", {"unite": title, "pourquoi": "fiche supprimée par le propriétaire : non récrite"})
        return
    parsed = carte_mod.parse(await service.read(user_id, rel) or "") if exists else None
    before = fiches.parse_body(parsed.body) if parsed else fiches.Body()
    if wanted in {c.key for c in before.replaced}:
        p.note("laissees", {"unite": title, "pourquoi": "déjà marquée remplacée dans la fiche : non récrite"})
        return

    new = habits.habit_claims(obs, title, proofs)
    held = {c.key for c in before.known}
    added = []
    for claim in new:
        key = fiches.norm(claim["texte"])
        if key not in held and key not in {fiches.norm(a["texte"]) for a in added}:
            added.append(claim)
    added = added[: max(0, HABIT_FICHE_CLAIMS - len(held))]
    if not added:
        return
    period = new[0]["source"]
    affirmations = [
        {
            "texte": c.texte,
            "provenance": c.provenance,
            "source": period if c.key == wanted and fiches.is_observed_regularity(c) else c.source,
            "heure": c.heure,
        }
        for c in before.known
    ] + added
    evoque = union_terms(parsed.carte.evoque, _strings(item.get("evoque"))) if parsed else _strings(item.get("evoque"))
    resume = (parsed.carte.resume if parsed else "") or _line(item.get("resume")) or title
    written = await fiches.write_fiche(
        service,
        user_id,
        titre=_stem(rel) if exists else title,
        affirmations=affirmations,
        resume=resume,
        dossiers=None if exists else dossiers,
        chemin=rel if exists else None,
        evoque=evoque or None,
        today=p.today,
        reason=p.reason(f"{len(proofs)} donnée(s) lue(s) dans l'application ({_period_of(obs)})"),
    )
    if not exists:
        await memory_night_repo.mark_seen(
            user_id, deleted_key, SCOPE_HABITS, hashlib.sha1(written["chemin"].encode()).hexdigest()[:16]
        )
    p.note("habitudes", {"chemin": written["chemin"], "preuves": len(proofs)})


def _period_of(obs: habits.Observation) -> str:
    return f"du {obs.start.isoformat()} au {obs.today.isoformat()}"


async def _cease_habit(p: Pass, obs: habits.Observation, item: dict[str, Any], mine: list[IndexedFile]) -> None:
    """A habit the data no longer show: dated and marked replaced, never erased."""
    chemin, texte = _line(item.get("chemin")), _line(item.get("texte"))
    fiche = next((f for f in mine if f.path == chemin), None)
    if fiche is None:
        p.note("laissees", {"unite": chemin or "habitude cessée", "pourquoi": "pas une fiche d'habitude connue"})
        return
    parsed = carte_mod.parse(await p.service.read(p.user_id, fiche.path) or "")
    before = fiches.parse_body(parsed.body)
    claim = next((c for c in before.known if c.key == fiches.norm(texte)), None)
    if claim is None or not fiches.is_observed_regularity(claim):
        why = f"«{texte}» n'est pas une habitude lue dans les données"
        p.note("laissees", {"unite": fiche.path, "pourquoi": why})
        return
    silent = habits.unreadable_for(obs, claim)
    if silent:
        why = f"{', '.join(silent)} illisible cette nuit : habitude gardée"
        p.note("laissees", {"unite": fiche.path, "pourquoi": why})
        return
    motif = _line(item.get("motif")) or "les données de la période ne la montrent plus"
    await fiches.write_fiche(
        p.service,
        p.user_id,
        titre=_stem(fiche.path),
        affirmations=[
            {"texte": c.texte, "provenance": c.provenance, "source": c.source, "heure": c.heure}
            for c in before.known
            if c.key != claim.key
        ],
        remplacees=[{"texte": claim.texte, "motif": motif}],
        chemin=fiche.path,
        today=p.today,
        reason=p.reason(f"habitude qui cesse ({motif})"),
    )
    p.note("habitudes_cessees", {"chemin": fiche.path, "texte": claim.texte})

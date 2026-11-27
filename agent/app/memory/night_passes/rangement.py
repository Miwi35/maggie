"""The tidying: rename, move, merge the duplicates, split the catch-alls."""

from typing import Any

from app.db.memory_index_repository import IndexedFile, memory_index_repo
from app.db.memory_night_repository import memory_night_repo
from app.memory import carte as carte_mod
from app.memory import fiches, misses, paths
from app.memory.carte import ARCHIVED_KEY, QUESTION_KEY, Carte, UnreadableFile
from app.memory.encoder import ProposalError
from app.memory.night_passes.common import (
    FICHES_LISTED,
    FICHES_OPENED,
    MAX_MERGED,
    MAX_OPERATIONS,
    MAX_TIDY_BATCHES,
    SCOPE_TIDIED,
    Pass,
    _claims_text,
    _dicts,
    _directory,
    _fiche_line,
    _items,
    _line,
    _stem,
    _strings,
    _union_sources,
    _with,
    rewrite_carte,
)
from app.memory.proposals import union_terms

TIDY_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : tu ranges les fiches. Une fiche est un fichier dont le "
    "nom est une phrase vraie, dans des dossiers peu profonds choisis par sujet.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"operations": [{"op": "renommer|deplacer|fusionner|scinder", "chemin": "…", "titre": "…", "dossiers": ["…"], '
    '"chemins": ["…"], "resume": "…", "parties": [{"titre": "…", "dossiers": ["…"], "resume": "…", '
    '"affirmations": ["texte exact d\'une affirmation de la fiche"]}], "motif": "…"}]}\n'
    "- renommer : `chemin`, nouveau `titre` (le nom ne dit pas ce que la fiche dit : une phrase vraie). deplacer : "
    "`chemin`, nouveaux `dossiers` (3 niveaux au plus ; `[]` = à la racine).\n"
    "- fusionner : `chemins` (2 à 4 fiches qui disent la même chose ou se complètent), le premier est gardé ; "
    "`titre` et `resume` facultatifs pour la fiche fusionnée. "
    "Rien ne se perd : toutes les affirmations sont reprises.\n"
    "- scinder : une fiche fourre-tout (`chemin`) en `parties`, chacune avec son `titre`, ses `dossiers` et les "
    "`affirmations` (texte exact) qu'elle reprend ; ce qui n'est dans aucune partie reste dans la fiche. Une "
    "affirmation ne va que dans UNE partie.\n"
    "Ne touche à rien qui est bien rangé. "
    "Les fiches marquées * sont celles qui ont changé depuis le dernier rangement. "
    "Si tout est bien rangé, `operations` reste vide."
)


async def run(p: Pass) -> None:
    for _ in range(MAX_TIDY_BATCHES):
        if not await _tidy_batch(p):
            return
    p.note("reste", "rangement : des fiches changées attendent la prochaine nuit")


async def _tidy_batch(p: Pass) -> bool:
    """Ask for the tidying of the next fiches that changed. True when some are still waiting for their turn."""
    listed = [f for f in await p.service.fiches(p.user_id) if not f.unreadable]
    seen = await memory_night_repo.seen(p.user_id, SCOPE_TIDIED)
    changed = [f for f in listed if seen.get(f.card_id) != f.content_hash]
    if not changed:
        return False
    shown = changed[:FICHES_OPENED]
    changed_paths = {f.path for f in changed}
    lines = [
        ("* " if f.path in changed_paths else "  ") + _fiche_line(f).removeprefix("- ") for f in listed[:FICHES_LISTED]
    ]
    detail = "\n\n".join(f"### {f.path}\n{_claims_text(f)}" for f in shown)
    request = (
        "Fiches :\n" + "\n".join(lines) + "\n\nContenu des fiches changées :\n" + detail + "\n\nRéponds par le JSON."
    )
    try:
        proposal = await p.ask(TIDY_SYSTEM, request)
    except ProposalError as e:
        p.failed("rangement", str(e))
        return False
    for op in _items(proposal.get("operations"))[:MAX_OPERATIONS]:
        try:
            await _tidy(p, op)
        except (fiches.FicheError, paths.UnsafePath, UnreadableFile) as e:
            p.note("laissees", {"unite": "rangement", "pourquoi": str(e)})
    # Only what the model was shown is done: the others keep their turn, tonight or the next night.
    looked_at = {f.card_id for f in shown}
    await _mark_tidied(p, [f for f in await p.service.fiches(p.user_id) if f.card_id in looked_at])
    return len(changed) > len(shown)


async def _mark_tidied(p: Pass, listed: list[IndexedFile]) -> None:
    for fiche in listed:
        await memory_night_repo.mark_seen(p.user_id, fiche.card_id, SCOPE_TIDIED, fiche.content_hash)


async def _tidy(p: Pass, op: Any) -> None:
    if not isinstance(op, dict):
        return
    kind = _line(op.get("op")).casefold()
    why = _line(op.get("motif"))
    if kind in ("renommer", "deplacer", "déplacer"):
        chemin = await _existing_fiche(p, op.get("chemin"))
        dossiers = _strings(op.get("dossiers")) if isinstance(op.get("dossiers"), list) else None
        new = await _relocate(p, chemin, _line(op.get("titre")) or None, dossiers, why)
        if new is not None:
            p.note("renommees" if kind == "renommer" else "deplacees", {"de": chemin, "a": new})
    elif kind == "fusionner":
        await _merge(p, op, why)
    elif kind == "scinder":
        await _split(p, op, why)


async def _existing_fiche(p: Pass, chemin: Any) -> str:
    rel = paths.clean_relative(_line(chemin))
    indexed = await p.service.indexed(p.user_id, rel)
    if indexed is None or indexed.kind != "fiche" or indexed.unreadable:
        raise fiches.FicheError(f"«{rel}» n'est pas une fiche connue")
    return rel


async def _relocate(p: Pass, old: str, titre: str | None, dossiers: list[str] | None, why: str) -> str | None:
    folder = p.service.folder(p.user_id)
    new_dirs = dossiers if dossiers is not None else _directory(old)
    if len(new_dirs) > fiches.MAX_FOLDERS:
        raise fiches.FicheError(f"At most {fiches.MAX_FOLDERS} nested folders.")
    new_title = titre or _stem(old)
    new = paths.fiche_relative(new_dirs, new_title, [f for f in folder.files() if f != old])
    if new == old:
        return None
    await p.service.move(p.user_id, old, new, reason=p.reason("rangement" + (f" — {why}" if why else "")))
    if titre and _stem(new) != _stem(old):
        await rewrite_carte(
            p,
            new,
            lambda c: _with(c, alias=union_terms(c.alias, [_stem(old)])),
            p.reason(f"ancien titre gardé en alias — {_stem(old)}"),
        )
    return new


async def _merge(p: Pass, op: dict[str, Any], why: str) -> None:
    wanted = list(dict.fromkeys(_strings(op.get("chemins"))))
    if not 2 <= len(wanted) <= MAX_MERGED:
        raise fiches.FicheError("Une fusion porte sur 2 à 4 fiches.")
    rels = [await _existing_fiche(p, rel) for rel in wanted]
    keep, others = rels[0], rels[1:]
    parsed = {rel: carte_mod.parse(await p.service.read(p.user_id, rel) or "") for rel in rels}
    bodies = {rel: fiches.parse_body(parsed[rel].body) for rel in rels}
    indexed = {rel: await p.service.indexed(p.user_id, rel) for rel in rels}

    known: dict[str, fiches.Claim] = {}
    replaced: dict[str, fiches.Claim] = {}
    rest: list[str] = []
    for rel in rels:
        for claim in bodies[rel].known:
            known.setdefault(claim.key, claim)
        for claim in bodies[rel].replaced:
            replaced.setdefault(claim.key, claim)
        if bodies[rel].rest:
            rest += ([""] if rest else []) + bodies[rel].rest
    for key in [k for k in replaced if k in known]:
        del replaced[key]

    base = parsed[keep].carte
    merged = Carte(
        id=base.id,
        resume=_line(op.get("resume"))
        or base.resume
        or next((parsed[r].carte.resume for r in others if parsed[r].carte.resume), ""),
        moments=union_terms(base.moments, [m for r in others for m in parsed[r].carte.moments], 50),
        personnes=union_terms(base.personnes, [x for r in others for x in parsed[r].carte.personnes]),
        evoque=union_terms(base.evoque, [x for r in others for x in parsed[r].carte.evoque]),
        alias=union_terms(base.alias, [_stem(r) for r in others] + [x for r in others for x in parsed[r].carte.alias]),
        liens=list(dict.fromkeys([*base.liens, *(i for r in others for i in parsed[r].carte.liens)])),
        sources=_union_sources(base.sources, [s for r in others for s in parsed[r].carte.sources]),
        extra=dict(base.extra),
    )
    if any(ARCHIVED_KEY not in parsed[r].carte.extra for r in rels):
        merged.extra.pop(ARCHIVED_KEY, None)
    if QUESTION_KEY not in merged.extra:
        question = next(
            (parsed[r].carte.extra[QUESTION_KEY] for r in others if parsed[r].carte.extra.get(QUESTION_KEY)), None
        )
        if question:
            merged.extra[QUESTION_KEY] = question
    body = fiches.Body(known=list(known.values()), replaced=list(replaced.values()), rest=rest)
    await p.service.write(
        p.user_id,
        keep,
        carte_mod.render(merged, fiches.render_body(body)),
        reason=p.reason(f"fusion de {len(rels)} fiches — {why or 'elles disaient la même chose'}"),
    )
    survivor = await p.service.indexed(p.user_id, keep)
    for rel in others:
        old = indexed[rel]
        if old is not None and survivor is not None:
            await _repoint(p, old.card_id, survivor.card_id)
        await p.service.delete(p.user_id, rel, reason=p.reason(f"fusionnée dans « {_stem(keep)} »"))
    titre = _line(op.get("titre"))
    final = keep
    if titre and titre != _stem(keep):
        final = await _relocate(p, keep, titre, None, why) or keep
    p.note("fusions", {"dans": final, "absorbees": others})


async def _repoint(p: Pass, old_id: str, new_id: str) -> None:
    """What pointed at an absorbed fiche points at the one that took it in."""

    def swap(carte: Carte) -> Carte:
        carte.liens = list(dict.fromkeys(new_id if i == old_id else i for i in carte.liens))
        carte.sources = [{**s, "id": new_id} if s.get("id") == old_id else s for s in carte.sources]
        return carte

    for pointer in await memory_index_repo.linked_from(p.user_id, old_id):
        await rewrite_carte(p, pointer, swap, p.reason("lien suivi vers la fiche fusionnée"))
    await misses.repoint(p.service, p.user_id, old_id, new_id)


async def _split(p: Pass, op: dict[str, Any], why: str) -> None:
    rel = await _existing_fiche(p, op.get("chemin"))
    parts = _dicts(op.get("parties"))
    if not parts:
        raise fiches.FicheError("Une scission a besoin de parties.")
    parsed = carte_mod.parse(await p.service.read(p.user_id, rel) or "")
    body = fiches.parse_body(parsed.body)
    held = {c.key: c for c in body.known}
    taken: set[str] = set()
    planned: list[tuple[dict[str, Any], list[fiches.Claim]]] = []
    for part in parts:
        claims = []
        for text in _strings(part.get("affirmations")):
            claim = held.get(fiches.norm(text))
            if claim is None or claim.key in taken:
                raise fiches.FicheError(
                    f"«{text}» n'est pas une affirmation de la fiche, ou déjà prise par une autre partie"
                )
            taken.add(claim.key)
            claims.append(claim)
        if not claims or not _line(part.get("titre")):
            raise fiches.FicheError("Chaque partie a un titre et au moins une affirmation.")
        planned.append((part, claims))
    remaining = [c for c in body.known if c.key not in taken]
    if not remaining:
        raise fiches.FicheError("La fiche d'origine doit garder au moins une affirmation.")

    created: list[str] = []
    try:
        for part, claims in planned:
            dossiers = _strings(part.get("dossiers")) if isinstance(part.get("dossiers"), list) else _directory(rel)
            target = paths.fiche_relative(
                dossiers, _line(part["titre"]), [f for f in p.service.folder(p.user_id).files()]
            )
            entries = [await fiches.stored_entry(p.service, p.user_id, c) for c in claims]
            carte = Carte(
                resume=_line(part.get("resume")) or parsed.carte.resume,
                personnes=parsed.carte.personnes,
                evoque=parsed.carte.evoque,
                sources=_union_sources([], entries),
            )
            await p.service.write(
                p.user_id,
                target,
                carte_mod.render(carte, fiches.render_body(fiches.Body(known=claims))),
                reason=p.reason(f"scission de « {_stem(rel)} » — {why or 'fourre-tout'}"),
            )
            created.append(target)
    except Exception:
        for target in created:
            await p.service.delete(p.user_id, target, reason=p.reason("scission annulée"))
        raise
    entries = [await fiches.stored_entry(p.service, p.user_id, c) for c in remaining + body.replaced]
    carte = _with(parsed.carte, sources=_union_sources([], entries))
    await p.service.write(
        p.user_id,
        rel,
        carte_mod.render(
            carte, fiches.render_body(fiches.Body(known=remaining, replaced=body.replaced, rest=body.rest))
        ),
        reason=p.reason(f"scindée en {len(created) + 1} fiches — {why or 'fourre-tout'}"),
    )
    p.note("scissions", {"de": rel, "en": created})

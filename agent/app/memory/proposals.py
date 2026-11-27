"""The merge of a proposal into a fiche, shared by the encoder and the night (MAG-1051)."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from typing import Any, Literal

from app.memory import carte as carte_mod
from app.memory import fiches, paths
from app.memory.service import MemoryService

MAX_TERMS = 12


@dataclass(frozen=True)
class Merged:
    chemin: str
    kind: Literal["ecrites", "completees", "corrigees"]
    left: list[str]


def _one_line(value: Any) -> str:
    return " ".join(str(value or "").split())


def union_terms(current: list[str], new: list[str], limit: int = MAX_TERMS) -> list[str]:
    seen = {fiches.norm(item) for item in current}
    out = list(current)
    for item in new:
        if len(out) - len(current) >= limit:
            break
        if fiches.norm(item) not in seen:
            seen.add(fiches.norm(item))
            out.append(item)
    return out


def owner_claims(items: Any, *, source: str, owner_minutes: set[str], limit: int) -> list[dict[str, Any]]:
    """The claims of a proposal that the owner really said: his minute, his words, a text."""
    return [
        {
            "texte": _one_line(a.get("texte")),
            "provenance": "dit",
            "source": source,
            "heure": _one_line(a.get("heure")),
            "citation": _one_line(a.get("citation")),
        }
        for a in [i for i in (items if isinstance(items, list) else []) if isinstance(i, dict)][:limit]
        if _one_line(a.get("texte")) and _one_line(a.get("heure")) in owner_minutes
    ]


async def merge_into_fiche(
    service: MemoryService,
    user_id: str,
    *,
    titre: str,
    chemin: str | None,
    dossiers: list[str],
    resume: str | None,
    personnes: list[str],
    evoque: list[str],
    alias: list[str],
    claims: list[dict[str, Any]],
    replace: list[dict[str, Any]],
    today: date,
    reason: Callable[[int, int], str],
    refuse_replaced: bool = False,
    left: list[str] | None = None,
) -> Merged | None:
    """Create a fiche, complete it, or correct it. None when it would add nothing.

    A correction keeps the old wording: it goes to « Ce qui n'est plus vrai » dated today, with its reason. Raises
    `fiches.FicheError` when the replaced claim is more recent than what replaces it, or, with `refuse_replaced`,
    when a claim is one of those that are no longer true. `reason(claims written, claims replaced)` is the journal line.
    `left` is filled as the replacements not found in the fiche are met, so the caller has them even on a refusal.
    """
    folder = service.folder(user_id)
    if chemin is None:
        if not titre:
            return None
        resume = resume or titre
        if len(dossiers) > fiches.MAX_FOLDERS:
            raise fiches.FicheError(f"At most {fiches.MAX_FOLDERS} nested folders.")
        target = paths.fiche_relative(dossiers, titre, [])
        chemin = target if folder.exists(target) else None

    previous: list[dict[str, Any]] = []
    marks: list[dict[str, str]] = []
    left = [] if left is None else left
    if chemin is not None:
        parsed = carte_mod.parse(await service.read(user_id, chemin) or "")
        before = fiches.parse_body(parsed.body)
        if refuse_replaced:
            gone = {c.key for c in before.replaced}
            if any(fiches.norm(c["texte"]) in gone for c in claims):
                raise fiches.FicheError("l'affirmation figure parmi celles qui ne sont plus vraies")
        held = {c.key: c for c in before.known}
        newest = max((c["heure"] for c in claims if c.get("heure")), default="")
        for raw in replace:
            old = held.get(fiches.norm(_one_line(raw.get("texte"))))
            if old is None:
                wanted = fiches.norm(_one_line(raw.get("texte")))
                if wanted and wanted not in {c.key for c in before.replaced}:
                    left.append(_one_line(raw.get("texte")))
                continue
            if old.heure and newest and old.heure > newest:
                raise fiches.FicheError(
                    f"«{old.texte}» est plus récente ({old.heure}) que ce qui devrait la remplacer ({newest}) : laissée"
                )
            marks.append(
                {
                    "texte": old.texte,
                    "motif": _one_line(raw.get("motif")) or "remplacée par une affirmation plus récente",
                }
            )
        marked = {fiches.norm(m["texte"]) for m in marks}
        claims = [c for c in claims if fiches.norm(c["texte"]) not in held]
        if not claims:
            return None
        previous = [
            {"texte": c.texte, "provenance": c.provenance, "source": c.source, "heure": c.heure}
            for c in before.known
            if c.key not in marked
        ]
        personnes = union_terms(parsed.carte.personnes, personnes)
        evoque = union_terms(parsed.carte.evoque, evoque)
        alias = union_terms(parsed.carte.alias, alias)
        resume = (resume if marks and resume else None) or parsed.carte.resume or resume
        titre = chemin.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)

    written = await fiches.write_fiche(
        service,
        user_id,
        titre=titre,
        affirmations=previous + claims,
        remplacees=marks,
        resume=resume,
        dossiers=dossiers if chemin is None else None,
        chemin=chemin,
        personnes=personnes or None,
        evoque=evoque or None,
        alias=alias or None,
        today=today,
        reason=reason(len(claims), len(marks)),
    )
    kind = "ecrites" if written["cree"] else ("corrigees" if marks else "completees")
    return Merged(chemin=written["chemin"], kind=kind, left=left)

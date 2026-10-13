"""The second layer of the memory: fiches, what is known, each claim with its source.

A fiche is a Markdown file whose name is a true sentence (`Françoise n'aime pas les roses.md`), in
shallow folders Maggie chooses. Its body is two lists the code writes and reads back:

    ## Ce qu'on sait
    - Françoise n'aime pas les roses — dit · Conversations/2026/10/2026-10-04 18h14 — Cadeau.md · 2026-10-04 18h14

    ## Ce qui n'est plus vrai
    - ~~Françoise habite à Nantes~~ — dit · Conversations/… · 2026-09-01 10h00 — remplacé le 2026-10-13 : a déménagé

Maggie hands over structured claims, not Markdown: the code checks that every claim has a provenance
(`dit`, `observé`, `déduit`) and a source that can be opened, that the minute it cites is in that
conversation, and that nothing true vanishes from a version to the next — what stops being true is
dated and moved to the second list. Nothing here knows what a fiche is about: no category, no kind of
person; the folders and the titles are Maggie's.
"""

import re
import unicodedata
from dataclasses import dataclass, field
from datetime import date
from typing import Any

from app.memory import carte as carte_mod
from app.memory import paths
from app.memory.carte import PROVENANCES, Carte
from app.memory.service import MemoryService

KNOWN_HEADING = "Ce qu'on sait"
REPLACED_HEADING = "Ce qui n'est plus vrai"
APP_SOURCE = "app :"
LEGACY_SOURCE = "ancienne mémoire"
MAX_FOLDERS = 3
MAX_CLAIM = 600
MAX_TITLE = 160

_STAMP = re.compile(r"^\d{4}-\d{2}-\d{2} \d{1,2}h\d{2}$")
_LINE = re.compile(
    r"^- (?:~~(?P<old>.+?)~~|(?P<now>.+?)) — (?P<prov>dit|observé|déduit) · (?P<source>.+?)"
    r"(?: · (?P<heure>\d{4}-\d{2}-\d{2} \d{1,2}h\d{2}))?"
    r"(?: — remplacé le (?P<date>\d{4}-\d{2}-\d{2})(?: : (?P<motif>.*))?)?$"
)


class FicheError(ValueError):
    """What the model is told when a fiche cannot be written as asked: say what to change."""


@dataclass
class Claim:
    texte: str
    provenance: str
    source: str
    heure: str | None = None
    replaced_on: str | None = None
    motif: str = ""

    @property
    def key(self) -> str:
        return norm(self.texte)


@dataclass
class Body:
    known: list[Claim] = field(default_factory=list)
    replaced: list[Claim] = field(default_factory=list)
    rest: list[str] = field(default_factory=list)


def norm(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", text).casefold().split())


def _one_line(text: Any) -> str:
    return " ".join(str(text or "").split())


def render_claim(claim: Claim) -> str:
    text = f"~~{claim.texte}~~" if claim.replaced_on else claim.texte
    line = f"- {text} — {claim.provenance} · {claim.source}"
    if claim.heure:
        line += f" · {claim.heure}"
    if claim.replaced_on:
        line += f" — remplacé le {claim.replaced_on}"
        if claim.motif:
            line += f" : {claim.motif}"
    return line


def render_body(body: Body) -> str:
    parts = [f"## {KNOWN_HEADING}\n\n" + "\n".join(render_claim(c) for c in body.known)] if body.known else []
    if body.replaced:
        parts.append(f"## {REPLACED_HEADING}\n\n" + "\n".join(render_claim(c) for c in body.replaced))
    rest = "\n".join(body.rest).strip()
    if rest:
        parts.append(rest)
    return "\n\n".join(parts)


def parse_body(text: str) -> Body:
    """The claims of a fiche's body; what is not a claim (the owner's own lines) goes to `rest`, untouched."""
    body = Body()
    for raw in text.splitlines():
        line = raw.rstrip()
        if line.strip() in (f"## {KNOWN_HEADING}", f"## {REPLACED_HEADING}"):
            continue
        match = _LINE.match(line)
        if match is None:
            body.rest.append(line)
            continue
        claim = Claim(
            texte=match.group("old") or match.group("now"),
            provenance=match.group("prov"),
            source=match.group("source"),
            heure=match.group("heure"),
            replaced_on=match.group("date"),
            motif=match.group("motif") or "",
        )
        (body.replaced if match.group("old") else body.known).append(claim)
    while body.rest and not body.rest[0].strip():
        body.rest.pop(0)
    while body.rest and not body.rest[-1].strip():
        body.rest.pop()
    return body


def to_announce(text: str) -> list[str]:
    """The claims of a fiche that Maggie deduced herself: said as such whenever she uses them."""
    return [c.texte for c in parse_body(carte_mod.parse(text).body).known if c.provenance == "déduit"]


def _clean_source(source: str) -> str:
    return _one_line(source).replace(" · ", ", ").replace(" — ", ", ")


async def _check_source(
    service: MemoryService, user_id: str, claim: Claim, *, allow_legacy: bool
) -> tuple[str, dict[str, Any]]:
    """Prove the source of a claim can be opened. Returns the source as written and its entry for the carte."""
    source = claim.source
    folder = service.folder(user_id)
    if source.startswith(APP_SOURCE) or source.startswith("app:"):
        detail = _clean_source(source.split(":", 1)[1])
        if not detail:
            raise FicheError(f"The source of «{claim.texte}» says app but not which data.")
        if claim.provenance == "dit":
            raise FicheError(f"«{claim.texte}» is `dit`: a thing said has a conversation as its source, not app data.")
        return f"{APP_SOURCE} {detail}", {"titre": f"{APP_SOURCE} {detail}", "provenance": claim.provenance}
    if allow_legacy and source.startswith(LEGACY_SOURCE):
        text = _clean_source(source)
        return text, {"titre": text, "provenance": claim.provenance}
    try:
        rel = paths.clean_relative(source)
    except paths.UnsafePath as e:
        raise FicheError(f"The source of «{claim.texte}» is not a path of the memory folder: {e}") from e
    if not paths.is_indexable(rel) or not folder.exists(rel):
        raise FicheError(
            f"The source «{source}» of «{claim.texte}» does not exist. A source is the path of a conversation "
            f"or of a fiche you opened, or `{APP_SOURCE} <la donnée>` for app data. Search first."
        )
    if paths.kind_of(rel) == "conversation":
        heure = claim.heure
        if not heure or not _STAMP.match(heure):
            raise FicheError(
                f"«{claim.texte}» cites a conversation: give `heure` as it is in the file, `AAAA-MM-JJ HHhMM`."
            )
        if f"## {heure} — " not in (folder.read(rel) or ""):
            raise FicheError(
                f"There is no message at {heure} in {rel}: open it with memory_open and copy the minute of the message."
            )
    elif claim.provenance == "dit":
        raise FicheError(f"«{claim.texte}» is `dit`: its source is the conversation where it was said, not a fiche.")
    indexed = await service.indexed(user_id, rel)
    title = rel.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)
    entry: dict[str, Any] = {"titre": title, "provenance": claim.provenance}
    if indexed is not None:
        entry["id"] = indexed.card_id
    return rel, entry


async def _stored_entry(service: MemoryService, user_id: str, claim: Claim) -> dict[str, Any]:
    """The carte entry of a claim already accepted in an earlier version: its source is not asked again."""
    entry: dict[str, Any] = {"titre": claim.source, "provenance": claim.provenance}
    if paths.is_syncable(claim.source):
        try:
            rel = paths.clean_relative(claim.source)
        except paths.UnsafePath:
            return entry
        entry["titre"] = rel.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)
        indexed = await service.indexed(user_id, rel)
        if indexed is not None:
            entry["id"] = indexed.card_id
    return entry


def _claim(raw: Any) -> Claim:
    if not isinstance(raw, dict):
        raise FicheError("A claim is an object: {texte, provenance, source, heure}.")
    texte = _one_line(raw.get("texte"))
    if not texte:
        raise FicheError("A claim needs its `texte`.")
    if len(texte) > MAX_CLAIM:
        raise FicheError(f"«{texte[:40]}…» is too long: one claim is one short sentence.")
    provenance = _one_line(raw.get("provenance")).casefold()
    if provenance not in PROVENANCES:
        raise FicheError(f"«{texte}» needs a `provenance` among {', '.join(PROVENANCES)}.")
    source = _one_line(raw.get("source"))
    if not source:
        raise FicheError(f"«{texte}» needs its `source`: no source, no claim.")
    heure = _one_line(raw.get("heure")) or None
    return Claim(texte, provenance, source, heure)


def _list(value: Any) -> list[str] | None:
    if value is None:
        return None
    if not isinstance(value, list):
        raise FicheError("`personnes`, `evoque` and `alias` are lists of short strings.")
    return [s for s in (_one_line(item) for item in value) if s]


def _target(
    service: MemoryService, user_id: str, titre: str, dossiers: list[str], chemin: str | None
) -> tuple[str, bool]:
    """Where the fiche goes, and whether it already exists."""
    folder = service.folder(user_id)
    if chemin:
        try:
            rel = paths.check_writable(chemin)
        except paths.UnsafePath as e:
            raise FicheError(f"{chemin!r} cannot be written: {e}") from e
        if paths.kind_of(rel) != "fiche":
            raise FicheError("Only a fiche can be rewritten here.")
        if not folder.exists(rel):
            raise FicheError(f"{rel!r} does not exist: leave `chemin` out to create a new fiche.")
        return rel, True
    if len(dossiers) > MAX_FOLDERS:
        raise FicheError(f"At most {MAX_FOLDERS} nested folders: keep the tree shallow.")
    try:
        rel = paths.fiche_relative(dossiers, titre, [])
    except paths.UnsafePath as e:
        raise FicheError(str(e)) from e
    if rel.casefold() in {f.casefold() for f in folder.files()}:
        raise FicheError(
            f"A fiche already has this title: {rel}. Open it with memory_open, then rewrite it with `chemin`: "
            "the claims already in it are kept or marked replaced, never dropped."
        )
    return rel, False


async def write_fiche(
    service: MemoryService,
    user_id: str,
    *,
    titre: str,
    affirmations: list[Any],
    remplacees: list[Any] | None = None,
    resume: str | None = None,
    dossiers: list[str] | None = None,
    chemin: str | None = None,
    personnes: list[Any] | None = None,
    evoque: list[Any] | None = None,
    alias: list[Any] | None = None,
    today: date,
    allow_legacy: bool = False,
    reason: str = "",
) -> dict[str, Any]:
    """Write (or rewrite) a fiche from its claims. Raises `FicheError` with what to change."""
    titre = _one_line(titre)
    if not titre or len(titre) > MAX_TITLE:
        raise FicheError(f"`titre` is a true sentence of at most {MAX_TITLE} characters.")
    if not isinstance(affirmations, list) or not affirmations:
        raise FicheError("`affirmations` lists what the fiche says: at least one claim.")
    dossiers = [_one_line(d) for d in (dossiers or []) if _one_line(d)]
    rel, exists = _target(service, user_id, titre, dossiers, chemin)

    before = Body()
    carte = Carte()
    if exists:
        text = await service.read(user_id, rel)
        parsed = carte_mod.parse(text or "")
        carte, before = parsed.carte, parse_body(parsed.body)

    previous = {c.key: c for c in before.known}
    known = [_claim(raw) for raw in affirmations]
    keys = [c.key for c in known]
    if len(set(keys)) != len(keys):
        raise FicheError("The same claim is listed twice.")
    entries: dict[tuple[str, str], dict[str, Any]] = {}
    for claim in known:
        prior = previous.get(claim.key)
        if prior is not None and (prior.provenance, prior.source, prior.heure) == (
            claim.provenance,
            claim.source,
            claim.heure,
        ):
            entry = await _stored_entry(service, user_id, claim)
        else:
            claim.source, entry = await _check_source(service, user_id, claim, allow_legacy=allow_legacy)
        entries[(entry["titre"], claim.provenance)] = entry

    moved: list[Claim] = []
    for raw in remplacees or []:
        if not isinstance(raw, dict) or not _one_line(raw.get("texte")):
            raise FicheError("A replaced claim is {texte, motif}: `texte` as it is written in the fiche.")
        texte = _one_line(raw.get("texte"))
        old = previous.get(norm(texte))
        if old is None:
            raise FicheError(f"«{texte}» is not in the fiche: only what is written there can be marked replaced.")
        if old.key in keys:
            raise FicheError(f"«{texte}» is both kept and replaced.")
        old.replaced_on, old.motif = today.isoformat(), _one_line(raw.get("motif"))
        moved.append(old)
    moved_keys = {m.key for m in moved}
    gone = [c.texte for key, c in previous.items() if key not in keys and key not in moved_keys]
    if gone:
        raise FicheError(
            "These claims were in the fiche and are not in your version: "
            + " ; ".join(f"«{t}»" for t in gone)
            + ". Keep them in `affirmations`, or put them in `remplacees` with the reason: nothing is erased."
        )
    for claim in moved + before.replaced:
        entry = await _stored_entry(service, user_id, claim)
        entries.setdefault((entry["titre"], claim.provenance), entry)

    resume = _one_line(resume) if resume is not None else carte.resume
    if not resume:
        raise FicheError("`resume` is required: a few words on what this fiche is about.")
    merged = Carte(
        id=carte.id,
        resume=resume,
        moments=carte.moments,
        personnes=_list(personnes) or carte.personnes,
        evoque=_list(evoque) or carte.evoque,
        alias=_list(alias) or carte.alias,
        liens=carte.liens,
        sources=list(entries.values()),
        extra=carte.extra,
    )
    body = Body(known=known, replaced=before.replaced + moved, rest=before.rest)
    text = carte_mod.render(merged, render_body(body))
    await service.write(user_id, rel, text, reason=reason or ("réécrite" if exists else "nouvelle fiche"))
    return {
        "chemin": rel,
        "cree": not exists,
        "affirmations": len(known),
        "remplacees": len(moved),
        "a_annoncer": [c.texte for c in known if c.provenance == "déduit"],
    }

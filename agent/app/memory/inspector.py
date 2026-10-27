"""What the web shows of the memory folder (MAG-20): the tree, a file and its sources, the journal, the bucket link.

Everything reads the owner's folder through `MemoryService` and every change goes back through it — the file first,
then the index, then a line in `journal.md` — like a write of Maggie's. The tree is the folder as it is in the bucket:
nothing is regrouped here, and a path outside this user's folder is an unknown path, whoever else owns it.
"""

import io
import re
import zipfile
from datetime import datetime
from typing import Any

from app.db.memory_index_repository import memory_outbox_repo
from app.memory import carte as carte_mod
from app.memory import fiches, misses, paths
from app.memory.carte import UnreadableFile
from app.memory.night import NIGHT_LABEL
from app.memory.service import MemoryService

KIND_FICHE = "fiche"
KIND_CONVERSATION = "conversation"
KIND_GENERATED = "generated"
KIND_CONFLICT = "conflict"
KIND_MISS = "miss"

EDIT_REASON = "corrigé par le propriétaire depuis l'onglet Mémoire"
MOVE_REASON = "déplacé par le propriétaire depuis l'onglet Mémoire"
DELETE_REASON = "supprimé par le propriétaire depuis l'onglet Mémoire"
MAX_JOURNAL_ENTRIES = 500
MAX_PENDING_LISTED = 20

_JOURNAL_LINE = re.compile(r"^- (?P<at>\d{4}-\d{2}-\d{2} \d{1,2}h\d{2}) — (?P<text>.+)$")


class NotFound(LookupError):
    """No such file in this user's folder."""


class Refused(ValueError):
    """The change is not one the owner may make there (a conversation, a generated file…)."""


class Conflict(ValueError):
    """The file changed since the owner opened it, or the name is taken."""


class Invalid(ValueError):
    """The text cannot be saved as it is."""


def classify(rel: str) -> str:
    if paths.is_generated(rel):
        return KIND_GENERATED
    top = rel.split("/", 1)[0]
    if top == paths.CONFLICTS_DIR:
        return KIND_CONFLICT
    if top == paths.MISSES_DIR:
        return KIND_MISS
    if top == paths.CONVERSATIONS_DIR:
        return KIND_CONVERSATION
    return KIND_FICHE


def _clean(rel: str) -> str:
    try:
        return paths.clean_relative(rel)
    except paths.UnsafePath as e:
        raise Invalid(str(e)) from e


def _existing(service: MemoryService, user_id: str, rel: str) -> tuple[str, str]:
    rel = _clean(rel)
    text = service.folder(user_id).read(rel)
    if text is None:
        raise NotFound(rel)
    return rel, text


async def tree(service: MemoryService, user_id: str) -> list[dict[str, Any]]:
    """Every Markdown file of the folder, by path: the tree is built from these, as it is in the bucket."""
    cards = {c.path: c for c in await service.cards(user_id)}
    out: list[dict[str, Any]] = []
    for rel in service.folder(user_id).files():
        kind = classify(rel)
        card = cards.get(rel)
        out.append(
            {
                "path": rel,
                "title": rel.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION),
                "kind": kind,
                "resume": card.resume if card else "",
                "archived": bool(card and card.archived),
                "question": card.question if card else "",
                "editable": kind == KIND_FICHE,
            }
        )
    return out


def _claim(service: MemoryService, user_id: str, claim: fiches.Claim) -> dict[str, Any]:
    source = claim.source
    source_path: str | None = None
    kind = "other"
    exists = False
    if source.startswith(fiches.APP_SOURCE) or source.startswith("app:"):
        kind = "app"
    elif source.startswith(fiches.LEGACY_SOURCE):
        kind = "legacy"
    else:
        try:
            rel = paths.clean_relative(source)
        except paths.UnsafePath:
            rel = None
        if rel is not None and paths.is_syncable(rel):
            source_path = rel
            kind = classify(rel)
            exists = service.folder(user_id).exists(rel)
    return {
        "texte": claim.texte,
        "provenance": claim.provenance,
        "source": source,
        "heure": claim.heure,
        "sourcePath": source_path,
        "sourceKind": kind,
        "sourceExists": exists,
        "replacedOn": claim.replaced_on,
        "motif": claim.motif,
    }


def _carte_view(carte: carte_mod.Carte) -> dict[str, Any]:
    return {
        "id": carte.id,
        "resume": carte.resume,
        "moments": carte.moments,
        "personnes": carte.personnes,
        "evoque": carte.evoque,
        "alias": carte.alias,
        "liens": carte.liens,
        "sources": carte.sources,
        "archivee": str(carte.extra.get(carte_mod.ARCHIVED_KEY) or ""),
        "question": str(carte.extra.get(carte_mod.QUESTION_KEY) or ""),
    }


async def open_file(service: MemoryService, user_id: str, rel: str) -> dict[str, Any]:
    rel, text = _existing(service, user_id, rel)
    kind = classify(rel)
    view: dict[str, Any] = {
        "path": rel,
        "title": rel.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION),
        "kind": kind,
        "text": text,
        "hash": carte_mod.content_hash(text),
        "editable": kind == KIND_FICHE,
        "deletable": kind in (KIND_FICHE, KIND_MISS),
        "carte": None,
        "unreadable": None,
        "known": [],
        "replaced": [],
    }
    if kind not in (KIND_FICHE, KIND_CONVERSATION):
        return view
    try:
        parsed = carte_mod.parse(text)
    except UnreadableFile as e:
        view["unreadable"] = str(e)
        return view
    view["carte"] = _carte_view(parsed.carte)
    if kind == KIND_FICHE:
        body = fiches.parse_body(parsed.body)
        view["known"] = [_claim(service, user_id, c) for c in body.known]
        view["replaced"] = [_claim(service, user_id, c) for c in body.replaced]
    return view


async def save_file(service: MemoryService, user_id: str, rel: str, text: str, base_hash: str | None) -> dict[str, Any]:
    """The owner's version of a fiche. It wins: it replaces the file, is indexed and journaled like Maggie's writes."""
    rel, current = _existing(service, user_id, rel)
    if classify(rel) != KIND_FICHE:
        raise Refused(f"{rel!r} is not a fiche: only a fiche is corrected here")
    if base_hash is not None and base_hash != carte_mod.content_hash(current):
        raise Conflict(rel)
    try:
        before = carte_mod.parse(current).carte
        after = carte_mod.parse(text).carte
    except UnreadableFile as e:
        raise Invalid(f"la carte de la fiche n'est pas lisible : {e}") from e
    # The id is the fiche's identity: a version that lost or changed it keeps the original, so links follow.
    if before.id and after.id != before.id:
        text = carte_mod.with_id(text, before.id)
    if text == current:
        return await open_file(service, user_id, rel)
    try:
        await service.write(user_id, rel, text, reason=EDIT_REASON)
    except paths.UnsafePath as e:
        raise Invalid(str(e)) from e
    return await open_file(service, user_id, rel)


async def move_file(service: MemoryService, user_id: str, old: str, new: str) -> dict[str, Any]:
    old, _ = _existing(service, user_id, old)
    new = _clean(new)
    if classify(old) != KIND_FICHE:
        raise Refused(f"{old!r} is not a fiche: only a fiche is moved")
    folder = service.folder(user_id)
    if old != new and new.casefold() in {f.casefold() for f in folder.files()}:
        raise Conflict(new)
    try:
        moved = await service.move(user_id, old, new, reason=MOVE_REASON)
    except paths.UnsafePath as e:
        raise Invalid(str(e)) from e
    return await open_file(service, user_id, moved)


async def delete_file(service: MemoryService, user_id: str, rel: str) -> None:
    rel, _ = _existing(service, user_id, rel)
    if classify(rel) not in (KIND_FICHE, KIND_MISS):
        raise Refused(f"{rel!r} cannot be deleted from here")
    if not await service.delete(user_id, rel, reason=DELETE_REASON):
        raise NotFound(rel)


def journal(service: MemoryService, user_id: str, limit: int = 200) -> list[dict[str, Any]]:
    """The lines of `journal.md`, the newest first: what changed, when, and why."""
    text = service.folder(user_id).read(paths.JOURNAL) or ""
    entries: list[dict[str, Any]] = []
    for line in text.splitlines():
        match = _JOURNAL_LINE.match(line)
        if match is None:
            continue
        body = match.group("text")
        entries.append({"at": match.group("at"), "text": body, "night": NIGHT_LABEL in body})
    return entries[::-1][: max(1, min(limit, MAX_JOURNAL_ENTRIES))]


async def sync_state(service: MemoryService, user_id: str) -> dict[str, Any]:
    """The link with the bucket: last pass that got through, writes waiting, versions set aside."""
    status = service.status(user_id)
    pending = await memory_outbox_repo.pending(user_id)
    return {
        "enabled": service.enabled,
        "degraded": status.degraded_since is not None,
        "degradedSince": _iso(status.degraded_since),
        "lastError": status.last_error,
        "lastSyncAt": _iso(status.last_ok_at),
        "pending": len(pending),
        "pendingPaths": [e.path for e in pending[:MAX_PENDING_LISTED]],
        "conflicts": service.conflicts(user_id),
    }


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


async def missed_recalls(service: MemoryService, user_id: str) -> list[dict[str, Any]]:
    """The « tu aurais dû t'en souvenir » cases (MAG-228), newest first."""
    found = await misses.cases(service, user_id)
    return [
        {
            "path": m.path,
            "title": m.stem,
            "demande": m.demande,
            "attendue": m.attendue,
            "souvenir": m.souvenir,
            "statut": m.statut,
            "cause": misses.CAUSES.get(m.cause, m.cause),
            "enregistre": m.enregistre,
            "vertDepuis": m.vert_depuis,
            "ressorti": m.ressorti,
            "historique": m.historique,
        }
        for m in reversed(found)
    ]


def export_archive(service: MemoryService, user_id: str) -> bytes:
    """The whole folder as a zip, paths and names as in the bucket: readable without the application."""
    folder = service.folder(user_id)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for rel in folder.files():
            text = folder.read(rel)
            if text is not None:
                archive.writestr(f"{paths.MEMORY_DIR}/{rel}", text.encode("utf-8"))
    return buffer.getvalue()

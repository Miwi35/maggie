"""Missed recalls (MAG-228, ADR-011 point 7): every « tu aurais dû t'en souvenir » becomes a case replayed each night.

A case is a Markdown file of `Rappels manqués/`, in the owner's folder like everything else: he reads it, and
removes a case by deleting the file. It holds what he asked, which fiche should have come back, what came back, and
its state — `ouvert` (not replayed yet), `vert` (the fiche now comes back), `rouge` (it does not). The cases are
never indexed, so the spontaneous recall can not surface them.

This module is the pure part: the file format, recording, the replay against the index and the diagnosis of
why a fiche did not come back. The night (`night.py`) fixes the cause and says so in `journal.md`.
"""

import re
from dataclasses import dataclass, field
from datetime import date
from typing import Any

import yaml

from app.config import settings
from app.db.memory_index_repository import CardView
from app.memory import carte as carte_mod
from app.memory import paths, recall
from app.memory.carte import UnreadableFile
from app.memory.service import MemoryService

OPEN = "ouvert"
GREEN = "vert"
RED = "rouge"

NOT_A_FICHE = "fiche_inexistante"
NOT_INDEXED = "absente_index"
SET_ASIDE = "mise_de_cote"
POOR_EVOQUE = "evoque_pauvre"
THRESHOLD = "seuil_trop_haut"
CROWDED_OUT = "mauvaise_carte"
FINE = "ressort"

CAUSES = {
    NOT_A_FICHE: "la fiche n'existait pas",
    NOT_INDEXED: "la fiche n'était pas dans l'index",
    SET_ASIDE: "la fiche avait été mise de côté",
    POOR_EVOQUE: "`evoque` trop pauvre : rien dans la carte ne rejoignait la demande",
    THRESHOLD: "la carte rejoignait la demande sans atteindre le seuil",
    CROWDED_OUT: "d'autres cartes sont passées devant",
    FINE: "la fiche ressort bien d'elle-même",
}

MAX_TEXT = 300
MAX_HISTORY = 30
MAX_NEIGHBOURS = 4
POOR = 3
MIN_SHARED_WORDS = 2
FALLBACK_WORDS = 3

_HISTORY = re.compile(r"^- (?P<line>.+)$", re.MULTILINE)


class MissError(ValueError):
    """The case can not be recorded as given: the message tells Maggie what to correct."""


@dataclass
class Miss:
    path: str = ""
    demande: str = ""
    souvenir: str = ""
    attendue: str = ""
    attendue_id: str = ""
    ressorti: list[str] = field(default_factory=list)
    statut: str = OPEN
    enregistre: str = ""
    vert_depuis: str = ""
    cause: str = ""
    historique: list[str] = field(default_factory=list)

    @property
    def stem(self) -> str:
        return self.path.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)

    def log(self, day: date | str, text: str) -> None:
        self.historique.append(f"{day} — {text}")
        del self.historique[:-MAX_HISTORY]


def _line(value: Any) -> str:
    return " ".join(str(value or "").split())


def render(miss: Miss) -> str:
    head: dict[str, Any] = {
        "statut": miss.statut,
        "enregistre": miss.enregistre,
        "demande": miss.demande,
        "souvenir": miss.souvenir,
        "attendue": miss.attendue,
        "attendue_id": miss.attendue_id,
        "ressorti": miss.ressorti,
        "vert_depuis": miss.vert_depuis,
        "cause": miss.cause,
    }
    head = {key: value for key, value in head.items() if value}
    dumped = yaml.safe_dump(head, allow_unicode=True, sort_keys=False, default_flow_style=False).rstrip("\n")
    expected = f"`{miss.attendue}`" if miss.attendue else (miss.souvenir or "une fiche qui n'existait pas")
    came = ", ".join(f"`{p}`" for p in miss.ressorti) or "rien"
    lines = [
        f"{carte_mod.FENCE}\n{dumped}\n{carte_mod.FENCE}",
        "",
        "> Rappel manqué : Maggie rejoue cette demande chaque nuit contre sa mémoire et vérifie que la fiche attendue "
        "ressort. Pour retirer ce cas, supprime ce fichier.",
        "",
        f"- **Tu demandais** : {miss.demande}",
        f"- **Aurait dû ressortir** : {expected}",
        f"- **Est ressorti** : {came}",
        f"- **Où il en est** : {miss.statut}" + (f" — {CAUSES.get(miss.cause, miss.cause)}" if miss.cause else ""),
        "",
        "## Historique",
        "",
    ]
    lines += [f"- {entry}" for entry in miss.historique]
    return "\n".join(lines) + "\n"


def parse(path: str, text: str) -> Miss:
    raw, body = carte_mod.split(text)
    if raw is None:
        raise UnreadableFile("a missed recall opens on its frontmatter")
    try:
        data = yaml.safe_load(raw) or {}
    except yaml.YAMLError as e:
        raise UnreadableFile(f"the frontmatter is not valid YAML: {e}") from e
    if not isinstance(data, dict) or not _line(data.get("demande")):
        raise UnreadableFile("a missed recall states what was asked")
    ressorti = data.get("ressorti")
    history = body.split("## Historique", 1)[1] if "## Historique" in body else ""
    statut = _line(data.get("statut"))
    return Miss(
        path=path,
        demande=_line(data.get("demande")),
        souvenir=_line(data.get("souvenir")),
        attendue=_line(data.get("attendue")),
        attendue_id=_line(data.get("attendue_id")),
        ressorti=[_line(p) for p in ressorti] if isinstance(ressorti, list) else [],
        statut=statut if statut in (OPEN, GREEN, RED) else OPEN,
        enregistre=_line(data.get("enregistre")),
        vert_depuis=_line(data.get("vert_depuis")),
        cause=_line(data.get("cause")),
        historique=[m.group("line").strip() for m in _HISTORY.finditer(history)],
    )


async def cases(service: MemoryService, user_id: str) -> list[Miss]:
    """Every case of the folder, oldest first. A file nobody can read is left alone."""
    folder = service.folder(user_id)
    found: list[Miss] = []
    for rel in sorted(folder.files()):
        if not paths.is_miss(rel):
            continue
        try:
            found.append(parse(rel, folder.read(rel) or ""))
        except UnreadableFile:
            continue
    return found


async def save(service: MemoryService, user_id: str, miss: Miss, *, reason: str = "", journal: bool = False) -> None:
    await service.write(user_id, miss.path, render(miss), reason=reason, miss=True, journal=journal)


def _same_case(miss: Miss, demande: str, attendue_id: str, souvenir: str) -> bool:
    if recall.fold(miss.demande) != recall.fold(demande):
        return False
    if attendue_id or miss.attendue_id:
        return miss.attendue_id == attendue_id
    return recall.fold(miss.souvenir) == recall.fold(souvenir)


async def record(
    service: MemoryService,
    user_id: str,
    *,
    demande: str,
    souvenir: str,
    chemin: str,
    today: date,
) -> dict[str, Any]:
    """Keep what went wrong: what was asked, what should have come back, what did. Idempotent for the same case."""
    demande, souvenir, chemin = _line(demande)[:MAX_TEXT], _line(souvenir)[:MAX_TEXT], _line(chemin)
    query = recall.Query.of(demande, today)
    if not (query.stems or query.days):
        raise MissError("`demande` must be the words the owner said when the fiche should have come back.")
    attendue = attendue_id = ""
    if chemin:
        try:
            rel = paths.clean_relative(chemin)
        except paths.UnsafePath as e:
            raise MissError(f"{chemin!r} is not a path of the memory folder: {e}") from e
        found = await service.indexed(user_id, rel)
        if found is None or found.kind != "fiche" or found.unreadable:
            raise MissError(f"No fiche at {rel!r}: search first and use the path as given.")
        attendue, attendue_id = found.path, found.card_id
    elif not souvenir:
        raise MissError("Give the `chemin` of the fiche that should have come back, or describe it in `souvenir`.")

    came = [r.path for r in recall.rank(await service.cards(user_id), demande, today)]
    day = today.isoformat()
    existing = next((m for m in await cases(service, user_id) if _same_case(m, demande, attendue_id, souvenir)), None)
    if existing is not None:
        existing.statut, existing.vert_depuis, existing.ressorti = OPEN, "", came
        existing.log(day, "signalé de nouveau par le propriétaire")
        await save(service, user_id, existing, reason="rappel manqué signalé de nouveau", journal=True)
        return {"cas": existing.path, "nouveau": False, "ressortait_deja": attendue in came}

    miss = Miss(
        path=paths.miss_relative(day, demande, service.folder(user_id).files()),
        demande=demande,
        souvenir=souvenir,
        attendue=attendue,
        attendue_id=attendue_id,
        ressorti=came,
        enregistre=day,
    )
    miss.log(day, "enregistré : le propriétaire attendait ce souvenir")
    await save(service, user_id, miss, reason="rappel manqué enregistré, rejoué toutes les nuits", journal=True)
    return {"cas": miss.path, "nouveau": True, "ressortait_deja": bool(attendue) and attendue in came}


def find_card(cards: list[CardView], miss: Miss) -> CardView | None:
    """The fiche the case expects: by its id (it survives a rename), else by its path."""
    if miss.attendue_id:
        for card in cards:
            if card.card_id == miss.attendue_id:
                return card
    return next((card for card in cards if miss.attendue and card.path == miss.attendue), None)


@dataclass
class Replay:
    ok: bool
    ressorti: list[str]
    card: CardView | None


def replay(cards: list[CardView], miss: Miss, today: date) -> Replay:
    """The demand run again against the index, as the recall of a turn does: does the expected fiche come back?"""
    card = find_card(cards, miss)
    found = recall.rank(cards, miss.demande, today)
    ok = card is not None and any(r.card_id == card.card_id for r in found)
    return Replay(ok, [r.path for r in found], card)


def diagnose(cards: list[CardView], miss: Miss, today: date, *, on_disk: bool) -> tuple[str, CardView | None]:
    """Why the expected fiche did not come back. `on_disk`: the file is in the folder though the index lacks it."""
    card = find_card(cards, miss)
    if card is None:
        return (NOT_INDEXED if on_disk else NOT_A_FICHE), None
    if card.archived:
        return SET_ASIDE, card
    points, _ = recall.score(card, recall.Query.of(miss.demande, today))
    ranked = {r.card_id for r in recall.rank(cards, miss.demande, today)}
    if card.card_id in ranked:
        return FINE, card
    if points <= 0:
        return POOR_EVOQUE, card
    if points < settings.memory_recall_threshold:
        return THRESHOLD, card
    return CROWDED_OUT, card


def _shared(a: CardView, b: CardView) -> int:
    first = set(recall.significant(f"{a.title} {a.resume}"))
    return len(first & set(recall.significant(f"{b.title} {b.resume}")))


def neighbours(cards: list[CardView], card: CardView) -> list[CardView]:
    """The fiches at risk of the same miss: same people or same themes, and a carte too poor to be evoked."""
    people = {recall.fold(p) for p in card.personnes}
    close: list[tuple[int, CardView]] = []
    for other in cards:
        if other.card_id == card.card_id or other.kind != "fiche" or other.archived or len(other.evoque) >= POOR:
            continue
        shared_people = len(people & {recall.fold(p) for p in other.personnes})
        shared_words = _shared(card, other)
        if shared_people or shared_words >= MIN_SHARED_WORDS:
            close.append((shared_people * 10 + shared_words, other))
    close.sort(key=lambda item: (-item[0], item[1].path))
    return [other for _, other in close[:MAX_NEIGHBOURS]]


def fallback_term(demande: str) -> str:
    """When no better association is proposed: the heart of the demand itself, which the replay then finds."""
    words = [w for w in re.findall(r"[^\W_]+", demande) if recall.significant(w)]
    longest = sorted(dict.fromkeys(words), key=lambda w: -len(w))[:FALLBACK_WORDS]
    return " ".join(w for w in dict.fromkeys(words) if w in longest).casefold()


async def repoint(service: MemoryService, user_id: str, old_id: str, new_id: str) -> None:
    """A fiche absorbed by another: the cases that expected it expect the one that took it in."""
    for miss in await cases(service, user_id):
        if miss.attendue_id == old_id:
            miss.attendue_id = new_id
            await save(service, user_id, miss)

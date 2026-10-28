"""Habits read in the app's data (MAG-18, ADR-011): what the agenda, the errands, the meals and the transactions show.

Once a week the night reads those four through MCP, the way Maggie does, and numbers every data point (`A3`, `C1`,
`R12`, `F7`). The model proposes habits and cites the numbers; the code turns each number back into the line it
came from, so a proof is never worded by the model and never cites a datum that was not read. A habit is a fiche
like any other: its claim is `observé`, sourced `app : <data>, sur la période du A au B`, followed by one `observé`
claim per proof. Without enough proofs, on different days, nothing is written. This module reads and checks; the
judgement is the model's and the writing goes through the night (`night.py`).
"""

import asyncio
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from app.config import settings
from app.memory import fiches
from app.memory.fiches import APP_SOURCE

logger = logging.getLogger(__name__)

AGENDA = "agenda"
COURSES = "courses"
REPAS = "repas"
FINANCES = "finances"
MODULES = (AGENDA, COURSES, REPAS, FINANCES)
PREFIX = {AGENDA: "A", COURSES: "C", REPAS: "R", FINANCES: "F"}
CAPS = {AGENDA: 200, COURSES: 20, REPAS: 120, FINANCES: 100}
SESSIONS = 8
MAX_PROOFS = 12
MAX_LABEL = 160
WEEKDAYS = ("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
SLOTS = {"lunch": "déjeuner", "dinner": "dîner"}

_KEY = re.compile(r"^[ACRF]\d+$")

HABITS_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie, l'assistante personnelle d'un propriétaire. Une fois par "
    "semaine tu cherches les habitudes observées dans les DONNÉES de l'application : son agenda, ses courses, ses "
    "repas, ses dépenses. Une habitude, c'est ce qui revient (« il fait ses courses le samedi matin », « du sport "
    "le mardi soir ») ou un goût que les données révèlent (les concerts d'un agenda « Concerts » disent les "
    "styles de musique qu'il aime).\n"
    "Réponds UNIQUEMENT par un objet JSON, sans texte autour :\n"
    '{"habitudes": [{"chemin": null, "titre": "…", "dossiers": ["…"], "resume": "…", "evoque": ["…"], '
    '"preuves": ["A3", "A7", "A9"]}], '
    '"cessees": [{"chemin": "…", "texte": "…", "motif": "…"}]}\n'
    "Chaque ligne de données porte une clé (A3, C1, R12, F7). `preuves` = les clés des lignes qui prouvent "
    "l'habitude, et SEULEMENT des clés données ci-dessous : il en faut au moins {proofs}, sur des jours "
    "différents. Une habitude sans preuve n'est pas écrite. Une coïncidence, un événement ponctuel ou un "
    "événement déjà récurrent dans l'agenda n'est pas une habitude.\n"
    "`titre` est une phrase vraie, au présent (« Le propriétaire fait ses courses le samedi matin », « Le "
    "propriétaire aime le stoner, le doom et le rock psychédélique »). Pour un goût, nomme les genres que tu "
    "connais des artistes ou des plats cités, sans rien inventer d'autre. `evoque` : les situations et thèmes "
    "pour lesquels cette habitude doit revenir d'elle-même, même sans mot commun (« concert », « sortie du "
    "soir », « idée de cadeau »). `resume` : une ligne.\n"
    "Une habitude déjà dans une fiche existante (listée plus bas) : donne son `chemin` et ne cite que les "
    "NOUVELLES preuves ; ne la récris pas sous un autre titre.\n"
    "`cessees` : une habitude d'une fiche existante que les données de la période ne montrent plus (« plus de "
    "courses le samedi depuis six semaines »). Donne le `chemin`, le `texte` EXACT de l'affirmation lue sur une "
    "période, et le `motif` en une phrase. N'en déclare aucune quand les données concernées manquent.\n"
    "Si rien n'est prouvé, les deux listes restent vides."
)


class SourceDown(Exception):
    """A module of the app could not be read: its habits are neither written nor ended tonight."""


@dataclass
class Item:
    key: str
    module: str
    day: date
    when: str
    where: str
    label: str

    @property
    def source(self) -> str:
        return f"{APP_SOURCE} {self.where}, {self.day.isoformat()}"

    @property
    def weekday(self) -> str:
        return WEEKDAYS[self.day.weekday()]

    @property
    def proof(self) -> str:
        at = f" {self.when}" if self.when else ""
        return f"{self.where} : {self.label} ({self.weekday} {self.day.isoformat()}{at})"

    def line(self) -> str:
        at = f" {self.when}" if self.when else ""
        return f"{self.key} | {self.day.isoformat()} {self.weekday}{at} | {self.where} | {self.label}"


@dataclass
class Observation:
    """What the pass read: the period, the data points by key, which modules answered, what was left out."""

    start: date
    today: date
    items: dict[str, Item] = field(default_factory=dict)
    ok: set[str] = field(default_factory=set)
    down: dict[str, str] = field(default_factory=dict)
    dropped: dict[str, int] = field(default_factory=dict)

    def end_for(self, proofs: list[Item]) -> date:
        return max([self.today, *(i.day for i in proofs)])


def clean(value: Any, limit: int = MAX_LABEL) -> str:
    """One line, nothing the fiche format would read as its own separators."""
    text = " ".join(str(value or "").split()).replace(" — ", ", ").replace(" · ", ", ").replace("~~", "")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _day(value: Any) -> date | None:
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


async def _call(mcp: Any, name: str, arguments: dict[str, Any], user_id: str) -> dict[str, Any]:
    raw = await mcp.call_tool(name, arguments, user_id=user_id)
    try:
        data = json.loads(raw)
    except (TypeError, ValueError) as e:
        raise SourceDown(f"{name}: unreadable answer") from e
    if not isinstance(data, dict) or data.get("error"):
        raise SourceDown(f"{name}: {data.get('error') if isinstance(data, dict) else 'unexpected answer'}")
    return data


# ---------------------------------------------------------------------- one reader per module


def agenda_item(event: Any) -> tuple[date, str, str, str, str] | None:
    """(day, time, where, label, event id) of a dated, one-off event; None for a series or a cancelled one."""
    if not isinstance(event, dict) or event.get("recurring") or str(event.get("status")) == "cancelled":
        return None
    summary = clean(event.get("summary"))
    if not summary:
        return None
    timed = not event.get("allDay")
    raw = event.get("startAt") if timed else event.get("startDate")
    day = _day(raw)
    if day is None:
        return None
    when = str(raw)[11:16] if timed and len(str(raw)) >= 16 else ""
    agenda = clean(event.get("agenda"), 60)
    where = f"{AGENDA} « {agenda} »" if agenda else AGENDA
    place = clean(event.get("location"), 60)
    label = f"{summary} @ {place}" if place else summary
    return day, when, where, label, str(event.get("id") or summary)


async def _read_agenda(mcp: Any, user_id: str, today: date, start: date, future_days: int) -> list[Item]:
    calls: list[tuple[str, dict[str, Any]]] = [("get_upcoming_events", {"days": future_days})]
    calls += [
        ("get_events_by_date", {"date": (start + timedelta(days=n)).isoformat()})
        for n in range((today - start).days + 1)
    ]
    pages: list[Any] = []
    failure: list[SourceDown] = []

    async def worker(session: Any) -> None:
        while calls and not failure:
            name, arguments = calls.pop()
            try:
                pages.append((await _call(session, name, arguments, user_id)).get("events"))
            except SourceDown as e:
                failure.append(e)

    sessions = [await mcp.fork() for _ in range(SESSIONS)]
    try:
        await asyncio.gather(*(worker(session) for session in sessions))
    finally:
        for session in sessions:
            await session.disconnect()
    if failure:
        raise failure[0]
    found: dict[tuple[str, date], Item] = {}
    for event in (e for page in pages if isinstance(page, list) for e in page):
        parsed = agenda_item(event)
        if parsed is None or parsed[0] < start:
            continue
        day, when, where, label, identity = parsed
        found.setdefault((identity, day), Item("", AGENDA, day, when, where, label))
    return list(found.values())


async def _read_courses(mcp: Any, user_id: str, start: date, tz: ZoneInfo) -> list[Item]:
    data = await _call(mcp, "list_past_errands", {"limit": 20}, user_id)
    out = []
    for errand in data.get("errands") or []:
        try:
            ended = datetime.fromisoformat(str(errand.get("endedAt"))).astimezone(tz)
        except (ValueError, AttributeError):
            continue
        if ended.date() < start:
            continue
        store = clean(errand.get("storeName"), 60)
        names = [clean(i.get("label"), 30) for i in errand.get("items") or [] if isinstance(i, dict)]
        shown = ", ".join(n for n in names[:5] if n)
        label = (f"courses chez {store}" if store else "courses") + (
            f" ({len(names)} article(s) : {shown})" if names else ""
        )
        out.append(Item("", COURSES, ended.date(), ended.strftime("%H:%M"), COURSES, label))
    return out


async def _read_meals(mcp: Any, user_id: str, start: date, today: date) -> list[Item]:
    data = await _call(
        mcp,
        "manage_meals",
        {"action": "list", "fromDate": start.isoformat(), "toDate": today.isoformat()},
        user_id,
    )
    out = []
    for meal in data.get("meals") or []:
        day = _day(meal.get("date")) if isinstance(meal, dict) else None
        if day is None:
            continue
        names = [clean(r.get("name"), 50) for r in meal.get("recipes") or [] if isinstance(r, dict)]
        what = clean(meal.get("summary")) or ", ".join(n for n in names if n)
        if not what:
            continue
        slot = SLOTS.get(str(meal.get("slot")), str(meal.get("slot") or "repas"))
        out.append(Item("", REPAS, day, "", REPAS, f"{slot} : {what}"))
    return out


async def _read_finances(mcp: Any, user_id: str, start: date, today: date) -> tuple[list[Item], int]:
    data = await _call(
        mcp,
        "manage_transactions",
        {"action": "list", "fromDate": start.isoformat(), "toDate": today.isoformat(), "limit": 100},
        user_id,
    )
    out = []
    for line in data.get("transactions") or []:
        if not isinstance(line, dict) or str(line.get("transferKind") or "none") != "none":
            continue
        day = _day(line.get("bookedAt"))
        who = clean(line.get("counterpartyName")) or clean(line.get("label"))
        cents = line.get("amountCents")
        if day is None or not who or not isinstance(cents, int):
            continue
        kind = "dépense" if cents < 0 else "revenu"
        out.append(Item("", FINANCES, day, "", FINANCES, f"{kind} {abs(cents) / 100:.2f} € : {who}"))
    total = data.get("total")
    return out, max(0, int(total) - 100) if isinstance(total, int) else 0


# ---------------------------------------------------------------------- the observation


def _nearest(items: list[Item], today: date, cap: int) -> tuple[list[Item], int]:
    if len(items) <= cap:
        return items, 0
    kept = sorted(items, key=lambda i: abs((i.day - today).days))[:cap]
    return kept, len(items) - cap


async def observe(mcp: Any, user_id: str, today: date, tz: ZoneInfo) -> Observation:
    """Read the four modules. One that does not answer is left out and said so: its habits wait for the next night."""
    start = today - timedelta(days=settings.memory_habits_past_days)
    obs = Observation(start=start, today=today)
    readers = {
        AGENDA: lambda: _read_agenda(mcp, user_id, today, start, settings.memory_habits_future_days),
        COURSES: lambda: _read_courses(mcp, user_id, start, tz),
        REPAS: lambda: _read_meals(mcp, user_id, start, today),
        FINANCES: lambda: _read_finances(mcp, user_id, start, today),
    }

    async def read(module: str) -> list[Item]:
        try:
            result = await readers[module]()
        except SourceDown as e:
            obs.down[module] = str(e)
            logger.warning("Habits of %s: %s could not be read: %s", user_id, module, e)
            return []
        items, left_out = result if isinstance(result, tuple) else (result, 0)
        obs.ok.add(module)
        if left_out:
            obs.dropped[module] = left_out
        return items

    found = [await read(m) for m in MODULES]
    for module, items in zip(MODULES, found, strict=True):
        kept, cut = _nearest(items, today, CAPS[module])
        if cut:
            obs.dropped[module] = obs.dropped.get(module, 0) + cut
        for number, item in enumerate(sorted(kept, key=lambda i: (i.day, i.when, i.label)), start=1):
            item.key = f"{PREFIX[module]}{number}"
            obs.items[item.key] = item
    return obs


# ---------------------------------------------------------------------- what the model is shown and what it may write


def build_request(obs: Observation, existing: str, listed: str) -> str:
    parts = [
        f"Aujourd'hui : {obs.today.isoformat()} ({WEEKDAYS[obs.today.weekday()]}). Période lue : du "
        f"{obs.start.isoformat()} au {obs.today.isoformat()} (l'agenda va aussi {settings.memory_habits_future_days} "
        "jours plus loin).",
        "Modules lus : "
        + (", ".join(sorted(obs.ok)) or "aucun")
        + ("" if not obs.down else ". Illisibles cette nuit : " + ", ".join(sorted(obs.down)))
        + (
            ""
            if not obs.dropped
            else ". Données tronquées (les plus récentes ou les plus proches sont gardées) : "
            + ", ".join(f"{m} ({n} de moins)" for m, n in sorted(obs.dropped.items()))
            + ". N'en tire aucune cessation."
        ),
        "Données (clé | date | source | ce qui s'est passé) :\n" + "\n".join(i.line() for i in obs.items.values()),
        "Fiches d'habitudes existantes :\n" + (existing or "(aucune)"),
        "Autres fiches :\n" + (listed or "(aucune)"),
        "Réponds par le JSON.",
    ]
    return "\n\n".join(parts)


def existing_habits(listed: list[Any]) -> list[Any]:
    """The fiches that already hold a habit read in the data."""
    return [f for f in listed if any(fiches.is_observed_regularity(c) for c in fiches.parse_body(f.body).known)]


def existing_block(habit_fiches: list[Any]) -> str:
    blocks = []
    for fiche in habit_fiches:
        body = fiches.parse_body(fiche.body)
        lines = [fiches.render_claim(c) for c in body.known + body.replaced]
        blocks.append(f"### {fiche.path}\n" + "\n".join(lines))
    return "\n\n".join(blocks)


def proofs_of(obs: Observation, keys: Any, *, needed: int) -> tuple[list[Item], str | None]:
    """The data points a habit cites, as read; or why they do not prove it."""
    wanted = [str(k).strip().upper() for k in keys] if isinstance(keys, list) else []
    items: list[Item] = []
    for key in dict.fromkeys(wanted):
        item = obs.items.get(key) if _KEY.match(key) else None
        if item is None:
            return [], f"la clé « {key} » ne désigne aucune donnée lue"
        items.append(item)
    items.sort(key=lambda i: (i.day, i.when, i.key))
    days = {i.day for i in items}
    if len(days) < needed:
        return [], f"{len(days)} jour(s) de preuve au lieu de {needed} : non écrite"
    return items[:MAX_PROOFS], None


def habit_claims(obs: Observation, title: str, proofs: list[Item]) -> list[dict[str, Any]]:
    """The habit and its proofs, as claims for `write_fiche`: all `observé`, all sourced in the app's data."""
    where = " et ".join(dict.fromkeys(i.where for i in proofs))
    source = fiches.period_source(where, obs.start, obs.end_for(proofs))
    claims = [{"texte": clean(title, 160), "provenance": "observé", "source": source, "heure": ""}]
    claims += [{"texte": i.proof, "provenance": "observé", "source": i.source, "heure": ""} for i in proofs]
    return claims


def unreadable_for(obs: Observation, claim: fiches.Claim) -> list[str]:
    """Modules a habit was read from that did not answer tonight: silence there ends nothing."""
    return [m for m in MODULES if m in claim.source and m not in obs.ok]

"""The facts a morning digest must state, and the check that it states them (MAG-807).

The model writes the digest; the code reads it back. The facts are drawn from what the model read this run
(appointments with an hour, tasks scheduled today, meals, the weather) and each is looked for in the text.
Pure functions: the code reads the text back and tells the model what it left out, once (`signal`); what
`app.digest` does with a fact still missing is to measure it, never to write it.
"""

import json
import re
import unicodedata
from dataclasses import dataclass
from datetime import date

EVENTS_TOOL = "get_events"
TASKS_TOOL = "get_tasks"
MEALS_TOOL = "manage_meals"
WEATHER_TOOL = "get_weather"

# The agendas the owner never lists as appointments: what Maggie lays down herself, and the meals.
IGNORED_AGENDAS = frozenset({"maggie", "repas"})

STOPWORDS = frozenset(
    {
        "les",
        "des",
        "une",
        "aux",
        "que",
        "qui",
        "pour",
        "par",
        "sur",
        "avec",
        "dans",
        "chez",
        "vos",
        "mes",
        "ses",
        "est",
        "pas",
        "son",
        "sa",
        "mon",
        "ton",
        "nos",
        "leur",
        "the",
        "and",
        "aussi",
        "tout",
        "tous",
        "plus",
        "mais",
        "donc",
        "car",
    }
)
HALF = 0.5


@dataclass(frozen=True)
class Fact:
    """Something the digest owes: `label` is how it is named, `hour` ("09:15") when it has one."""

    kind: str
    label: str
    hour: str | None


def format_hour(hour: str) -> str:
    """ "09:15" -> "9 h 15", "18:00" -> "18 h"."""
    hours, _, minutes = hour.partition(":")
    spoken = f"{int(hours)} h"
    return spoken if int(minutes[:2] or 0) == 0 else f"{spoken} {int(minutes[:2]):02d}"


def _payload(result: object) -> dict | None:
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except ValueError:
            return None
    return result if isinstance(result, dict) and "error" not in result else None


def _event_facts(payload: dict, day: str) -> list[Fact]:
    facts = []
    for event in payload.get("events") or []:
        start = str(event.get("startAt") or "")
        agenda = str(event.get("agenda") or "").strip().lower()
        if (
            not start.startswith(day)
            or event.get("allDay")
            or event.get("status") == "cancelled"
            or agenda in IGNORED_AGENDAS
            or not event.get("summary")
        ):
            continue
        hour = start[11:16]
        label = str(event["summary"])
        facts.append(Fact("event", label, hour))
    return facts


def _task_facts(payload: dict, day: str) -> list[Fact]:
    facts = []
    for task in payload.get("tasks") or []:
        if task.get("scheduledDate") != day or task.get("isDone") or not task.get("title"):
            continue
        label = str(task["title"])
        timed = task.get("scheduledMoment") == "time" and task.get("scheduledTime")
        hour = str(task["scheduledTime"])[:5] if timed else None
        facts.append(Fact("task", label, hour))
    return facts


def _meal_facts(payload: dict, day: str) -> list[Fact]:
    facts = []
    for meal in payload.get("meals") or []:
        if meal.get("date") not in (None, day):
            continue
        label = " et ".join(str(r["name"]) for r in meal.get("recipes") or [] if r.get("name"))
        if label:
            facts.append(Fact("meal", label, None))
    return facts


def _weather_fact(payload: dict) -> list[Fact]:
    days = payload.get("days") or []
    if not days:
        return []
    low, high = days[0].get("temperature_min_c"), days[0].get("temperature_max_c")
    if low is None and high is None:
        return []
    return [Fact("weather", "météo", None)]


def required_facts(tool_calls: list[dict], day: date | str) -> list[Fact]:
    """What the digest owes, from the tool results of the run, in the order of the day."""
    day = day if isinstance(day, str) else day.isoformat()
    readers = {EVENTS_TOOL: _event_facts, TASKS_TOOL: _task_facts, MEALS_TOOL: _meal_facts}
    facts: list[Fact] = []
    weather: list[Fact] = []
    for call in tool_calls:
        payload = _payload(call.get("result"))
        if payload is None:
            continue
        name = call.get("name")
        if name == WEATHER_TOOL:
            weather = _weather_fact(payload)
        elif name in readers:
            facts.extend(readers[name](payload, day))
    return weather + facts


def _normalize(text: str) -> str:
    plain = unicodedata.normalize("NFKD", text.lower())
    plain = "".join(c for c in plain if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", plain).strip()


def _words(label: str) -> set[str]:
    tokens = _normalize(label).split()
    return {t for t in tokens if len(t) >= 3 and t not in STOPWORDS} or set(tokens)


def _has_hour(sentence: str, hour: str) -> bool:
    hours, minutes = int(hour[:2]), int(hour[3:5])
    tail = rf"\s*(?:h|heures?|:)\s*{minutes:02d}(?!\d)" if minutes else r"\s*(?:h|heures?|:)\s*(?:00)?(?!\d)"
    return re.search(rf"(?<!\d)0?{hours}{tail}", sentence.lower()) is not None


def _names(sentence: str, fact: Fact) -> bool:
    words = _words(fact.label)
    return not words or len(words & set(_normalize(sentence).split())) >= len(words) * HALF


def _found(text: str, fact: Fact) -> bool:
    if fact.kind == "weather":
        return "degré" in text.lower() or "°" in text
    if fact.hour is None:
        return _names(text, fact)
    sentences = re.split(r"[.!?;]+(?:\s|$)|\n+", text)
    return any(_has_hour(s, fact.hour) and _names(s, fact) for s in sentences)


def missing_facts(text: str, facts: list[Fact]) -> list[Fact]:
    """The facts the text does not state: named with half of their words, and their hour in the same sentence."""
    return [fact for fact in facts if not _found(text, fact)]


EMPTY_OPENINGS = (("aucun",), ("aucune",), ("rien",), ("pas", "de"))
_SENTENCE = re.compile(r"[^\n]+?(?:[.!?;]+(?=\s|$)|$)")


def empty_sentences(text: str) -> list[str]:
    """The sentences that open like « Aucun… », « Rien… », « Pas de… »: the text says a section is empty."""
    found = []
    for line in text.splitlines():
        for sentence in _SENTENCE.findall(line):
            words = _normalize(sentence).split()
            if any(tuple(words[: len(opening)]) == opening for opening in EMPTY_OPENINGS):
                found.append(sentence.strip())
    return found


def signal(missing: list[Fact], empty: list[str] | None = None) -> str:
    """The finding sent back to the model: the facts its digest leaves out, each with its hour, and the sentences
    that speak of what is empty, quoted. No style."""
    parts = []
    if missing:
        lines = "\n".join(f"- {f.label}, {format_hour(f.hour)}" if f.hour else f"- {f.label}" for f in missing)
        parts.append(f"Ton digest ne cite pas ces faits lus ce matin :\n{lines}")
    if empty:
        lines = "\n".join(f"- « {sentence} »" for sentence in empty)
        parts.append(f"Ces phrases parlent de ce qui est vide :\n{lines}")
    return "[Contrôle automatique] " + "\n".join(parts) + "\nRéponds par le digest entier."

"""Maggie's verdict on what a check found (MAG-886): how much it matters, how firmly it rests on a
fact, and the topic it is about. The code reads it to choose the channel; it never speaks for her.

A native tool, offered to a `check` run only and answered here: it never reaches the API nor the
policy, and Maggie's text stays hers.
"""

import json
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

from app.llm.tools import ToolRouter

VERDICT_TOOL_NAME = "proaction_verdict"
TOPIC_LENGTH = 80
IMPACTS = ("low", "medium", "high")
EVIDENCES = ("weak", "solid")
NOTED = {"noted": True}
# A deadline written without an offset is read where the owner lives, as every instant the API reads.
FALLBACK_TIME_ZONE = ZoneInfo("Europe/Paris")

PROACTION_VERDICT_TOOL = {
    "name": VERDICT_TOOL_NAME,
    "description": (
        "Ton verdict sur ce que la vérification a trouvé, avant ton texte final. "
        "Le code choisit d'après lui comment ton message arrive : interruption, simple message dans le fil, "
        "ou rien s'il redit un sujet déjà signalé sans rien de nouveau. "
        "impact : ce que ça change pour lui (low, medium, high). "
        "evidence : solid si un fait lu le prouve, weak sinon. "
        "topic : la clé courte du sujet (ex. enveloppe-loisirs) ; reprends celle d'un sujet déjà signalé "
        "s'il s'agit du même. "
        "changed : ce qui a changé depuis le dernier signalement, avec le fait lu ; vide si rien. "
        "deadline : l'échéance, ISO 8601, s'il y en a une. "
        "headline : la phrase de l'interruption, une seule, écrite pour être dite à voix haute, sans Markdown."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "impact": {"type": "string", "enum": list(IMPACTS)},
            "evidence": {"type": "string", "enum": list(EVIDENCES)},
            "topic": {"type": "string"},
            "changed": {"type": "string"},
            "deadline": {"type": "string", "description": "ISO 8601, ex. 2037-01-15T08:30:00+01:00"},
            "headline": {"type": "string"},
        },
        "required": ["impact", "evidence", "topic"],
    },
}


@dataclass(frozen=True)
class Verdict:
    impact: str
    evidence: str
    topic: str
    changed: str | None = None
    deadline: datetime | None = None
    headline: str | None = None

    def to_record(self) -> dict:
        """What the API keeps of it on the proaction (`record_proaction_result`)."""
        record = {"impact": self.impact, "evidence": self.evidence}
        if self.deadline is not None:
            # To the second, with its offset: the shape the API reads (`ProactionOutcome::INSTANT`).
            record["deadline"] = self.deadline.isoformat(timespec="seconds")
        if self.changed:
            record["changed"] = self.changed
        return record


class VerdictToolRouter:
    """A ToolRouter that offers `proaction_verdict` and answers it itself (MAG-886).

    Like `BlockedToolsRouter`, it wraps the run's router: every other tool goes through untouched.
    """

    def __init__(self, router: ToolRouter):
        self._router = router

    async def get_tool_definitions(self, *args, **kwargs) -> list[dict]:
        definitions = await self._router.get_tool_definitions(*args, **kwargs)
        return [*definitions, PROACTION_VERDICT_TOOL]

    async def call_tool(self, name: str, arguments: dict, **kwargs) -> str:
        if name == VERDICT_TOOL_NAME:
            return json.dumps(NOTED)
        return await self._router.call_tool(name, arguments, **kwargs)

    def __getattr__(self, attr):
        return getattr(self._router, attr)


def normalize_topic(value: object) -> str | None:
    """A topic key the API accepts: lowercase ASCII, digits, `:` and `-`, 80 characters at most."""
    if not isinstance(value, str):
        return None
    ascii_text = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode().lower()
    slug = re.sub(r"-{2,}", "-", re.sub(r"[^a-z0-9:-]+", "-", ascii_text)).strip("-")
    return slug[:TOPIC_LENGTH].rstrip("-") or None


def _text(value: object) -> str | None:
    return (value.strip() or None) if isinstance(value, str) else None


def _deadline(value: object) -> datetime | None:
    """An ISO 8601 instant, or None: a deadline the code cannot read is no deadline."""
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        at = datetime.fromisoformat(value.strip())
    except ValueError:
        return None
    return at if at.tzinfo is not None else at.replace(tzinfo=FALLBACK_TIME_ZONE)


def read_verdict(tool_calls: list[dict]) -> Verdict | None:
    """The last verdict the run gave, or None when it gave none the code can read.

    Read from what the model asked, like `voice_screen`. A verdict missing its impact, its evidence or
    its topic is no verdict: the run then interrupts — a forgotten verdict never hides a message.
    """
    for call in reversed(tool_calls):
        arguments = call.get("input")
        if call.get("name") != VERDICT_TOOL_NAME or not isinstance(arguments, dict):
            continue
        impact = arguments.get("impact")
        evidence = arguments.get("evidence")
        topic = normalize_topic(arguments.get("topic"))
        if impact not in IMPACTS or evidence not in EVIDENCES or topic is None:
            return None
        return Verdict(
            impact=impact,
            evidence=evidence,
            topic=topic,
            changed=_text(arguments.get("changed")),
            deadline=_deadline(arguments.get("deadline")),
            headline=_text(arguments.get("headline")),
        )
    return None

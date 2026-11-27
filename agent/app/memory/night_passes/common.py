"""What every pass of the night shares: its state, its limits and the small readers of a proposal."""

import copy
import logging
import re
from datetime import date, datetime
from typing import TYPE_CHECKING, Any
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.memory_index_repository import IndexedFile
from app.db.memory_night_repository import NightRun, memory_night_repo
from app.memory import carte as carte_mod
from app.memory import conversations, fiches, paths
from app.memory.carte import Carte
from app.memory.encoder import parse_proposal

if TYPE_CHECKING:
    from app.memory.night import NightKeeper

logger = logging.getLogger("app.memory.night")

NIGHT_LABEL = "entretien nocturne"
SCOPE_CONSOLIDATED = "consolidated"
SCOPE_TIDIED = "tidied"
SCOPE_FORGOTTEN = "forgotten"
SCOPE_HABITS = "habits"
HABITS_WEEK = "habits:week"

MAX_TOKENS = 3000
MAX_MESSAGES = 120
MAX_MESSAGE_CHARS = 1500
MAX_PROPOSALS = 8
MAX_CLAIMS = 10
MAX_TERMS = 12
MAX_RESUME = 600
FICHES_LISTED = 80
FICHES_OPENED = 6
FICHE_EXCERPT = 2500
ENCODING_PAGE = 20
ENCODING_MAX_PAGES = 50
MAX_OPERATIONS = 10
MAX_TIDY_BATCHES = 5
MAX_MERGED = 4
FORGET_BATCH = 25
REGULARITY_FRESH = 8
REGULARITY_OTHERS = 40
REGULARITY_OWNER_LINES = 8
MAX_ATTEMPTS = 2
MAX_EVOQUE_ADDED = 6
MAX_TERM_WORDS = 4
MAX_TERM_CHARS = 40
MISS_CANDIDATES = 2
MISS_REPAIRS = 3
HABIT_FICHE_CLAIMS = 40

_STAMP_DATE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})")


class TickLimit(Exception):
    """The tick has spent its calls: the pass is saved and resumes at the next tick."""


class RunLimit(Exception):
    """The pass has spent its calls: it ends here, the rest waits for the next night."""


class ModelDown(Exception):
    """The model cannot be reached: the pass is saved where it is and resumes later."""


def _line(value: Any) -> str:
    return " ".join(str(value or "").split())


def _strings(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [text for text in (_line(item) for item in value) if text]


def _items(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _dicts(value: Any) -> list[dict[str, Any]]:
    return [item for item in _items(value) if isinstance(item, dict)]


def _stem(rel: str) -> str:
    return rel.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)


def _directory(rel: str) -> list[str]:
    return rel.split("/")[:-1]


def transcript(messages: list[conversations.Message]) -> str:
    parts = []
    for message in messages[-MAX_MESSAGES:]:
        content = message.content
        if len(content) > MAX_MESSAGE_CHARS:
            content = content[:MAX_MESSAGE_CHARS] + " […]"
        parts.append(f"## {message.heure} — {message.role}\n{content}")
    return "\n\n".join(parts)


def _claims_text(fiche: IndexedFile) -> str:
    body = fiches.parse_body(fiche.body)
    lines = [fiches.render_claim(c) for c in body.known + body.replaced]
    return ("\n".join(lines) or fiche.body)[:FICHE_EXCERPT]


def _fiche_line(fiche: IndexedFile) -> str:
    mark = " (mise de côté)" if fiche.archived else ""
    return f"- {fiche.path} — {fiche.resume}{mark}" if fiche.resume else f"- {fiche.path}{mark}"


def _date_of(stamp: str | None) -> date | None:
    match = _STAMP_DATE.match(stamp or "")
    return date(int(match.group(1)), int(match.group(2)), int(match.group(3))) if match else None


def last_claim_date(fiche: IndexedFile) -> date | None:
    """When the fiche was last backed by something said. None when a claim has no date (app data, old memory)."""
    known = fiches.parse_body(fiche.body).known
    dates = [_date_of(c.heure) for c in known]
    if not known or any(d is None for d in dates):
        return None
    return max(d for d in dates if d is not None)


class Pass:
    """One pass in progress: its run, its clock, its budget, and the account it keeps."""

    def __init__(self, keeper: "NightKeeper", run: NightRun, tz: ZoneInfo, now: datetime, tick_calls: int):
        self.keeper = keeper
        self.run = run
        self.tz = tz
        self.now = now
        self.today = now.astimezone(tz).date()
        self.tick_left = tick_calls
        self.service = keeper.service
        self.user_id = run.user_id

    def spend(self) -> None:
        if self.run.calls >= settings.memory_night_max_calls:
            raise RunLimit
        if self.tick_left <= 0:
            raise TickLimit
        self.tick_left -= 1
        self.run.calls += 1

    async def ask(self, system: str, request: str) -> dict[str, Any]:
        self.spend()
        return parse_proposal(await self.keeper.ask(system, request))

    def note(self, key: str, value: Any) -> None:
        self.run.report.setdefault(key, []).append(value)

    def attempts(self, unit: str) -> int:
        return int((self.run.report.get("echecs") or {}).get(unit, 0))

    def failed(self, unit: str, why: str) -> None:
        failures = self.run.report.setdefault("echecs", {})
        failures[unit] = int(failures.get(unit, 0)) + 1
        logger.warning("Night of %s: %s left alone: %s", self.user_id, unit, why)

    def gave_up(self, unit: str) -> bool:
        return self.attempts(unit) >= MAX_ATTEMPTS

    def reason(self, what: str) -> str:
        return f"{NIGHT_LABEL} du {self.run.night} : {what}"

    async def save(self) -> None:
        await memory_night_repo.save(self.run)


async def rewrite_carte(p: Pass, rel: str, change: Any, reason: str) -> None:
    """Change the carte of a file, whatever it is, the text below it carried over untouched."""
    if paths.kind_of(rel) == "conversation":
        await conversations.update_carte_of_file(p.user_id, rel, change, reason=reason, service=p.service)
        return
    parsed = carte_mod.parse(await p.service.read(p.user_id, rel) or "")
    before = copy.deepcopy(parsed.carte)
    after = change(parsed.carte)
    if after != before:
        await p.service.write(p.user_id, rel, carte_mod.render(after, parsed.body), reason=reason)


def _with(carte: Carte, **changes: Any) -> Carte:
    data = {
        "id": carte.id,
        "resume": carte.resume,
        "moments": carte.moments,
        "personnes": carte.personnes,
        "evoque": carte.evoque,
        "alias": carte.alias,
        "liens": carte.liens,
        "sources": carte.sources,
        "extra": carte.extra,
    }
    data.update(changes)
    return Carte(**data)


def _union_sources(current: list[dict[str, Any]], new: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out = list(current)
    seen = {(str(s.get("titre")), str(s.get("provenance"))) for s in out}
    for source in new:
        key = (str(source.get("titre")), str(source.get("provenance")))
        if key not in seen:
            seen.add(key)
            out.append(source)
    return out

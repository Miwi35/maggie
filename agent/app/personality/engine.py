import logging
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

from app.db.personality_repository import personality_repo

logger = logging.getLogger(__name__)

DEFAULT_CONFIG_PATH = Path(__file__).parent / "default.yaml"
TZ_PARIS = ZoneInfo("Europe/Paris")
DAYS_FR = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
MONTHS_FR = [
    "janvier",
    "février",
    "mars",
    "avril",
    "mai",
    "juin",
    "juillet",
    "août",
    "septembre",
    "octobre",
    "novembre",
    "décembre",
]

# Fields editable via the API (system_prompt is a hardcoded template)
EDITABLE_FIELDS = ("name", "language", "backstory")


def french_date(moment: datetime, *, with_year: bool = True) -> str:
    """« jeudi 8 octobre 2026 »: the date as it is said, in the zone `moment` is already in."""
    day = "1er" if moment.day == 1 else str(moment.day)
    date = f"{DAYS_FR[moment.weekday()]} {day} {MONTHS_FR[moment.month - 1]}"
    return f"{date} {moment.year}" if with_year else date


def current_datetime_line(now: datetime | None = None, tz: ZoneInfo | None = None) -> str:
    """Current date and time to the minute in the user's timezone (Paris by default), with the zone named.

    Kept out of the cached prompt prefix: it changes every minute. The zone and its offset are said so that
    « ici » has a meaning for the date_time tool and for anyone reading the prompt across a clock change.

    The date comes in full first, the ISO one after it for the tools (MAG-349): given only « jeudi 2026-10-08 »,
    the model kept the weekday and replaced the rest with the dates written in the history.
    """
    tz = tz or TZ_PARIS
    now = (now or datetime.now(tz)).astimezone(tz)
    offset = now.strftime("%z")
    zone = f"{tz.key}, UTC{offset[:3]}:{offset[3:]}"
    return (
        f"Nous sommes le {french_date(now)} ({now.strftime('%Y-%m-%d')}), "
        f"il est {now.hour} h {now.strftime('%M')} ({zone})."
    )


def _elapsed(gap: timedelta) -> str:
    seconds = max(gap.total_seconds(), 0)
    if seconds < 60:
        return "à l'instant"
    minutes = int(seconds // 60)
    if minutes < 60:
        return f"il y a {minutes} min"
    hours, minutes = divmod(minutes, 60)
    if hours < 48:
        return f"il y a {hours}h{minutes:02d}" if minutes else f"il y a {hours}h"
    days = hours // 24
    return f"il y a {days} jour{'s' if days > 1 else ''}"


def last_exchange_line(last_at: datetime, topic: str | None = None, now: datetime | None = None) -> str:
    """When the last message of the conversation was written, how long ago, and what it was about.

    « Hier » and « avant-hier » count calendar days in Paris, not 24-hour spans: a message
    written at 22h40 is « hier » at 08h the next morning, which is what a greeting needs.
    """
    now = (now or datetime.now(TZ_PARIS)).astimezone(TZ_PARIS)
    last = last_at.astimezone(TZ_PARIS)
    days = max((now.date() - last.date()).days, 0)
    if days == 0:
        when = "aujourd'hui"
    elif days == 1:
        when = "hier"
    elif days == 2:
        when = "avant-hier"
    else:
        when = f"{DAYS_FR[last.weekday()]} {last.strftime('%Y-%m-%d')}"

    line = f"Dernière conversation : {when} à {last.strftime('%Hh%M')} ({_elapsed(now - last)})"
    topic = " ".join((topic or "").split())
    return f"{line}, sujet : {topic}." if topic else f"{line}."


class PersonalityEngine:
    """Loads personality configuration and constructs the system prompt."""

    def __init__(self, config_path: Path | None = None):
        self.config_path = config_path or DEFAULT_CONFIG_PATH
        self._yaml = self._load_yaml()

    def _load_yaml(self) -> dict:
        try:
            with open(self.config_path) as f:
                return yaml.safe_load(f)
        except FileNotFoundError:
            logger.warning(f"Personality config not found at {self.config_path}, using defaults")
            return {
                "name": "Maggie",
                "language": "fr",
                "backstory": "Une assistante personnelle IA intelligente et bienveillante.",
                "system_prompt": "Tu es {name}. {backstory}\n\nLangue : {language}.\n{capabilities}",
            }

    def _yaml_defaults(self) -> dict:
        """Return the editable defaults from YAML."""
        return {key: self._yaml.get(key, "") for key in EDITABLE_FIELDS}

    async def get_config(self, user_id: str) -> dict:
        """Return editable fields from DB, falling back to YAML defaults."""
        row = await personality_repo.get(user_id)
        if row is None:
            return self._yaml_defaults()
        return {"name": row.name, "language": row.language, "backstory": row.backstory}

    async def update_config(self, user_id: str, data: dict) -> dict:
        """Upsert editable fields into DB."""
        # Only keep allowed fields
        filtered = {k: v for k, v in data.items() if k in EDITABLE_FIELDS}
        if not filtered:
            return await self.get_config(user_id)

        # Merge with current values so we always store a complete row
        current = await self.get_config(user_id)
        current.update(filtered)
        row = await personality_repo.upsert(user_id, current)
        return {"name": row.name, "language": row.language, "backstory": row.backstory}

    async def get_system_prompt(self, user_id: str, capabilities: str = "") -> str:
        """Build the stable system prompt: template from YAML, values from DB (no date, it would break caching)."""
        config = await self.get_config(user_id)
        template = self._yaml.get("system_prompt", "")
        return template.format(
            name=config.get("name", "Maggie"),
            language=config.get("language", "fr"),
            backstory=config.get("backstory", ""),
            capabilities=capabilities,
        )

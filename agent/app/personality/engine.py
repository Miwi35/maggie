import logging
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

from app.db.personality_repository import personality_repo

logger = logging.getLogger(__name__)

DEFAULT_CONFIG_PATH = Path(__file__).parent / "default.yaml"
TZ_PARIS = ZoneInfo("Europe/Paris")
DAYS_FR = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]

# Fields editable via the API (system_prompt is a hardcoded template)
EDITABLE_FIELDS = ("name", "language", "backstory")


def current_datetime_line() -> str:
    """Current date and hour in Paris. Kept out of the system prompt prefix so it does not break the prompt cache."""
    now = datetime.now(TZ_PARIS)
    return f"Nous sommes le {DAYS_FR[now.weekday()]} {now.strftime('%Y-%m-%d')}, il est {now.strftime('%Hh')}."


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

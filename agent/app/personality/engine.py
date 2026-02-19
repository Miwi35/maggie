import logging
from datetime import date
from pathlib import Path

import yaml

logger = logging.getLogger(__name__)

DEFAULT_CONFIG_PATH = Path(__file__).parent / "default.yaml"

# Fields editable via the API (system_prompt is a hardcoded template)
EDITABLE_FIELDS = ("name", "language", "backstory")


class PersonalityEngine:
    """Loads personality configuration and constructs the system prompt."""

    def __init__(self, config_path: Path | None = None):
        self.config_path = config_path or DEFAULT_CONFIG_PATH
        self.config = self._load_config()

    def _load_config(self) -> dict:
        try:
            with open(self.config_path) as f:
                return yaml.safe_load(f)
        except FileNotFoundError:
            logger.warning(f"Personality config not found at {self.config_path}, using defaults")
            return {
                "name": "Maggie",
                "language": "fr",
                "backstory": "Une assistante personnelle IA intelligente et bienveillante.",
                "system_prompt": "Tu es {name}. {backstory}\n\nLangue : {language}.\nDate : {today}.",
            }

    def get_config(self) -> dict:
        """Return only the editable fields (not the system_prompt template)."""
        return {key: self.config.get(key, "") for key in EDITABLE_FIELDS}

    def update_config(self, data: dict) -> dict:
        """Merge editable fields into config, write YAML, and reload."""
        for key in EDITABLE_FIELDS:
            if key in data:
                self.config[key] = data[key]
        with open(self.config_path, "w") as f:
            yaml.dump(self.config, f, allow_unicode=True, default_flow_style=False, sort_keys=False)
        self.config = self._load_config()
        return self.get_config()

    def get_system_prompt(self) -> str:
        template = self.config.get("system_prompt", "")
        return template.format(
            name=self.config.get("name", "Maggie"),
            language=self.config.get("language", "fr"),
            backstory=self.config.get("backstory", ""),
            today=date.today().isoformat(),
        )

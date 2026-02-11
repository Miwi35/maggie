import logging
from pathlib import Path

import yaml

logger = logging.getLogger(__name__)

DEFAULT_CONFIG_PATH = Path(__file__).parent / "default.yaml"


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
                "tone": "friendly and helpful",
                "system_prompt": "Tu es Maggie, une assistante personnelle IA.",
            }

    def get_system_prompt(self) -> str:
        template = self.config.get("system_prompt", "")
        return template.format(
            name=self.config.get("name", "Maggie"),
            language=self.config.get("language", "fr"),
            tone=self.config.get("tone", "friendly"),
        )

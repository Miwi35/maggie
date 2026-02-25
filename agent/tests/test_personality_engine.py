from datetime import date
from pathlib import Path

import yaml

from app.personality.engine import PersonalityEngine


class TestPersonalityEngine:
    def test_get_system_prompt_with_valid_config(self, tmp_path: Path):
        """Load a custom YAML config and verify placeholders are formatted."""
        config_file = tmp_path / "personality.yaml"
        config_file.write_text(
            yaml.dump({
                "name": "TestBot",
                "language": "en",
                "backstory": "A helpful test bot.",
                "system_prompt": "You are {name}. {backstory}\nLanguage: {language}. Date: {today}.",
            })
        )

        engine = PersonalityEngine(config_path=config_file)
        prompt = engine.get_system_prompt()

        today = date.today().isoformat()
        assert prompt == f"You are TestBot. A helpful test bot.\nLanguage: en. Date: {today}."

    def test_fallback_config_on_missing_file(self, tmp_path: Path):
        """When config file does not exist, defaults are used."""
        missing_path = tmp_path / "nonexistent.yaml"

        engine = PersonalityEngine(config_path=missing_path)

        assert engine.config["name"] == "Maggie"
        assert engine.config["language"] == "fr"
        assert engine.config["backstory"] == "Une assistante personnelle IA intelligente et bienveillante."
        assert "{name}" in engine.config["system_prompt"]

    def test_get_system_prompt_with_minimal_config(self, tmp_path: Path):
        """Config with only system_prompt uses default values for missing keys."""
        config_file = tmp_path / "minimal.yaml"
        config_file.write_text(
            yaml.dump({
                "system_prompt": "Hello, I am {name}. Language: {language}. Date: {today}.",
            })
        )

        engine = PersonalityEngine(config_path=config_file)
        prompt = engine.get_system_prompt()

        today = date.today().isoformat()
        assert prompt == f"Hello, I am Maggie. Language: fr. Date: {today}."

    def test_get_system_prompt_reloads_from_disk(self, tmp_path: Path):
        """After YAML is updated on disk, get_system_prompt reflects changes."""
        config_file = tmp_path / "personality.yaml"
        config_file.write_text(
            yaml.dump({
                "name": "OldName",
                "language": "fr",
                "backstory": "Old backstory.",
                "system_prompt": "I am {name}. {backstory}",
            })
        )

        engine = PersonalityEngine(config_path=config_file)
        assert "OldName" in engine.get_system_prompt()

        # Simulate another worker updating the file
        config_file.write_text(
            yaml.dump({
                "name": "NewName",
                "language": "fr",
                "backstory": "New backstory.",
                "system_prompt": "I am {name}. {backstory}",
            })
        )

        prompt = engine.get_system_prompt()
        assert "NewName" in prompt
        assert "New backstory." in prompt


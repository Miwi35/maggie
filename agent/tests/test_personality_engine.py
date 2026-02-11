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
                "tone": "professional",
                "system_prompt": "You are {name}, speaking {language} in a {tone} tone.",
            })
        )

        engine = PersonalityEngine(config_path=config_file)
        prompt = engine.get_system_prompt()

        assert prompt == "You are TestBot, speaking en in a professional tone."

    def test_fallback_config_on_missing_file(self, tmp_path: Path):
        """When config file does not exist, defaults are used."""
        missing_path = tmp_path / "nonexistent.yaml"

        engine = PersonalityEngine(config_path=missing_path)

        assert engine.config["name"] == "Maggie"
        assert engine.config["language"] == "fr"
        assert engine.config["tone"] == "friendly and helpful"
        assert "Maggie" in engine.config["system_prompt"]

    def test_get_system_prompt_with_minimal_config(self, tmp_path: Path):
        """Config with only system_prompt uses default values for missing keys."""
        config_file = tmp_path / "minimal.yaml"
        config_file.write_text(
            yaml.dump({
                "system_prompt": "Hello, I am {name}. Language: {language}. Tone: {tone}.",
            })
        )

        engine = PersonalityEngine(config_path=config_file)
        prompt = engine.get_system_prompt()

        # Missing keys fall back to: name=Maggie, language=fr, tone=friendly
        assert prompt == "Hello, I am Maggie. Language: fr. Tone: friendly."

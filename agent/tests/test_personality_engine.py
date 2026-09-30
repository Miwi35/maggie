from datetime import datetime
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
import yaml

from app.personality.engine import DAYS_FR, TZ_PARIS, PersonalityEngine, current_datetime_line


@pytest.fixture
def yaml_config(tmp_path: Path) -> Path:
    config_file = tmp_path / "personality.yaml"
    config_file.write_text(
        yaml.dump(
            {
                "name": "TestBot",
                "language": "en",
                "backstory": "A helpful test bot.",
                "system_prompt": "You are {name}. {backstory}\nLanguage: {language}.\n{capabilities}",
            }
        )
    )
    return config_file


class TestPersonalityEngine:
    @pytest.mark.asyncio
    async def test_get_config_falls_back_to_yaml(self, yaml_config: Path):
        """When no DB row exists, get_config returns YAML defaults."""
        engine = PersonalityEngine(config_path=yaml_config)

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            config = await engine.get_config("user-1")

        assert config == {"name": "TestBot", "language": "en", "backstory": "A helpful test bot."}

    @pytest.mark.asyncio
    async def test_get_config_returns_db_values(self, yaml_config: Path):
        """When a DB row exists, get_config returns its values."""
        engine = PersonalityEngine(config_path=yaml_config)

        db_row = AsyncMock()
        db_row.name = "DBBot"
        db_row.language = "fr"
        db_row.backstory = "From the database."

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=db_row)
            config = await engine.get_config("user-1")

        assert config == {"name": "DBBot", "language": "fr", "backstory": "From the database."}

    @pytest.mark.asyncio
    async def test_update_config_upserts_to_db(self, yaml_config: Path):
        """update_config merges with current values and writes to DB."""
        engine = PersonalityEngine(config_path=yaml_config)

        db_row = AsyncMock()
        db_row.name = "Updated"
        db_row.language = "en"
        db_row.backstory = "A helpful test bot."

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            mock_repo.upsert = AsyncMock(return_value=db_row)
            result = await engine.update_config("user-1", {"name": "Updated"})

        assert result["name"] == "Updated"
        mock_repo.upsert.assert_called_once_with(
            "user-1",
            {"name": "Updated", "language": "en", "backstory": "A helpful test bot."},
        )

    @pytest.mark.asyncio
    async def test_get_system_prompt_formats_template_with_db_values(self, yaml_config: Path):
        """get_system_prompt uses YAML template but fills DB values."""
        engine = PersonalityEngine(config_path=yaml_config)

        db_row = AsyncMock()
        db_row.name = "DBBot"
        db_row.language = "fr"
        db_row.backstory = "Smart assistant."

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=db_row)
            prompt = await engine.get_system_prompt("user-1")

        assert prompt == "You are DBBot. Smart assistant.\nLanguage: fr.\n"

    @pytest.mark.asyncio
    async def test_get_system_prompt_uses_yaml_defaults_when_no_db(self, yaml_config: Path):
        """When no DB row exists, system prompt uses YAML defaults."""
        engine = PersonalityEngine(config_path=yaml_config)

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1")

        assert prompt == "You are TestBot. A helpful test bot.\nLanguage: en.\n"

    @pytest.mark.asyncio
    async def test_get_system_prompt_includes_capabilities(self, yaml_config: Path):
        """When capabilities are provided, they appear in the prompt."""
        engine = PersonalityEngine(config_path=yaml_config)

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1", capabilities="Cap summary")

        assert prompt == "You are TestBot. A helpful test bot.\nLanguage: en.\nCap summary"

    def test_current_datetime_line_is_paris_time(self):
        """The date line gives the weekday, the date and the hour in Paris."""
        now = datetime.now(TZ_PARIS)
        line = current_datetime_line()
        assert line.startswith(f"Nous sommes le {DAYS_FR[now.weekday()]} {now.strftime('%Y-%m-%d')}")

    @pytest.mark.asyncio
    async def test_default_prompt_has_no_date(self):
        """The shipped template carries no date: the prefix must stay identical between calls."""
        engine = PersonalityEngine()

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1", capabilities="Cap summary")

        assert "Nous sommes le" not in prompt
        assert "Cap summary" in prompt

    def test_fallback_config_on_missing_yaml(self, tmp_path: Path):
        """When YAML file does not exist, hardcoded defaults are used."""
        engine = PersonalityEngine(config_path=tmp_path / "nonexistent.yaml")

        defaults = engine._yaml_defaults()
        assert defaults["name"] == "Maggie"
        assert defaults["language"] == "fr"

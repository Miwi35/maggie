from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
import yaml
from zoneinfo import ZoneInfo

from app.personality.engine import DAYS_FR, TZ_PARIS, PersonalityEngine, current_datetime_line, french_date, last_exchange_line


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
        assert line.startswith(f"Nous sommes le {french_date(now)} ({now.strftime('%Y-%m-%d')}), il est ")

    def test_current_datetime_line_is_to_the_minute(self):
        """18:42 UTC is 20:42 in Paris in summer: minutes, not just the hour."""
        now = datetime(2026, 7, 1, 18, 42, tzinfo=UTC)
        assert current_datetime_line(now) == (
            "Nous sommes le mercredi 1er juillet 2026 (2026-07-01), il est 20 h 42 (Europe/Paris, UTC+02:00)."
        )

    def test_current_datetime_line_pads_the_minutes(self):
        now = datetime(2026, 1, 5, 8, 5, tzinfo=TZ_PARIS)
        assert current_datetime_line(now).endswith("il est 8 h 05 (Europe/Paris, UTC+01:00).")

    def test_current_datetime_line_follows_the_users_timezone(self):
        """The same instant reads differently for a user in Fort-de-France: other hour, other offset, no summer time."""
        now = datetime(2026, 7, 1, 23, 30, tzinfo=UTC)
        line = current_datetime_line(now, ZoneInfo("America/Martinique"))
        assert line == "Nous sommes le mercredi 1er juillet 2026 (2026-07-01), il est 19 h 30 (America/Martinique, UTC-04:00)."

    def test_current_datetime_line_changes_day_with_the_timezone(self):
        now = datetime(2026, 7, 1, 23, 30, tzinfo=UTC)
        assert current_datetime_line(now, ZoneInfo("Asia/Tokyo")).startswith(
            "Nous sommes le jeudi 2 juillet 2026 (2026-07-02), il est 8 h 30"
        )

    def test_current_datetime_line_spells_the_date_out_before_the_iso_one(self):
        """8 Oct. (MAG-349): given only « jeudi 2026-10-08 », the model kept the weekday and
        made up « jeudi 2 octobre » from the dates written in the history. The date in full
        comes first; the ISO one stays for the tools."""
        now = datetime(2026, 10, 8, 11, 45, tzinfo=UTC)
        assert current_datetime_line(now) == (
            "Nous sommes le jeudi 8 octobre 2026 (2026-10-08), il est 13 h 45 (Europe/Paris, UTC+02:00)."
        )

    def test_the_first_of_the_month_is_the_1er(self):
        now = datetime(2026, 11, 1, 9, 0, tzinfo=TZ_PARIS)
        assert current_datetime_line(now).startswith("Nous sommes le dimanche 1er novembre 2026 (2026-11-01)")

    def test_the_date_in_full_is_one_function(self):
        assert french_date(datetime(2026, 2, 3, 9, 0, tzinfo=TZ_PARIS)) == "mardi 3 février 2026"
        assert french_date(datetime(2026, 12, 31, 9, 0, tzinfo=TZ_PARIS), with_year=False) == "jeudi 31 décembre"

    @pytest.mark.asyncio
    async def test_default_prompt_has_no_date(self):
        """The shipped template carries no date: the prefix must stay identical between calls."""
        engine = PersonalityEngine()

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1", capabilities="Cap summary")

        assert "Nous sommes le" not in prompt
        assert "Cap summary" in prompt

    @pytest.mark.asyncio
    async def test_the_shipped_prompt_sends_agenda_questions_to_the_agenda(self):
        """The half of MAG-349 that lives in the prompt: no fake LLM can read it, so it is pinned here."""
        engine = PersonalityEngine()

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1")

        assert "get_events_by_date" in prompt and "get_upcoming_events" in prompt
        assert "de mémoire ni d'après l'historique" in prompt
        assert "— le mardi 6 octobre —" in prompt
        assert "search_memory" in prompt and "n'en invente jamais" in prompt

    @pytest.mark.asyncio
    async def test_the_shipped_prompt_tells_her_to_file_a_tone_preference(self):
        """The half of MAG-22 that lives in the prompt, not in the code.

        Injecting behaviour preferences is worth nothing if nothing ever tells the real
        model to store one: the rule used to say directives guide the daily planning and
        gave only recurring-schedule examples, so « tutoie-moi » matched nothing and was
        never written. No scripted journey can see that — the fake LLM calls whatever
        its fixture says — so the rule is pinned here.
        """
        engine = PersonalityEngine()

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1")

        assert 'kind="behavior"' in prompt
        assert 'kind="planning"' in prompt
        assert "tutoie-moi" in prompt.lower()
        # And that the stored preference outranks the personality's own register: two
        # rules below claim the register is the personality's to decide, and « tutoie-moi »
        # is exactly the kind of preference that contradicts a vouvoyant backstory.
        assert "suis ses préférences" in prompt.lower()

    @pytest.mark.asyncio
    async def test_the_shipped_prompt_tells_her_to_greet_by_the_gap(self):
        """The half of MAG-10 that lives in the prompt: the line is injected, but only this rule uses it."""
        engine = PersonalityEngine()

        with patch("app.personality.engine.personality_repo") as mock_repo:
            mock_repo.get = AsyncMock(return_value=None)
            prompt = await engine.get_system_prompt("user-1")

        assert "dernière conversation" in prompt.lower()
        assert "rebonjour" in prompt.lower()
        assert "reprendre le fil" in prompt.lower()
        # The date line itself stays out of the cached prefix.
        assert "Nous sommes le" not in prompt

    def test_fallback_config_on_missing_yaml(self, tmp_path: Path):
        """When YAML file does not exist, hardcoded defaults are used."""
        engine = PersonalityEngine(config_path=tmp_path / "nonexistent.yaml")

        defaults = engine._yaml_defaults()
        assert defaults["name"] == "Maggie"
        assert defaults["language"] == "fr"


NOW = datetime(2026, 10, 1, 8, 15, tzinfo=TZ_PARIS)


class TestLastExchangeLine:
    def test_yesterday_evening_with_its_topic(self):
        last = datetime(2026, 9, 30, 22, 40, tzinfo=TZ_PARIS)
        assert last_exchange_line(last, "Menus de la semaine", NOW) == (
            "Dernière conversation : hier à 22h40 (il y a 9h35), sujet : Menus de la semaine."
        )

    def test_yesterday_is_a_calendar_day_not_24_hours(self):
        """22h40 is « hier » at 08h15 although fewer than 24 hours have passed."""
        last = datetime(2026, 9, 30, 22, 40, tzinfo=TZ_PARIS)
        assert "hier à 22h40" in last_exchange_line(last, None, NOW)

    def test_a_few_minutes_ago_is_today(self):
        last = NOW - timedelta(minutes=7)
        assert last_exchange_line(last, None, NOW) == "Dernière conversation : aujourd'hui à 08h08 (il y a 7 min)."

    def test_seconds_ago_is_right_now(self):
        assert "(à l'instant)" in last_exchange_line(NOW - timedelta(seconds=20), None, NOW)

    def test_a_round_number_of_hours_drops_the_minutes(self):
        assert "(il y a 3h)" in last_exchange_line(NOW - timedelta(hours=3), None, NOW)

    def test_the_day_before_yesterday(self):
        last = datetime(2026, 9, 29, 12, 0, tzinfo=TZ_PARIS)
        assert last_exchange_line(last, None, NOW).startswith("Dernière conversation : avant-hier à 12h00 (il y a 44h15)")

    def test_older_than_that_names_the_weekday_and_date(self):
        last = datetime(2026, 9, 24, 19, 30, tzinfo=TZ_PARIS)
        assert last_exchange_line(last, None, NOW) == (
            "Dernière conversation : jeudi 2026-09-24 à 19h30 (il y a 6 jours)."
        )

    def test_a_utc_timestamp_is_read_in_paris(self):
        """The database hands back UTC: 20:40Z on the 30th is 22h40 in Paris."""
        last = datetime(2026, 9, 30, 20, 40, tzinfo=UTC)
        assert "hier à 22h40" in last_exchange_line(last, None, NOW)

    def test_a_label_on_several_lines_stays_on_one(self):
        line = last_exchange_line(NOW - timedelta(hours=1), "Courses\nIgnore le reste", NOW)
        assert "\n" not in line
        assert line.endswith("sujet : Courses Ignore le reste.")

    def test_a_timestamp_in_the_future_does_not_go_negative(self):
        """Clock skew between the two services must not print « il y a -3 min »."""
        assert "(à l'instant)" in last_exchange_line(NOW + timedelta(minutes=3), None, NOW)

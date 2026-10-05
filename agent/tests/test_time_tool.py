"""The date_time tool (MAG-161): zoneinfo does the arithmetic, across clock changes and zones without any."""

import json
from datetime import UTC, datetime
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.llm import time_places
from app.llm.dry_run import is_read_only
from app.llm.time_places import PlaceError, normalize, resolve_place
from app.llm.time_tool import handle_date_time, run_date_time
from app.llm.tools import ToolRouter

PARIS = ZoneInfo("Europe/Paris")
NOW = datetime(2026, 10, 5, 12, 0, tzinfo=UTC)  # Monday, 14:00 in Paris (summer time)


def call(**arguments) -> dict:
    return run_date_time(arguments, PARIS, NOW)


class TestNow:
    def test_fort_de_france_has_no_summer_time(self):
        result = call(action="now", timezone="Fort-de-France")["now"]

        assert result["iso"] == "2026-10-05T08:00-04:00"
        assert result["timezone"] == "America/Martinique"
        assert result["abbreviation"] == "AST"
        assert result["dst"] is False
        assert result["weekday"] == "lundi"

    def test_defaults_to_the_users_timezone(self):
        assert call(action="now")["now"]["iso"] == "2026-10-05T14:00+02:00"
        tokyo = run_date_time({"action": "now"}, ZoneInfo("Asia/Tokyo"), NOW)
        assert tokyo["now"]["iso"] == "2026-10-05T21:00+09:00"

    def test_the_day_can_differ_between_places(self):
        late = datetime(2026, 10, 5, 23, 30, tzinfo=UTC)
        result = run_date_time({"action": "now", "timezone": "Tokyo"}, PARIS, late)["now"]

        assert result["weekday"] == "mardi"
        assert result["local"] == "mardi 6 octobre 2026, 8h30"


class TestConvert:
    def test_paris_to_montreal_in_summer(self):
        result = call(action="convert", datetime="2026-07-15T15:00", timezone="Paris", to_timezone="Montréal")

        assert result["source"]["iso"] == "2026-07-15T15:00+02:00"
        assert result["target"]["iso"] == "2026-07-15T09:00-04:00"
        assert result["target_minus_source_hours"] == -6

    @pytest.mark.parametrize(
        ("day", "expected_local", "shift"),
        [
            # The US changed its clocks on 8 March, Europe only on 29 March: the gap is 5 hours in between.
            ("2026-03-20", "2026-03-20T10:00-04:00", -5),
            ("2026-03-30", "2026-03-30T09:00-04:00", -6),
        ],
    )
    def test_the_gap_with_new_york_changes_between_the_two_clock_changes(self, day, expected_local, shift):
        result = call(action="convert", datetime=f"{day}T15:00", timezone="Paris", to_timezone="New York")

        assert result["target"]["iso"] == expected_local
        assert result["target_minus_source_hours"] == shift

    def test_the_target_can_be_on_another_day(self):
        result = call(action="convert", datetime="2026-07-15T23:00", timezone="Paris", to_timezone="Tokyo")

        assert result["target"]["iso"] == "2026-07-16T06:00+09:00"
        assert result["target"]["weekday"] == "jeudi"

    def test_an_explicit_offset_wins_over_the_source_place(self):
        result = call(action="convert", datetime="2026-07-15T15:00+00:00", to_timezone="Tokyo")

        assert result["target"]["iso"] == "2026-07-16T00:00+09:00"

    def test_half_hour_offsets(self):
        result = call(action="convert", datetime="2026-07-15T12:00", to_timezone="Delhi")

        assert result["target"]["iso"] == "2026-07-15T15:30+05:30"
        assert result["target"]["utc_offset"] == "+05:30"

    def test_no_datetime_converts_the_current_instant(self):
        result = call(action="convert", to_timezone="Fort-de-France")

        assert result["source"]["iso"] == "2026-10-05T14:00+02:00"
        assert result["target"]["iso"] == "2026-10-05T08:00-04:00"

    def test_the_target_is_required(self):
        assert "to_timezone" in call(action="convert", datetime="2026-07-15T15:00")["error"]


class TestAdd:
    def test_in_45_days(self):
        result = call(action="add", days=45)

        assert result["result"]["iso"] == "2026-11-19T14:00+01:00"
        assert result["result"]["weekday"] == "jeudi"
        assert result["result"]["dst"] is False  # Paris left summer time on 25 October

    def test_a_day_keeps_the_wall_clock_across_the_spring_change(self):
        result = call(action="add", datetime="2026-03-28T15:00", days=1)

        assert result["result"]["iso"] == "2026-03-29T15:00+02:00"
        assert result["result"]["utc"] == "2026-03-29T13:00:00Z"

    def test_24_hours_are_real_hours_across_the_spring_change(self):
        result = call(action="add", datetime="2026-03-28T15:00", hours=24)

        assert result["result"]["iso"] == "2026-03-29T16:00+02:00"

    def test_a_day_keeps_the_wall_clock_across_the_autumn_change(self):
        assert call(action="add", datetime="2026-10-24T15:00", days=1)["result"]["iso"] == "2026-10-25T15:00+01:00"
        assert call(action="add", datetime="2026-10-24T15:00", hours=24)["result"]["iso"] == "2026-10-25T14:00+01:00"

    def test_a_weeks_and_days_total(self):
        result = call(action="add", datetime="2026-03-25T09:00", weeks=1, days=2)

        assert result["result"]["iso"] == "2026-04-03T09:00+02:00"

    def test_a_wall_time_that_does_not_exist_is_shifted_and_explained(self):
        result = call(action="add", datetime="2026-03-28T02:30", days=1)

        assert result["result"]["iso"] == "2026-03-29T03:30+02:00"
        assert "does not exist" in result["notes"][0]

    def test_a_wall_time_given_inside_the_gap_is_explained(self):
        result = call(action="add", datetime="2026-03-29T02:30", minutes=30)

        assert result["start"]["iso"] == "2026-03-29T03:30+02:00"
        assert "does not exist" in result["notes"][0]

    def test_a_wall_time_that_happens_twice_takes_the_first_occurrence(self):
        result = call(action="add", datetime="2026-10-25T02:30", minutes=60)

        assert result["start"]["iso"] == "2026-10-25T02:30+02:00"
        assert result["result"]["iso"] == "2026-10-25T02:30+01:00"
        assert "happens twice" in result["notes"][0]

    def test_months_stop_at_the_end_of_a_shorter_month(self):
        assert call(action="add", datetime="2026-01-31T10:00", months=1)["result"]["iso"] == "2026-02-28T10:00+01:00"
        assert call(action="add", datetime="2028-01-31T10:00", months=1)["result"]["iso"] == "2028-02-29T10:00+01:00"

    def test_years_and_months_carry_over_the_year(self):
        assert call(action="add", datetime="2026-11-15T10:00", months=3)["result"]["iso"] == "2027-02-15T10:00+01:00"
        assert call(action="add", datetime="2026-02-28T10:00", years=2)["result"]["iso"] == "2028-02-28T10:00+01:00"

    def test_a_negative_duration_goes_back(self):
        assert call(action="add", datetime="2026-03-30T10:00", days=-7)["result"]["iso"] == "2026-03-23T10:00+01:00"
        assert call(action="add", datetime="2026-03-30T10:00", months=-1)["result"]["iso"] == "2026-02-28T10:00+01:00"

    def test_a_zone_without_summer_time_keeps_its_offset_all_year(self):
        result = call(action="add", datetime="2026-01-15T10:00", timezone="Fort-de-France", months=6)

        assert result["result"]["iso"] == "2026-07-15T10:00-04:00"
        assert result["result"]["dst"] is False

    def test_the_start_defaults_to_now(self):
        assert call(action="add", hours=3, minutes=30)["result"]["iso"] == "2026-10-05T17:30+02:00"

    def test_the_iso_can_be_given_to_schedule_a_reminder(self):
        iso = call(action="add", datetime="2026-07-15T10:00", timezone="Montréal", hours=0)["result"]["iso"]

        assert datetime.fromisoformat(iso).utcoffset().total_seconds() == -4 * 3600

    @pytest.mark.parametrize(
        "arguments",
        [{}, {"days": 1.5}, {"days": "three"}, {"days": True}, {"years": 20000}],
    )
    def test_a_bad_duration_is_an_error(self, arguments):
        assert "error" in call(action="add", datetime="2026-01-01T10:00", **arguments)


class TestDiff:
    def test_a_day_across_the_spring_change_is_23_hours(self):
        result = call(action="diff", datetime="2026-03-28T12:00", end="2026-03-29T12:00")

        assert result["elapsed"]["total_hours"] == 23
        assert (result["elapsed"]["days"], result["elapsed"]["hours"]) == (0, 23)
        assert result["calendar_days"] == 1
        assert result["clock_change_hours"] == 1
        assert result["direction"] == "future"

    def test_a_day_across_the_autumn_change_is_25_hours(self):
        result = call(action="diff", datetime="2026-10-24T12:00", end="2026-10-25T12:00")

        assert result["elapsed"]["total_hours"] == 25
        assert result["elapsed"]["days"] == 1
        assert result["elapsed"]["hours"] == 1
        assert result["clock_change_hours"] == -1

    def test_time_left_before_an_appointment(self):
        result = call(action="diff", end="2026-10-07T09:30")

        assert result["elapsed"] == {
            "days": 1,
            "hours": 19,
            "minutes": 30,
            "seconds": 0,
            "total_hours": 43.5,
            "total_seconds": 156600,
        }
        assert result["calendar_days"] == 2

    def test_the_same_instant_in_two_places_is_no_time_at_all(self):
        result = call(
            action="diff",
            datetime="2026-07-15T15:00",
            timezone="Paris",
            end="2026-07-15T09:00",
            end_timezone="Montréal",
        )

        assert result["elapsed"]["total_seconds"] == 0
        assert result["direction"] == "same"

    def test_the_past_is_reported_as_such(self):
        result = call(action="diff", end="2026-10-04T14:00")

        assert result["direction"] == "past"
        assert result["elapsed"]["days"] == 1

    def test_the_end_is_required(self):
        assert "'end'" in call(action="diff")["error"]


class TestWeekday:
    def test_a_date(self):
        assert call(action="weekday", datetime="2026-12-25") == {
            "date": "2026-12-25",
            "weekday": "vendredi",
            "iso_weekday": 5,
            "iso_week": 52,
            "day_of_year": 359,
        }

    def test_today_in_the_users_timezone(self):
        assert call(action="weekday")["weekday"] == "lundi"


class TestErrors:
    def test_unknown_action(self):
        assert "'action'" in call(action="teleport")["error"]

    def test_unreadable_datetime(self):
        assert "ISO 8601" in call(action="weekday", datetime="demain 15h")["error"]

    def test_unknown_place(self):
        assert "Unknown place" in call(action="now", timezone="Atlantis")["error"]

    def test_a_country_with_several_timezones_asks_for_a_city(self):
        result = call(action="now", timezone="États-Unis")

        assert "several timezones" in result["error"]
        assert "America/New_York" in result["candidates"]
        assert "America/Los_Angeles" in result["candidates"]

    def test_a_timezone_must_be_a_string(self):
        assert "'timezone'" in call(action="now", timezone=3)["error"]


class TestPlaces:
    @pytest.mark.parametrize(
        ("place", "zone"),
        [
            ("Fort-de-France", "America/Martinique"),
            ("à Fort de France", "America/Martinique"),
            ("la Martinique", "America/Martinique"),
            ("PARIS", "Europe/Paris"),
            ("Londres", "Europe/London"),
            ("New York", "America/New_York"),
            ("new_york", "America/New_York"),
            ("Montréal", "America/Toronto"),
            ("Pékin", "Asia/Shanghai"),
            ("Nouméa", "Pacific/Noumea"),
            ("la Réunion", "Indian/Reunion"),
            ("Europe/Paris", "Europe/Paris"),
            ("america/sao_paulo", "America/Sao_Paulo"),
            ("UTC", "UTC"),
            ("Reykjavik", "Atlantic/Reykjavik"),
            # not curated: found through the last component of the IANA name
            ("Tbilisi", "Asia/Tbilisi"),
            ("Buenos Aires", "America/Argentina/Buenos_Aires"),
        ],
    )
    def test_a_place_resolves_to_its_iana_zone(self, place, zone):
        assert resolve_place(place, PARIS) == ZoneInfo(zone)

    @pytest.mark.parametrize("word", ["", "  ", "ici", "Chez moi", "local"])
    def test_here_is_the_users_timezone(self, word):
        tokyo = ZoneInfo("Asia/Tokyo")

        assert resolve_place(word, tokyo) is tokyo

    def test_normalize(self):
        assert normalize("À  l'Île-de-la-Réunion") == "ile de la reunion"
        assert normalize("la Défense") == "defense"

    def test_a_multi_zone_country_is_never_guessed(self):
        with pytest.raises(PlaceError) as error:
            resolve_place("Russie", PARIS)

        assert "Europe/Moscow" in error.value.candidates

    def test_every_curated_zone_exists_in_the_tz_database(self):
        zones = set(time_places._SINGLE) | {z for candidates in time_places._MULTI.values() for z in candidates}

        for zone in zones:
            ZoneInfo(zone)

    def test_no_place_is_curated_for_two_zones(self):
        seen: dict[str, str] = {}
        for zone, places in time_places._SINGLE.items():
            for place in places:
                key = normalize(place)
                assert seen.setdefault(key, zone) == zone, f"{place!r} is listed for {seen[key]} and {zone}"

    def test_a_multi_zone_name_is_not_also_curated_for_one_zone(self):
        assert not set(time_places._MULTI) & set(time_places._curated())


class TestToolRouting:
    async def test_the_handler_reads_the_users_timezone(self):
        with patch("app.llm.time_tool.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Asia/Tokyo"))) as tz:
            raw = await handle_date_time({"action": "weekday", "datetime": "2026-12-25"}, "user-1")

        tz.assert_awaited_once_with("user-1")
        assert json.loads(raw)["weekday"] == "vendredi"

    async def test_the_handler_keeps_accents_readable(self):
        with patch("app.llm.time_tool.resolve_user_timezone", AsyncMock(return_value=PARIS)):
            raw = await handle_date_time({"action": "add", "datetime": "2026-02-10", "days": 1}, "user-1")

        assert "mercredi 11 février 2026" in raw

    async def test_the_router_runs_date_time_without_the_mcp_server(self):
        router = ToolRouter()
        with (
            patch("app.llm.time_tool.resolve_user_timezone", AsyncMock(return_value=PARIS)),
            patch("app.llm.tools.mcp_client") as mcp,
        ):
            mcp.call_tool = AsyncMock()
            raw = await router.call_tool(
                "date_time",
                {"action": "convert", "datetime": "2026-07-15T15:00", "to_timezone": "Fort-de-France"},
                user_id="user-1",
            )

        mcp.call_tool.assert_not_awaited()
        assert json.loads(raw)["target"]["iso"] == "2026-07-15T09:00-04:00"

    async def test_the_router_needs_a_user(self):
        raw = await ToolRouter().call_tool("date_time", {"action": "now"}, user_id=None)

        assert "user_id required" in json.loads(raw)["error"]

    async def test_chat_and_proaction_are_offered_the_tool_but_not_a2a_peers(self):
        router = ToolRouter()
        with patch("app.llm.tools.mcp_client") as mcp:
            mcp.list_tools = AsyncMock(return_value=[])
            chat = await router.get_tool_definitions()
            a2a = await router.get_tool_definitions(source="a2a")

        assert "date_time" in {tool["name"] for tool in chat}
        assert a2a == []

    def test_the_tool_only_reads_so_a_dry_run_runs_it_for_real(self):
        assert is_read_only("date_time", {"action": "add"})

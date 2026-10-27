"""The daily planning, scripted by the fake LLM against the simulated API (MAG-327).

« Les jours où j'ai Ael, préviens-moi 15 min avant de le déposer » is an instruction; the
planning reads it with the day's agenda and the proactions already there, and poses one
only on a custody day. Whether a real model makes that call is judged on the real model;
what this proves is the run itself — the prompt, the tools it reaches, and that a second
run the same day leaves a single proaction.
"""

from datetime import UTC, date, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.llm.gateway import PROACTION_MAX_ITERATIONS
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.queue.scheduler import daily_planning_prompt
from tests.fake_proaction_api import FakeProactionApi

USER = "user-1"
CUSTODY_DAY = date(2030, 11, 14)
ORDINARY_DAY = date(2030, 11, 15)
INSTRUCTION = "Les jours où j'ai Ael, préviens-moi 15 min avant de le déposer à l'école (8 h 30)."
OFFERED = [
    {"name": name, "input_schema": {}}
    for name in (
        "list_instructions",
        "get_events_by_date",
        "list_proactions",
        "schedule_proaction",
        "date_time",
        "list_trips",
        "estimate_trip",
        "set_trip",
        "cancel_proaction",
    )
]
TRIP_DAY = date(2030, 11, 14)
DENTIST_ID = "01JDENTST00000000000000000"


@pytest.fixture()
def api():
    fake = FakeProactionApi()
    fake.agenda[CUSTODY_DAY.isoformat()] = [
        {"id": "01JGARDE", "summary": "Garde Ael", "startDate": CUSTODY_DAY.isoformat(), "allDay": True}
    ]
    instruction = SimpleNamespace(to_dict=lambda: {"id": "i-1", "content": INSTRUCTION, "kind": "planning"})
    with (
        patch("app.llm.tools.mcp_client", fake),
        patch("app.llm.tools.instruction_repo.find_by_user", AsyncMock(return_value=[instruction])),
        patch("app.llm.time_tool.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
    ):
        yield fake


async def plan(day: date) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.\n\nTu es en mode planification autonome.",
            [{"role": "user", "content": daily_planning_prompt(day)}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="proaction",
            max_iterations=PROACTION_MAX_ITERATIONS,
        )


def tools_called(result: dict) -> list[str]:
    return [call["name"] for call in result["tool_calls"]]


class TestTheDailyPlanning:
    async def test_it_reads_the_instructions_the_day_s_agenda_and_proactions_before_anything(self, api):
        result = await plan(ORDINARY_DAY)

        assert tools_called(result)[:3] == ["list_instructions", "get_events_by_date", "list_proactions"]
        assert ("get_events_by_date", {"date": "2030-11-15"}, USER) in api.calls
        assert ("list_proactions", {"from": "2030-11-15", "to": "2030-11-15"}, USER) in api.calls

    async def test_a_custody_day_gets_the_drop_off_reminder(self, api):
        result = await plan(CUSTODY_DAY)

        assert "schedule_proaction" in tools_called(result)
        assert [(p["prompt"], p["scheduledAt"], p["status"]) for p in api.of(USER)] == [
            ("Préviens l'utilisateur qu'il dépose Ael à l'école dans 15 minutes.", "2030-11-14T08:15:00+01:00", "pending")
        ]

    async def test_an_ordinary_day_gets_none(self, api):
        result = await plan(ORDINARY_DAY)

        assert "schedule_proaction" not in tools_called(result)
        assert api.of(USER) == []
        assert result["response"] == "Rien à poser aujourd'hui."

    async def test_planning_the_same_day_again_leaves_a_single_proaction(self, api):
        await plan(CUSTODY_DAY)
        await plan(CUSTODY_DAY)

        assert len(api.of(USER)) == 1


class _Frozen(datetime):
    """`${today}` of a scenario reads the clock: a day of the simulated API's future."""

    @classmethod
    def now(cls, tz=None):
        return datetime(2030, 11, 14, 8, 0, tzinfo=UTC).astimezone(tz)


@pytest.fixture()
def trip_api(api):
    api.agenda[TRIP_DAY.isoformat()] = [
        {
            "id": DENTIST_ID,
            "summary": "Dentiste",
            "startDate": TRIP_DAY.isoformat(),
            "location": "5 allée du Sourire Fictif, 35000 Rennes",
        }
    ]
    with patch("app.llm.fake.datetime", _Frozen):
        yield api


async def replan(day: date) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": f"Replanifier les trajets du {day.isoformat()}. Raison : un événement a bougé."}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="proaction",
            max_iterations=PROACTION_MAX_ITERATIONS,
        )


async def check(prompt: str) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": prompt}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="proaction",
            max_iterations=PROACTION_MAX_ITERATIONS,
        )


class TestTheMorningCardOfTrips:
    async def test_the_trip_is_estimated_kept_by_car_and_two_checks_are_posted(self, trip_api):
        result = await plan(TRIP_DAY)

        assert tools_called(result)[4:] == [
            "list_trips",
            "estimate_trip",
            "set_trip",
            "schedule_proaction",
            "schedule_proaction",
        ]
        assert trip_api.trips[DENTIST_ID]["mode"] == "drive"
        assert [(p["summary"], p["scheduledAt"]) for p in trip_api.of(USER)] == [
            ("Check départ · Dentiste", "2030-11-14T13:04:00+01:00"),
            ("Check départ · Dentiste", "2030-11-14T13:24:00+01:00"),
        ]

    async def test_a_day_without_a_place_to_go_posts_nothing(self, trip_api):
        trip_api.agenda[TRIP_DAY.isoformat()] = []

        result = await plan(TRIP_DAY)

        assert "set_trip" not in tools_called(result)
        assert trip_api.of(USER) == []

    async def test_the_replan_estimates_again_from_the_position_and_keeps_the_trip(self, trip_api):
        trip_api.depart_at["drive"] = "14:34"

        result = await replan(TRIP_DAY)

        assert tools_called(result) == ["list_trips", "get_events_by_date", "estimate_trip", "set_trip"]
        assert ("estimate_trip", {"eventId": DENTIST_ID, "modes": ["drive", "walk"], "origin": "position"}, USER) in trip_api.calls
        assert trip_api.trips[DENTIST_ID]["departAt"] == "2030-11-14T14:34:00+01:00"

    async def test_a_check_moves_the_trip_and_replaces_the_two_checks(self, trip_api):
        prompt = f"Dentiste (événement {DENTIST_ID}) : le départ approche, recalcule le trajet depuis la position réelle."
        for at in ("2030-11-14T13:04:00+01:00", "2030-11-14T13:24:00+01:00"):
            trip_api.seed(USER, prompt=prompt, at=at, summary="Check départ · Dentiste")
        trip_api.seed(USER, prompt="Prépare le dîner", at="2030-11-14T17:00:00+01:00", summary="Dîner")
        trip_api.depart_at["drive"] = "14:34"

        result = await check(prompt)

        assert tools_called(result) == [
            "list_trips",
            "estimate_trip",
            "set_trip",
            "list_proactions",
            "cancel_proaction",
            "cancel_proaction",
            "schedule_proaction",
            "schedule_proaction",
        ]
        assert trip_api.trips[DENTIST_ID]["departAt"] == "2030-11-14T14:34:00+01:00"
        assert [(p["summary"], p["scheduledAt"], p["prompt"]) for p in trip_api.of(USER)] == [
            ("Check départ · Dentiste", "2030-11-14T14:04:00+01:00", prompt),
            ("Check départ · Dentiste", "2030-11-14T14:24:00+01:00", prompt),
            ("Dîner", "2030-11-14T17:00:00+01:00", "Prépare le dîner"),
        ]

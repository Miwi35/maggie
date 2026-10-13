"""The daily planning, scripted by the fake LLM against the simulated API (MAG-327).

« Les jours où j'ai Ael, préviens-moi 15 min avant de le déposer » is an instruction; the
planning reads it with the day's agenda and the proactions already there, and poses one
only on a custody day. Whether a real model makes that call is judged on the real model;
what this proves is the run itself — the prompt, the tools it reaches, and that a second
run the same day leaves a single proaction.
"""

from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

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
    for name in ("list_instructions", "get_events_by_date", "list_proactions", "schedule_proaction", "date_time")
]


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

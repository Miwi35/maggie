"""The morning digest end to end, scripted by the fake LLM against the simulated API (MAG-709).

« Tous les matins à 7 h, fais-moi le digest de ma journée, par écrit et en vocal » is a planning
instruction; the planning reads it and schedules the day's digest, the digest run reads the day,
sends the vocal on the digest screen and answers the written digest. What this proves is the run —
the tools it reaches, the screen it sets, and that a weather service in error stops nothing. The
wording is the real model's, judged by the prompt-lab scenario `93-digest-du-matin.yaml`.
"""

import json
from datetime import UTC, date, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.db.journal_repository import journal_tool_calls
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.gateway import PROACTION_MAX_ITERATIONS
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.queue.proaction_consumer import voice_screen
from app.queue.scheduler import daily_planning_prompt
from tests.fake_proaction_api import FakeProactionApi

USER = "user-1"
DAY = date(2030, 11, 14)
INSTRUCTION = "Tous les matins à 7 h, fais-moi le digest de ma journée, par écrit et en vocal (digest MAG-709)."
WEATHER_DOWN = "Le service météo ne répond pas."
OFFERED = [
    {"name": name, "input_schema": {}}
    for name in (
        "list_instructions",
        "get_events_by_date",
        "list_proactions",
        "schedule_proaction",
        "date_time",
        "get_skill",
        "get_weather",
        "list_trips",
        "get_tasks",
        "manage_meals",
        "send_voice_message",
    )
]


class DigestApi(FakeProactionApi):
    """The simulated API, with the reads of a day: rain at 70 %, nothing else on."""

    weather_down = False

    def _get_weather(self, arguments: dict, user_id: str | None) -> dict:
        if self.weather_down:
            return {"error": WEATHER_DOWN}
        return {"days": [{"date": arguments.get("date"), "precipitationProbability": 70}]}

    def _get_tasks(self, arguments: dict, user_id: str | None) -> dict:
        return {"tasks": []}

    def _manage_meals(self, arguments: dict, user_id: str | None) -> dict:
        return {"meals": []}


class _Frozen(datetime):
    """`${today}` of a scenario reads the clock: the day the simulated API plans."""

    @classmethod
    def now(cls, tz=None):
        return datetime(2030, 11, 14, 6, 0, tzinfo=UTC).astimezone(tz)


@pytest.fixture()
def api():
    fake = DigestApi()
    instruction = SimpleNamespace(to_dict=lambda: {"id": "i-1", "content": INSTRUCTION, "kind": "planning"})
    with (
        patch("app.llm.tools.mcp_client", fake),
        patch("app.llm.tools.instruction_repo.find_by_user", AsyncMock(return_value=[instruction])),
        patch("app.llm.tools.skill_index.get", AsyncMock(return_value="---\nname: digest\n---\n\nLe digest.")),
        patch("app.llm.time_tool.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
        patch("app.llm.fake.datetime", _Frozen),
    ):
        yield fake


@pytest.fixture()
def vocal():
    """The voice tool's own work — speech, bucket, thread — is test_voice_message's: here, what it is asked."""
    sent = AsyncMock(return_value=MagicMock(id="voc-1", audio={"durationMs": 900}, screen="digest"))
    with patch("app.tts.voice_message.send_voice_message", sent):
        yield sent


async def run(prompt: str) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.\n\nTu es en mode proaction autonome.",
            [{"role": "user", "content": prompt}],
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


async def plan_and_run(api) -> dict:
    await run(daily_planning_prompt(DAY))
    [digest] = api.of(USER)
    return await run(f"[Proaction due maintenant] {digest['prompt']}")


class TestTheMorningDigest:
    async def test_the_planning_reads_the_instruction_and_schedules_the_day_s_digest(self, api):
        result = await run(daily_planning_prompt(DAY))

        assert tools_called(result)[0] == "list_instructions"
        assert [(p["summary"], p["scheduledAt"]) for p in api.of(USER)] == [
            ("Digest du matin", "2030-11-14T07:02:00+01:00")
        ]

    async def test_the_digest_reads_the_day_then_sends_the_vocal_on_the_digest_screen(self, api, vocal):
        result = await plan_and_run(api)

        assert tools_called(result)[:3] == ["get_skill", "get_weather", "get_events_by_date"]
        assert ("get_weather", {"date": "2030-11-14"}, USER) in api.calls
        assert ("get_tasks", {"view": "today"}, USER) in api.calls
        assert vocal.await_args.kwargs["screen"] == "digest"
        assert voice_screen(result["tool_calls"]) == "digest"
        assert result["response"].startswith("**Météo**")

    async def test_a_weather_service_in_error_stops_nothing_and_the_journal_keeps_the_error(self, api, vocal):
        api.weather_down = True

        result = await plan_and_run(api)

        vocal.assert_awaited_once()
        assert result["response"]
        [weather] = [call for call in journal_tool_calls(result["tool_calls"]) if call["name"] == "get_weather"]
        assert weather["error"] == WEATHER_DOWN
        assert json.loads(weather["result"]) == {"error": WEATHER_DOWN}

"""The morning digest end to end, scripted by the fake LLM against the simulated API (MAG-709).

« Tous les matins à 7 h, fais-moi le digest de ma journée, par écrit et en vocal » is a planning
instruction; the planning reads it and schedules the day's digest, the digest run reads the day,
answers one text, and the code reads it back, voices it and stores one message on the digest screen
(MAG-807). What this proves is the run — the tools it reaches, the one message, and that a weather
service in error or a voice down stops nothing. The wording is the real model's, judged by the
prompt-lab scenarios `93-digest-du-matin.yaml` and its neighbours.
"""

import json
from datetime import UTC, date, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.db.journal_repository import journal_tool_calls
from app.digest import DIGEST_AVAILABLE, digest_block, run_digest
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.gateway import PROACTION_MAX_ITERATIONS
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.tts.voice_message import VoiceMessageError
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
        "get_tasks",
        "manage_meals",
        "get_grocery_list",
    )
]


class DigestApi(FakeProactionApi):
    """The simulated API, with the reads of a day: rain at 70 %, nothing else on."""

    weather_down = False

    def _get_weather(self, arguments: dict, user_id: str | None) -> dict:
        if self.weather_down:
            return {"error": WEATHER_DOWN}
        return {"days": [{"date": arguments.get("date"), "temperature_min_c": 9.5, "temperature_max_c": 18.5}]}

    def _get_tasks(self, arguments: dict, user_id: str | None) -> dict:
        evening = {"id": "t-1", "title": "Préparation du weekend", "scheduledDate": "2030-11-14", "scheduledMoment": "evening"}
        return {"tasks": [evening], "reminders": []}

    def _manage_meals(self, arguments: dict, user_id: str | None) -> dict:
        return {"meals": []}

    def _get_grocery_list(self, arguments: dict, user_id: str | None) -> dict:
        return {"stores": []}


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

    async def test_the_digest_reads_the_day_and_answers_one_text_without_calling_the_voice_tool(self, api):
        result = await plan_and_run(api)

        assert tools_called(result)[:3] == ["get_skill", "get_weather", "get_events_by_date"]
        assert ("get_weather", {"date": "2030-11-14"}, USER) in api.calls
        assert ("get_tasks", {"view": "today"}, USER) in api.calls
        assert not {"send_voice_message", "list_trips", "get_finance_scores"} & set(tools_called(result))
        assert "degrés" in result["response"]
        assert "ce soir, la préparation du weekend" in result["response"].lower()

    async def test_a_weather_service_in_error_stops_nothing_and_the_journal_keeps_the_error(self, api):
        api.weather_down = True

        result = await plan_and_run(api)

        assert result["response"]
        [weather] = [call for call in journal_tool_calls(result["tool_calls"]) if call["name"] == "get_weather"]
        assert weather["error"] == WEATHER_DOWN
        assert json.loads(weather["result"]) == {"error": WEATHER_DOWN}


class _Gateway:
    """What `run_digest` asks of the gateway: the proaction run, a client and the tools."""

    client = None
    tool_router = ToolRouter()

    async def proaction(
        self, prompt: str, user_id: str, *, summary: str | None = None, blocked_tools: frozenset[str] = frozenset()
    ) -> dict:
        self.blocked_tools = blocked_tools
        result = await run(f"[Proaction due maintenant] {prompt}")
        return result | {"context_id": "ctx-1", "model": "fake"}


KINE = {
    "id": "ev-kine",
    "summary": "Kiné",
    "startAt": "2030-11-14T09:15:00+01:00",
    "endAt": "2030-11-14T10:00:00+01:00",
    "allDay": False,
    "status": "confirmed",
    "agenda": "Défaut",
}
DIGEST = SimpleNamespace(prompt="Fais le digest du matin (digest MAG-709).", user_id=USER, summary="Digest du matin")


@pytest.fixture()
def storage():
    """The voice and the thread: their own work is test_voice_message's, here what the digest asks of them."""
    voice = AsyncMock(return_value=MagicMock(id="voc-1", audio={"durationMs": 900}, screen="digest"))
    thread = MagicMock()
    thread.create = AsyncMock(return_value=MagicMock(id="msg-1", audio=None, screen="digest"))
    with (
        patch("app.digest.send_voice_message", voice),
        patch("app.digest.message_repo", thread),
        patch("app.digest.client_for", AsyncMock(return_value=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR))),
        patch("app.digest.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
        patch("app.digest.datetime", _Frozen),
        patch("app.llm.runner.record_llm_usage"),
    ):
        yield SimpleNamespace(voice=voice, thread=thread)


class TestRunDigest:
    async def test_the_digest_message_says_it_is_available_and_carries_its_block(self, api, storage):
        result, stored = await run_digest(_Gateway(), DIGEST)

        storage.voice.assert_awaited_once()
        args, kwargs = storage.voice.await_args
        assert args[0] == USER and args[2] == "ctx-1"
        assert kwargs["screen"] == "digest" and kwargs["content"] == DIGEST_AVAILABLE
        assert args[1] == result["response"]
        assert kwargs["ui_blocks"] == [digest_block(result["response"], date(2030, 11, 14), ZoneInfo("Europe/Paris"))]
        storage.thread.create.assert_not_called()
        assert stored is storage.voice.return_value
        assert "send_voice_message" not in tools_called(result)

    async def test_a_missing_appointment_is_rewritten_once_then_appended(self, api, storage):
        api.agenda["2030-11-14"] = [KINE]

        result, _ = await run_digest(_Gateway(), DIGEST)

        assert result["response"].endswith("Vous avez aussi Kiné à 9 h 15.")
        assert result["response"].count("Vous avez aussi") == 1
        assert "RELECTURE DU DIGEST" not in result["response"]

    async def test_a_rewrite_in_error_keeps_the_text_and_appends_the_missing_sentence(self, api, storage, monkeypatch):
        api.agenda["2030-11-14"] = [KINE]

        async def down(*args, **kwargs):
            raise RuntimeError("529")

        monkeypatch.setattr("app.digest.run_tool_loop", down)

        result, stored = await run_digest(_Gateway(), DIGEST)

        assert result["response"].endswith("Vous avez aussi Kiné à 9 h 15.")
        assert stored is storage.voice.return_value

    async def test_an_appointment_the_text_already_names_is_neither_rewritten_nor_appended(self, api, storage):
        api.agenda["2030-11-14"] = [SESSION]
        api._get_tasks = lambda arguments, user_id: {"tasks": [], "reminders": []}

        result, _ = await run_digest(_Gateway(), SimpleNamespace(**(vars(DIGEST) | {"prompt": "Digest (digest MAG-777)."})))

        assert "Vous avez aussi" not in result["response"]

    async def test_the_digest_run_blocks_the_voice_tool_whatever_the_skill_says(self, api, storage):
        gateway = _Gateway()

        await run_digest(gateway, DIGEST)

        assert "send_voice_message" in gateway.blocked_tools

    async def test_a_voice_down_still_stores_the_text_on_the_digest_screen(self, api, storage):
        storage.voice.side_effect = VoiceMessageError("down")

        result, stored = await run_digest(_Gateway(), DIGEST)

        kwargs = storage.thread.create.await_args.kwargs
        assert (kwargs["content"], kwargs["screen"], kwargs["role"]) == (DIGEST_AVAILABLE, "digest", "assistant")
        assert kwargs["ui_blocks"][0]["text"] == result["response"]
        assert stored is storage.thread.create.return_value


# The day's planned session, an event of the « Santé » agenda (MAG-777).
SESSION = {
    "id": "ev-sport",
    "summary": "Séance A",
    "startAt": "2030-11-14T18:30:00+01:00",
    "endAt": "2030-11-14T19:00:00+01:00",
    "activity": "sport",
}


class TestTheDaysSession:
    async def test_the_digest_reads_the_agenda_and_cites_the_session_with_its_hour_and_length(self, api):
        api.agenda["2030-11-14"] = [SESSION]

        result = await run("[Proaction due maintenant] Fais le digest du matin (digest MAG-777).")

        assert ("get_events_by_date", {"date": "2030-11-14"}, USER) in api.calls
        assert "séance A à 18 h 30, 30 min" in result["response"]
        assert "send_voice_message" not in tools_called(result)

    def test_the_shipped_skill_says_how_to_cite_it(self):
        skill = (DEFAULT_FIXTURES_DIR.parent.parent / "app" / "skills" / "digest.md").read_text(encoding="utf-8")

        assert 'activity: "sport"' in skill
        assert "Séance A à 18 h 30, 30 min" in skill

"""The evening review on the agent's side (MAG-749): the proactions the planning lays down, the single nudge,
the technical accounts left out, and the on-demand run scripted by the fake model against the simulated API."""

import json
from datetime import UTC, date, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app import bilan
from app.auth import RECETTE_ACCOUNT_EMAIL, SMOKE_ACCOUNT_EMAIL
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.gateway import PROACTION_MAX_ITERATIONS
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.proactions.api import Proaction
from app.queue import proaction_consumer, scheduler
from app.queue.scheduler import daily_planning_prompt
from tests.fake_proaction_api import FakeProactionApi

USER = "user-1"
DAY = date(2030, 11, 14)
OFFERED = [
    {"name": name, "input_schema": {}}
    for name in (
        "get_skill",
        "get_activity_status",
        "save_bilan_step",
        "get_events_by_date",
        "get_tasks",
        "create_task",
        "schedule_proaction",
        "add_instruction",
    )
]


def relance(summary: str = bilan.BILAN_RELANCE_SUMMARY) -> Proaction:
    return Proaction(id="p-1", user_id=USER, prompt="Fais le bilan", status="running", summary=summary, scheduled_at=None)


class TestThePlanningPrompt:
    def test_it_lays_down_the_evening_proaction_and_its_single_nudge(self):
        prompt = daily_planning_prompt(DAY)

        assert "Bilan du soir du 2030-11-14" in prompt
        assert "21:30" in prompt
        assert f"résumé exactement « {bilan.BILAN_SUMMARY} »" in prompt
        assert f"résumé exactement « {bilan.BILAN_RELANCE_SUMMARY} »" in prompt
        assert "30 minutes plus tard" in prompt
        assert "heure de coucher" in prompt

    def test_a_technical_account_is_planned_without_the_evening(self):
        assert "Bilan du soir" not in daily_planning_prompt(DAY, evening=False)

    @pytest.mark.parametrize("email", [SMOKE_ACCOUNT_EMAIL, RECETTE_ACCOUNT_EMAIL, "owner@example.com", None])
    async def test_the_planning_leaves_the_evening_out_for_technical_accounts_only(self, monkeypatch, email):
        monkeypatch.setattr(scheduler, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris")))
        monkeypatch.setattr(scheduler, "resolve_user_email", AsyncMock(return_value=email))
        monkeypatch.setattr(scheduler, "planning_agenda", AsyncMock(return_value=""))
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "ok"}

        await scheduler.generate_proactions(gateway, USER, dry_run=True, now=datetime(2030, 11, 14, 4, tzinfo=UTC))

        evening = email not in {SMOKE_ACCOUNT_EMAIL, RECETTE_ACCOUNT_EMAIL}
        assert ("Bilan du soir" in gateway.proaction.await_args.args[0]) is evening


class TestTheReviewDay:
    def test_a_nudge_after_midnight_belongs_to_the_evening_before(self):
        paris = ZoneInfo("Europe/Paris")

        day = bilan.relance_review_day("2030-11-07T00:15:00+01:00", datetime(2030, 11, 7, tzinfo=UTC), paris)

        assert day == "2030-11-06"

    def test_without_a_time_it_falls_back_to_now(self):
        assert bilan.relance_review_day(None, datetime(2030, 11, 7, 12, tzinfo=UTC), ZoneInfo("UTC")) == "2030-11-07"


class TestTheSingleNudge:
    @pytest.fixture()
    def run(self, monkeypatch):
        monkeypatch.setattr(proaction_consumer, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("UTC")))
        record = AsyncMock()
        monkeypatch.setattr(proaction_consumer, "_record", record)
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "On fait le point ?", "tool_calls": [], "context_id": None}
        return gateway, record

    async def test_no_nudge_once_the_user_has_answered(self, run, monkeypatch):
        gateway, record = run
        today = datetime.now(UTC).date().isoformat()
        status = {"activity": "bilan", "origin": {"kind": "day", "id": today}, "state": {"step": "finance"}}
        monkeypatch.setattr(bilan.mcp_client, "call_tool", AsyncMock(return_value=json.dumps(status)))

        outcome = await proaction_consumer.execute_proaction(gateway, relance())

        assert outcome == {"status": "completed", "message": None, "toolCalls": []}
        gateway.proaction.assert_not_awaited()
        record.assert_awaited_once()

    @pytest.mark.parametrize("answer", ["null", json.dumps({"origin": {"kind": "day", "id": "2000-01-01"}})])
    async def test_the_nudge_goes_when_nothing_is_in_progress_for_today(self, run, monkeypatch, answer):
        gateway, _ = run
        monkeypatch.setattr(bilan.mcp_client, "call_tool", AsyncMock(return_value=answer))
        monkeypatch.setattr(proaction_consumer, "_journal", AsyncMock())
        monkeypatch.setattr(proaction_consumer.message_repo, "create", AsyncMock(return_value=SimpleNamespace(id="m")))
        monkeypatch.setattr(proaction_consumer, "notify_message", AsyncMock())

        outcome = await proaction_consumer.execute_proaction(gateway, relance())

        assert outcome["message"] == "On fait le point ?"

    async def test_the_first_proaction_never_reads_the_status(self, run, monkeypatch):
        gateway, _ = run
        read = AsyncMock()
        monkeypatch.setattr(bilan.mcp_client, "call_tool", read)
        monkeypatch.setattr(proaction_consumer, "_journal", AsyncMock())
        monkeypatch.setattr(proaction_consumer.message_repo, "create", AsyncMock(return_value=SimpleNamespace(id="m")))
        monkeypatch.setattr(proaction_consumer, "notify_message", AsyncMock())

        await proaction_consumer.execute_proaction(gateway, relance(bilan.BILAN_SUMMARY))

        read.assert_not_awaited()
        gateway.proaction.assert_awaited_once()

    async def test_an_unreadable_status_counts_as_not_answered(self, monkeypatch):
        monkeypatch.setattr(bilan.mcp_client, "call_tool", AsyncMock(side_effect=RuntimeError("down")))

        assert await bilan.bilan_answered(USER, "2030-11-14") is False


class BilanApi(FakeProactionApi):
    """The simulated API with the bilan's status: written by save_bilan_step, read by get_activity_status."""

    status: dict | None = None
    tasks: list[dict] = []

    def _get_activity_status(self, arguments: dict, user_id: str | None) -> dict | None:
        return self.status

    def _save_bilan_step(self, arguments: dict, user_id: str | None) -> dict:
        self.status = {
            "activity": "bilan",
            "origin": {"kind": "day", "id": arguments["day"]},
            "state": {"step": arguments["step"], "instructions": arguments.get("instructions", [])},
        }
        return {"success": True, "status": self.status}

    def _get_events_by_date(self, arguments: dict, user_id: str | None) -> dict:
        return {"events": []}

    def _get_tasks(self, arguments: dict, user_id: str | None) -> dict:
        return {"tasks": [{"id": "t1", "title": "Appeler le garage"}]}

    def _create_task(self, arguments: dict, user_id: str | None) -> dict:
        self.tasks.append(arguments)
        return {"success": True, "task": {"id": "t2", **arguments}}


@pytest.fixture()
def api():
    fake = BilanApi(now=datetime(2020, 1, 1, tzinfo=UTC))
    fake.tasks = []
    learned = AsyncMock(return_value=SimpleNamespace(to_dict=lambda: {"id": "i-1"}))
    fake.learned = learned
    with (
        patch("app.llm.tools.instruction_repo.store", learned),
        patch("app.llm.tools.mcp_client", fake),
        patch("app.llm.tools.skill_index.get", AsyncMock(return_value="---\nname: bilan-soir\n---\n\nLe bilan.")),
    ):
        yield fake


async def say(prompt: str) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": prompt}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="chat",
            max_iterations=PROACTION_MAX_ITERATIONS,
        )


def names(result: dict) -> list[str]:
    return [call["name"] for call in result["tool_calls"]]


class TestOnDemand:
    async def test_on_fait_le_point_opens_the_journee_step_without_the_proaction(self, api):
        result = await say("On fait le point")

        assert names(result) == ["get_skill", "get_activity_status", "save_bilan_step", "get_events_by_date", "get_tasks"]
        assert api.status["state"]["step"] == "journee"
        assert "*" not in result["response"] and "#" not in result["response"]


class TestTheProactionOpening:
    async def test_it_opens_without_writing_the_status_so_a_silence_stays_a_silence(self, api):
        result = await say("[Proaction due maintenant] Fais le bilan de la journée (compétence bilan-soir).")

        assert "save_bilan_step" not in names(result)
        assert api.status is None
        assert result["response"]


class TestTheConsignes:
    DICTATED = "Pour demain : rappelle-moi d'appeler le garage à 9 h, ajoute acheter du pain, et le mardi je pars plus tôt"

    async def test_each_consigne_is_classed_and_carries_the_bilan_soir_provenance(self, api):
        result = await say(self.DICTATED)

        assert names(result) == ["schedule_proaction", "create_task", "add_instruction", "save_bilan_step"]
        [proaction] = api.of(USER)
        assert proaction["scheduledAt"].endswith("T09:00:00+01:00")
        assert "bilan-soir" in proaction["prompt"]
        [task] = api.tasks
        assert task["title"] == "Acheter du pain" and "bilan-soir" in task["description"]
        api.learned.assert_awaited_once()
        assert api.learned.await_args.kwargs["kind"].value == "planning"
        assert "bilan-soir" in api.learned.await_args.args[1]

    async def test_the_bilan_status_keeps_the_three_kinds_for_the_digest(self, api):
        await say(self.DICTATED)

        kinds = [instruction["kind"] for instruction in api.status["state"]["instructions"]]
        assert kinds == ["reminder", "task", "instruction"]
        assert api.status["state"]["step"] == "consignes"

"""The journal of the plannings and proaction runs (MAG-688): what Maggie read, decided and scheduled."""

import json
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.auth import get_current_user_id
from app.db.journal_model import JournalRun
from app.db.journal_repository import RESULT_MAX_CHARS, journal_repo
from app.proactions.api import Proaction
from app.queue import scheduler
from app.queue.proaction_consumer import execute_proaction

INSTRUCTION = {"id": "ins-1", "content": "Tous les matins à 7 h, fais-moi le digest de ma journée.", "kind": "planning"}
SCHEDULED = {
    "id": "01JDIGEST00000000000000000",
    "prompt": "Fais le digest de la journée de l'utilisateur.",
    "summary": "Digest du matin",
    "scheduledAt": "2026-10-29T07:00:00+01:00",
    "status": "pending",
}
PLANNING_RESULT = {
    "response": "Proaction posée : le digest à 7 h.",
    "tool_calls": [
        {"name": "list_instructions", "input": {"kind": "planning"}, "result": json.dumps([INSTRUCTION])},
        {"name": "get_events_by_date", "input": {"date": "2026-10-29"}, "result": json.dumps({"events": []})},
        {
            "name": "schedule_proaction",
            "input": {"prompt": SCHEDULED["prompt"], "scheduled_at": "2026-10-29T07:00:00", "summary": "Digest du matin"},
            "result": json.dumps({"proaction": SCHEDULED}),
        },
        {
            "name": "schedule_proaction",
            "input": {"prompt": "Hier", "scheduled_at": "2026-10-28T07:00:00"},
            "result": json.dumps({"error": "scheduled_at is in the past"}),
        },
    ],
    "usage": {"input_tokens": 1200, "output_tokens": 80},
}


@pytest.fixture(autouse=True)
def paris(monkeypatch):
    zone = AsyncMock(return_value=ZoneInfo("Europe/Paris"))
    monkeypatch.setattr(scheduler, "resolve_user_timezone", zone)
    monkeypatch.setattr("app.queue.proaction_consumer.resolve_user_timezone", zone)
    monkeypatch.setattr(scheduler, "planning_agenda", AsyncMock(return_value=""))


def _client(user_id: str | None = "user-1") -> TestClient:
    app = FastAPI()
    app.include_router(router)
    if user_id is not None:
        app.dependency_overrides[get_current_user_id] = lambda: user_id
    return TestClient(app)


def _gateway(result: dict | Exception) -> MagicMock:
    gateway = MagicMock()
    gateway.proaction = AsyncMock(side_effect=result) if isinstance(result, Exception) else AsyncMock(return_value=result)
    return gateway


async def _execute(result: dict | Exception, proaction_id: str = SCHEDULED["id"]) -> dict:
    proaction = Proaction.from_api(SCHEDULED | {"id": proaction_id, "userId": "user-1", "status": "running"})
    with (
        patch("app.queue.proaction_consumer.message_repo") as messages,
        patch("app.queue.proaction_consumer.proactions_api") as proactions,
        patch("app.queue.proaction_consumer.publisher") as publisher,
        patch("app.queue.proaction_consumer.context_summarizer") as summarizer,
    ):
        messages.create = AsyncMock()
        proactions.record = AsyncMock(return_value=proaction)
        publisher.publish = AsyncMock()
        summarizer.maybe_summarize = AsyncMock()
        return await execute_proaction(_gateway(result), proaction)


async def _journal(user_id: str = "user-1") -> list[JournalRun]:
    return await journal_repo.find_by_user(user_id, datetime.now(UTC) - timedelta(days=1))


class TestPlanningIsKept:
    async def test_the_journal_shows_the_instructions_read_and_the_proactions_scheduled(self, journal_db):
        """Criterion 1: after the planning, the journal shows what it read and what it scheduled."""
        result = await scheduler.generate_proactions(_gateway(PLANNING_RESULT), "user-1")

        [run] = await _journal()
        entry = run.to_dict()
        assert (entry["kind"], entry["status"]) == ("planning", "completed")
        assert entry["instructions"] == [INSTRUCTION]
        assert entry["scheduled"] == [
            {
                "id": SCHEDULED["id"],
                "prompt": SCHEDULED["prompt"],
                "summary": "Digest du matin",
                "scheduledAt": SCHEDULED["scheduledAt"],
            }
        ]
        assert entry["response"] == "Proaction posée : le digest à 7 h."
        assert [call["name"] for call in entry["toolCalls"]] == [
            "list_instructions",
            "get_events_by_date",
            "schedule_proaction",
            "schedule_proaction",
        ]
        assert entry["toolCalls"][3]["error"] == "scheduled_at is in the past"
        assert (entry["inputTokens"], entry["outputTokens"]) == (1200, 80)
        assert result["journal"]["id"] == entry["id"]

    async def test_the_day_planned_is_the_users_own(self, journal_db):
        # 23:30 UTC on the 28th is already the 29th in Paris.
        await scheduler.generate_proactions(_gateway(PLANNING_RESULT), "user-1", now=datetime(2026, 10, 28, 23, 30, tzinfo=UTC))

        [run] = await _journal()
        assert run.day == "2026-10-29"

    async def test_a_planning_that_raises_is_kept_failed_with_its_error_and_still_raises(self, journal_db):
        with pytest.raises(RuntimeError, match="MCP down"):
            await scheduler.generate_proactions(_gateway(RuntimeError("MCP down")), "user-1")

        [run] = await _journal()
        assert (run.status, run.error) == ("failed", "MCP down")

    async def test_a_model_that_answered_with_an_error_is_a_failed_planning(self, journal_db):
        answered = {"response": "AI service error: overloaded", "tool_calls": [], "error": "overloaded"}

        await scheduler.generate_proactions(_gateway(answered), "user-1")

        [run] = await _journal()
        assert (run.status, run.error) == ("failed", "overloaded")

    async def test_a_dry_run_is_not_kept(self, journal_db):
        await scheduler.generate_proactions(_gateway(PLANNING_RESULT), "user-1", dry_run=True)

        assert await _journal() == []

    async def test_a_long_tool_result_is_cut_short(self, journal_db):
        long = {"response": "ok", "tool_calls": [{"name": "get_events_by_date", "input": {}, "result": "x" * 5000}]}

        await scheduler.generate_proactions(_gateway(long), "user-1")

        [run] = await _journal()
        assert len(run.tool_calls[0]["result"]) == RESULT_MAX_CHARS + 1

    async def test_a_journal_that_cannot_be_written_does_not_fail_the_planning(self, journal_db):
        with patch.object(journal_repo, "record_planning", AsyncMock(side_effect=RuntimeError("db down"))):
            result = await scheduler.generate_proactions(_gateway(PLANNING_RESULT), "user-1")

        assert result["response"] == PLANNING_RESULT["response"]
        assert "journal" not in result


class TestProactionRunIsKept:
    async def test_a_failing_tool_is_shown_with_its_error(self, journal_db):
        """Criterion 2: a proaction in trouble shows the faulty tool and its error."""
        await _execute(
            {
                "response": "Je n'ai pas pu lire ton agenda.",
                "tool_calls": [
                    {"name": "get_events_by_date", "input": {"date": "2026-10-29"}, "result": '{"error": "Agenda unavailable"}'},
                    {"name": "get_weather", "input": {}, "result": '{"sky": "éclaircies"}'},
                ],
                "usage": {"input_tokens": 900, "output_tokens": 40},
            }
        )

        [run] = await _journal()
        entry = run.to_dict()
        assert (entry["kind"], entry["proactionId"], entry["summary"]) == ("proaction", SCHEDULED["id"], "Digest du matin")
        failing = [(call["name"], call["error"]) for call in entry["toolCalls"] if call["error"]]
        assert failing == [("get_events_by_date", "Agenda unavailable")]
        assert (entry["inputTokens"], entry["outputTokens"]) == (900, 40)
        assert entry["durationMs"] >= 0

    async def test_a_run_that_raises_is_kept_failed_with_its_error(self, journal_db):
        outcome = await _execute(RuntimeError("LLM timeout"))

        [run] = await _journal()
        assert outcome["status"] == "failed"
        assert (run.status, run.error, run.prompt) == ("failed", "LLM timeout", SCHEDULED["prompt"])


class TestJournalRoutes:
    async def test_relaunching_the_planning_returns_its_entry_and_the_journal_lists_it(self, journal_db):
        """« Relancer la planification maintenant », then the journal: the run, what it read and scheduled."""
        with patch("app.api.routes.llm_gateway", _gateway(PLANNING_RESULT)):
            relaunched = _client().post("/journal/planning")

        assert relaunched.status_code == 200
        assert relaunched.json()["scheduled"][0]["id"] == SCHEDULED["id"]

        listed = _client().get("/journal")
        assert listed.status_code == 200
        [entry] = listed.json()
        assert entry["id"] == relaunched.json()["id"]
        assert entry["instructions"] == [INSTRUCTION]

    async def test_a_proaction_run_carries_the_planning_that_scheduled_it(self, journal_db):
        await scheduler.generate_proactions(_gateway(PLANNING_RESULT), "user-1")
        await _execute({"response": "Voici ton digest.", "tool_calls": []})
        await _execute({"response": "Pense au plombier.", "tool_calls": []}, proaction_id="01JOTHER000000000000000000")

        other, proaction, planning = _client().get("/journal").json()

        assert proaction["origin"] == {"planningId": planning["id"], "day": planning["day"], "instructions": [INSTRUCTION]}
        assert other["origin"] is None

    async def test_a_planning_that_fails_answers_502_and_is_in_the_journal(self, journal_db):
        with patch("app.api.routes.llm_gateway", _gateway(RuntimeError("MCP down"))):
            relaunched = _client().post("/journal/planning")

        assert relaunched.status_code == 502
        [entry] = _client().get("/journal").json()
        assert (entry["status"], entry["error"]) == ("failed", "MCP down")

    async def test_the_journal_is_the_callers_alone(self, journal_db):
        await scheduler.generate_proactions(_gateway(PLANNING_RESULT), "user-2")

        assert _client("user-1").get("/journal").json() == []
        assert len(_client("user-2").get("/journal").json()) == 1

    async def test_older_runs_than_asked_are_left_out(self, journal_db):
        await journal_repo.record_planning("user-1", "2026-10-01", datetime.now(UTC) - timedelta(days=10), 0, result={})

        assert _client().get("/journal?days=7").json() == []
        assert len(_client().get("/journal?days=30").json()) == 1

    @pytest.mark.parametrize(("method", "path"), [("get", "/journal"), ("post", "/journal/planning")])
    def test_without_a_token_nothing_is_read_nor_run(self, method, path):
        response = getattr(_client(None), method)(path)

        assert response.status_code in (401, 403)

    def test_a_day_count_out_of_range_is_refused(self):
        assert _client().get("/journal?days=0").status_code == 422


class TestPurge:
    async def test_runs_past_the_retention_go_and_the_others_stay(self, journal_db, monkeypatch):
        monkeypatch.setattr(scheduler.settings, "proaction_journal_retention_days", 30)
        now = datetime(2026, 10, 29, 12, 0, tzinfo=UTC)
        await journal_repo.record_planning("user-1", "2026-09-01", now - timedelta(days=31), 0, result={})
        await journal_repo.record_planning("user-1", "2026-10-28", now - timedelta(days=1), 0, result={})

        assert await scheduler.purge_journal(now) == 1

        [kept] = await journal_repo.find_by_user("user-1", now - timedelta(days=366))
        assert kept.day == "2026-10-28"

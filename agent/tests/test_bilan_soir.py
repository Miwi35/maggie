"""The evening review on the agent's side (MAG-749): out of the planning prompt (MAG-787), and the on-demand run scripted by the fake model against the simulated API."""

import json
from datetime import UTC, date, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app import bilan
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient, expand_days
from app.llm.gateway import PROACTION_MAX_ITERATIONS
from app.llm.runner import run_tool_loop
from app.llm.tool_blocks import replay
from app.llm.tools import ToolRouter
from app.native_tools import build_native_registry
from app.proactions.api import Proaction
from app.queue import proaction_consumer
from app.queue.scheduler import daily_planning_prompt
from tests.fake_proaction_api import FakeProactionApi

USER = "user-1"
DAY = date(2030, 11, 14)
EVENT_ID = "01HZX3Q8R5T6V7W8X9Y0Z1A2B3"
PREVIOUS_ID = "01HZX3Q8R5T6V7W8X9Y0Z1A2B4"
TASK_ID = "01HZX3Q8R5T6V7W8X9Y0Z1A2B5"
OFFERED = [
    {"name": name, "input_schema": {}}
    for name in (
        "get_skill",
        "get_activity_status",
        "save_bilan_step",
        "get_events",
        "get_tasks",
        "create_task",
        "schedule_proaction",
        "add_instruction",
        "add_bilan_consigne",
        "get_daily_balance",
        "get_daily_energy",
        "get_health_day",
        "update_health_day",
        "set_trip",
        "update_task",
    )
]


def proaction(summary: str = bilan.BILAN_SUMMARY) -> Proaction:
    return Proaction(id="p-1", user_id=USER, prompt="Fais le bilan", status="running", summary=summary, scheduled_at=None)


class TestThePlanningPrompt:
    def test_the_evening_is_no_longer_the_model_s(self):
        """The code lays the review down from the settings (MAG-787): the planning prompt says nothing of it."""
        prompt = daily_planning_prompt(DAY)

        assert "Bilan du soir" not in prompt
        assert bilan.BILAN_SUMMARY not in prompt
        assert "heure de coucher" not in prompt


class TestTheReviewMessage:
    @pytest.fixture()
    def run(self, monkeypatch):
        monkeypatch.setattr(proaction_consumer, "resolve_user_timezone", AsyncMock(return_value=ZoneInfo("UTC")))
        monkeypatch.setattr(proaction_consumer, "_record", AsyncMock())
        monkeypatch.setattr(proaction_consumer, "_journal", AsyncMock())
        monkeypatch.setattr(proaction_consumer, "notify_message", AsyncMock())
        create = AsyncMock(return_value=SimpleNamespace(id="m", content=bilan.BILAN_AVAILABLE))
        monkeypatch.setattr(proaction_consumer.message_repo, "create", create)
        gateway = AsyncMock()
        gateway.proaction.return_value = {"response": "Rappel", "tool_calls": [], "context_id": None}
        gateway.proaction_context.return_value = "ctx-1"
        return gateway, create

    async def test_the_review_says_the_owner_s_sentence(self, run):
        gateway, create = run

        await proaction_consumer.execute_proaction(gateway, proaction())

        assert create.await_args.kwargs["content"] == bilan.BILAN_AVAILABLE
        assert create.await_args.kwargs["context_id"] == "ctx-1"

    async def test_the_review_prepares_tomorrow_with_the_model_once(self, run):
        gateway, create = run

        await proaction_consumer.execute_proaction(gateway, proaction())

        gateway.proaction.assert_awaited_once()
        assert "Prépare l'étape Demain" in gateway.proaction.await_args.args[0]
        assert datetime.now(UTC).date().isoformat() in gateway.proaction.await_args.args[0]
        blocked = gateway.proaction.await_args.kwargs["blocked_tools"]
        assert {"update_task", "create_task", "send_voice_message"} <= blocked

    async def test_the_review_message_stays_the_owner_s_sentence(self, run):
        gateway, create = run
        gateway.proaction.return_value = {"response": "Demain est prêt.", "tool_calls": [], "context_id": None}

        await proaction_consumer.execute_proaction(gateway, proaction())

        assert create.await_args.kwargs["content"] == bilan.BILAN_AVAILABLE

    async def test_a_failed_preparation_still_announces_the_review(self, run):
        gateway, create = run
        gateway.proaction.side_effect = RuntimeError("boom")

        await proaction_consumer.execute_proaction(gateway, proaction())

        assert create.await_args.kwargs["content"] == bilan.BILAN_AVAILABLE
        proaction_consumer.notify_message.assert_awaited_once()

    async def test_the_review_message_carries_a_bilan_block(self, run):
        gateway, create = run

        await proaction_consumer.execute_proaction(gateway, proaction())

        today = datetime.now(UTC).date().isoformat()
        assert create.await_args.kwargs["ui_blocks"] == [
            {"type": "bilan", "day": today, "route": "maggie://activity/bilan"}
        ]

    async def test_the_review_is_recorded_as_a_message_in_the_thread(self, run):
        gateway, create = run

        await proaction_consumer.execute_proaction(gateway, proaction())

        assert proaction_consumer._record.await_args.kwargs["delivery"] == "thread"

    async def test_the_review_notifies_one_message_linking_the_chat(self, run):
        gateway, create = run

        await proaction_consumer.execute_proaction(gateway, proaction())

        proaction_consumer.notify_message.assert_awaited_once_with(
            USER, "m", bilan.BILAN_AVAILABLE, has_audio=False, screen=None
        )

    async def test_another_proaction_carries_no_bilan_block(self, run):
        gateway, create = run

        await proaction_consumer.execute_proaction(gateway, proaction("Rappel"))

        assert create.await_args.kwargs["ui_blocks"] is None

    async def test_a_nudge_laid_down_before_says_nothing(self, run):
        gateway, create = run

        outcome = await proaction_consumer.execute_proaction(gateway, proaction(bilan.RETIRED_RELANCE_SUMMARY))

        assert outcome == {"status": "completed", "message": None, "toolCalls": []}
        gateway.proaction.assert_not_awaited()
        create.assert_not_awaited()


class BilanApi(FakeProactionApi):
    """The simulated API with the bilan's status: written by save_bilan_step, read by get_activity_status."""

    status: dict | None = None
    tasks: list[dict] = []
    health_day: dict | None = None
    trips: list[dict] = []
    updated: list[dict] = []
    bilan_tomorrow: list[dict] = []

    def _set_trip(self, arguments: dict, user_id: str | None) -> dict:
        self.trips.append(arguments)
        return {"success": True, "trip": {"id": "trip-1", **arguments}}

    def _get_activity_status(self, arguments: dict, user_id: str | None) -> dict | None:
        return self.status

    def _update_task(self, arguments: dict, user_id: str | None) -> dict:
        self.updated.append(arguments)
        return {"success": True, "task": arguments}

    def _save_bilan_step(self, arguments: dict, user_id: str | None) -> dict:
        kept = (self.status or {}).get("state", {})
        chosen = {key: arguments.get(key, kept[key]) for key in ("tomorrowTasks", "tomorrowSpares") if key in arguments or key in kept}
        self.status = {
            "activity": "bilan",
            "origin": {"kind": "day", "id": arguments["day"]},
            "state": {
                **({"step": arguments["step"]} if "step" in arguments else {}),
                "instructions": arguments.get("instructions", []),
                **chosen,
                **({"answers": arguments["answers"]} if "answers" in arguments else {}),
            },
        }
        return {"success": True, "status": self.status}

    def _get_daily_balance(self, arguments: dict, user_id: str | None) -> dict:
        return {"date": arguments["date"], "eatenKcal": 1806, "expendedKcal": 2118, "balanceKcal": -312}

    def _get_daily_energy(self, arguments: dict, user_id: str | None) -> dict:
        return {"days": [{"date": arguments["from"], "steps": 8420}]}

    def _get_health_day(self, arguments: dict, user_id: str | None) -> dict:
        return {"day": {"date": arguments["date"], "mood": None, "fatigue": None}}

    def _update_health_day(self, arguments: dict, user_id: str | None) -> dict:
        self.health_day = arguments
        return {"success": True, "day": arguments}

    def _get_events(self, arguments: dict, user_id: str | None) -> dict:
        return {"events": []}

    def _get_tasks(self, arguments: dict, user_id: str | None) -> dict:
        if arguments.get("dated") is False:
            return {
                "tasks": [
                    {
                        "id": TASK_ID,
                        "title": "Trier les papiers",
                        "dueDate": None,
                        "scheduledDate": None,
                        "urgency": "normal",
                        "orderScore": 1,
                        "overdueDays": None,
                    }
                ]
            }
        if "day" in arguments and arguments["day"] != datetime.now(ZoneInfo("Europe/Paris")).date().isoformat():
            return {"tasks": []}
        return {"tasks": [{"id": "t1", "title": "Appeler le garage"}]}

    def _create_task(self, arguments: dict, user_id: str | None) -> dict:
        self.tasks.append(arguments)
        return {"success": True, "task": {"id": "t2", **arguments}}


@pytest.fixture()
def api():
    fake = BilanApi(now=datetime(2020, 1, 1, tzinfo=UTC))
    fake.tasks = []
    fake.trips = []
    fake.updated = []
    learned = AsyncMock(return_value=SimpleNamespace(to_dict=lambda: {"id": "i-1"}))
    fake.learned = learned
    with (
        patch("app.instruction_tools.instruction_repo.store", learned),
        patch("app.llm.tools.mcp_client", fake),
        patch("app.bilan_consigne.mcp_client", fake),
        patch("app.bilan_consigne.instruction_repo.store", learned),
        patch("app.skills.tools.skill_index.get", AsyncMock(return_value="---\nname: bilan-soir\n---\n\nLe bilan.")),
    ):
        yield fake


async def say(prompt: str) -> dict:
    with patch("app.runtime.loop.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": prompt}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(natives=build_native_registry()),
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

        assert names(result) == ["get_skill", "get_activity_status", "save_bilan_step", "get_events", "get_tasks"]
        assert api.status["state"]["step"] == "journee"
        assert "*" not in result["response"] and "#" not in result["response"]


PREPARE_PROMPT = bilan.BILAN_PREPARE_PROMPT.format(day=DAY.isoformat())


class TestThePreparation:
    async def test_the_preparation_reads_tomorrow_and_the_stock(self, api):
        result = await say(PREPARE_PROMPT)

        assert names(result) == ["get_events", "get_tasks", "get_tasks", "save_bilan_step"]
        tomorrow = expand_days("${tomorrow}")
        assert result["tool_calls"][1]["input"] == {"day": tomorrow}
        assert result["tool_calls"][2]["input"] == {"dated": False}

    async def test_the_preparation_keeps_its_choice_in_the_bilan_without_a_step(self, api):
        result = await say(PREPARE_PROMPT)

        assert result["tool_calls"][-1]["input"] == {
            "day": expand_days("${today}"),
            "tomorrowTasks": [{"taskId": TASK_ID, "title": "Trier les papiers", "reason": "sans date"}],
            "tomorrowSpares": [],
        }

    async def test_the_preparation_plans_no_task(self, api):
        await say(PREPARE_PROMPT)

        assert api.updated == []


class TestTheDemainTasks:
    @pytest.fixture(autouse=True)
    def prepared(self, api):
        api.status = {
            "activity": "bilan",
            "origin": {"kind": "day", "id": DAY.isoformat()},
            "state": {
                "instructions": [],
                "tomorrowTasks": [{"taskId": TASK_ID, "title": "Trier les papiers"}],
            },
        }

    async def say_in_thread(self, first: str, second: str) -> dict:
        """The second prompt with the first turn's tool results replayed, as the chat history carries them."""
        opening = await say(first)
        history = [
            {"role": "user", "content": first},
            *replay(opening["blocks"]),
            {"role": "assistant", "content": opening["response"]},
            {"role": "user", "content": second},
        ]
        with patch("app.runtime.loop.record_llm_usage"):
            return await run_tool_loop(
                "Tu es Maggie.",
                history,
                OFFERED,
                client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
                tool_router=ToolRouter(natives=build_native_registry()),
                user_id=USER,
                model="fake",
                source="chat",
                max_iterations=PROACTION_MAX_ITERATIONS,
            )

    async def test_the_demain_step_reads_the_bilan_before_proposing(self, api):
        result = await say("C'est tout pour les consignes")

        assert "get_activity_status" in names(result)
        assert "update_task" not in names(result) and "create_task" not in names(result)

    async def test_nothing_is_planned_before_the_owner_answers(self, api):
        await say("C'est tout pour les consignes")

        assert api.updated == []

    async def test_the_proposal_is_said_for_the_ear(self, api):
        result = await say("C'est tout pour les consignes")

        assert "*" not in result["response"] and "#" not in result["response"]
        assert "papiers" in result["response"]

    async def test_a_yes_plans_the_kept_task_for_tomorrow_without_its_due_date(self, api):
        result = await self.say_in_thread("C'est tout pour les consignes", "Oui, je garde")

        assert names(result) == ["update_task"]
        assert api.updated == [{"id": TASK_ID, "scheduledDate": expand_days("${tomorrow}")}]

    async def test_without_the_bilan_in_the_history_a_yes_writes_nothing(self, api):
        result = await say("Oui, je garde")

        assert api.updated == []
        assert result["response"].startswith("[fake-llm]")


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

        assert names(result) == ["add_bilan_consigne"] * 3
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

    async def test_every_status_entry_points_to_what_was_created(self, api):
        await say(self.DICTATED)

        [proaction] = api.of(USER)
        targets = [i["targetId"] for i in api.status["state"]["instructions"]]
        assert targets == [proaction["id"], "t2", "i-1"]
        assert [i["id"] for i in api.status["state"]["instructions"]] == ["1", "2", "3"]


class TestTheSanteStep:
    ANSWER = "Mon moral à 7 et ma fatigue à 4"

    async def test_mood_and_fatigue_are_written_to_the_journal_of_the_day(self, api):
        result = await say(self.ANSWER)

        assert names(result) == [
            "get_daily_balance",
            "get_daily_energy",
            "get_health_day",
            "update_health_day",
            "save_bilan_step",
        ]
        assert api.health_day is not None
        assert (api.health_day["mood"], api.health_day["fatigue"]) == (7, 4)
        assert api.health_day["date"] == datetime.now(ZoneInfo("Europe/Paris")).date().isoformat()

    async def test_the_answers_are_kept_in_the_bilan_status_for_a_resume(self, api):
        await say(self.ANSWER)

        assert api.status["state"]["step"] == "sante"
        assert api.status["state"]["answers"] == {"mood": 7, "fatigue": 4}

    def test_the_shipped_skill_takes_the_reminder_time_from_date_time(self):
        from pathlib import Path

        skill = (Path(bilan.__file__).parent / "skills" / "bilan-soir.md").read_text(encoding="utf-8")

        assert "Paris" not in skill
        assert "le champ `iso` que renvoie `date_time`" in skill

    async def test_the_skill_asks_a_value_off_the_scale_again_and_says_the_figures_without_verdict(self):
        from pathlib import Path

        skill = (Path(bilan.__file__).parent / "skills" / "bilan-soir.md").read_text(encoding="utf-8")
        step = skill.split("## 4. Santé")[1].split("## 5.")[0]

        assert "hors de 1 à 10" in step and "n'appelle pas" in step
        assert "sans verdict" in step
        assert "update_health_day" in step


class TestTheConsigneTool:
    async def test_a_new_day_starts_the_list_afresh(self, api):
        api.status = {
            "origin": {"kind": "day", "id": "2000-01-01"},
            "state": {"step": "demain", "instructions": [{"id": "1", "text": "vieux", "kind": "task", "targetId": "x"}]},
        }

        out = await ToolRouter(natives=build_native_registry()).call_tool(
            "add_bilan_consigne",
            {"day": "2030-11-14", "kind": "task", "text": "Acheter du pain", "due_date": "2030-11-15"},
            user_id=USER,
        )

        assert json.loads(out)["consigne"]["id"] == "1"
        assert [i["text"] for i in api.status["state"]["instructions"]] == ["Acheter du pain"]

    async def test_a_reminder_without_a_time_creates_nothing(self, api):
        out = await ToolRouter(natives=build_native_registry()).call_tool(
            "add_bilan_consigne", {"day": "2030-11-14", "kind": "reminder", "text": "Appeler le garage"}, user_id=USER
        )

        assert "scheduled_at" in json.loads(out)["error"]
        assert api.of(USER) == [] and api.status is None

    async def test_an_unknown_kind_is_refused(self, api):
        out = await ToolRouter(natives=build_native_registry()).call_tool(
            "add_bilan_consigne", {"day": "2030-11-14", "kind": "idée", "text": "x"}, user_id=USER
        )

        assert "error" in json.loads(out)
        assert api.status is None



class TestTheTripAnswersOfTheDemainStep:
    @staticmethod
    def answer(text: str, event_id: str = EVENT_ID) -> str:
        return f"Bilan du soir, trajet de demain · événement {event_id} « Dentiste » : {text}."

    async def test_the_home_origin_is_kept_by_the_user(self, api):
        result = await say(self.answer("je pars de la maison"))

        assert names(result) == ["set_trip"]
        assert api.trips == [{"eventId": EVENT_ID, "mode": "drive", "origin": "home", "decidedBy": "user"}]

    async def test_the_position_origin_is_kept_by_the_user(self, api):
        await say(self.answer("je pars de ma position"))

        assert api.trips == [{"eventId": EVENT_ID, "mode": "drive", "origin": "position", "decidedBy": "user"}]

    async def test_staying_in_town_starts_from_the_previous_event(self, api):
        await say(self.answer(f"je pars de l’événement {PREVIOUS_ID} « Déjeuner »"))

        assert api.trips == [{"eventId": EVENT_ID, "mode": "drive", "origin": f"event:{PREVIOUS_ID}", "decidedBy": "user"}]

    async def test_the_mode_answer_changes_the_mode(self, api):
        await say(self.answer("j’y vais en transports"))

        assert api.trips[0]["mode"] == "transit"

    async def test_the_parking_answer_calls_nothing(self, api):
        result = await say(self.answer("je me gare dans la rue"))

        assert names(result) == []
        assert api.trips == []


def test_the_skill_prepares_tomorrow_and_writes_only_after_the_answer():
    from pathlib import Path

    skill = (Path(bilan.__file__).parent / "skills" / "bilan-soir.md").read_text(encoding="utf-8")
    prepare = skill.split("## Préparer demain")[1].split("## 2.")[0]
    demain = skill.split("## 6. Demain")[1].split("## Quitter")[0]

    for word in ("dated: false", "urgency", "orderScore", "tomorrowTasks", "tomorrowSpares", "Pas de nombre fixe"):
        assert word in prepare
    assert "scheduledDate" in demain and "Rien n'est écrit avant sa réponse" in demain
    assert "DayProposal" not in skill and "day-plan" not in skill

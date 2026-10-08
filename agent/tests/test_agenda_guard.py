"""MAG-349: Maggie answered « demain, vendredi 3 octobre, de 19 h à minuit » without opening the agenda.

The event was that evening, the 8th at 19 h. The prompt already tells her to call the reading
tools; this is the check that does not depend on the model reading it: an answer to an
agenda question that cites a date or an hour, in a turn where no agenda tool ran, is sent
back once (`app.llm.claim_guard`). The guard alone, then both tool loops on the shipped
fake-llm scenarios.
"""

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.claim_guard import (
    AGENDA_NUDGE,
    NOT_READ_MESSAGE,
    ClaimGuard,
    Verdict,
    asks_about_the_agenda,
    cites_a_date,
    question_of,
)
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from tests.test_reminder_guard import stream_turn, the_bubble

JULIE = "Quand est-ce que je vois Julie ?"
JULIE_TWICE = "Quand est-ce que je revois Julie ?"
WRONG_DAY = "Demain, vendredi 3 octobre, de 19 h à minuit à Rennes."
TONIGHT = "Vous voyez Julie ce soir à 19 h : « Soirée à Rennes avec Julie », à Rennes."

OFFERED = [
    {"name": name, "input_schema": {}}
    for name in (
        "date_time",
        "get_events_by_date",
        "get_upcoming_events",
        "create_event",
        "schedule_proaction",
        "list_proactions",
        "store_memory",
        "search_memory",
        "update_memory",
    )
]
EVENTS = json.dumps({"member": [{"id": "e-1", "summary": "Soirée à Rennes avec Julie", "startAt": "2026-10-08T19:00"}]})


class TestTheQuestion:
    @pytest.mark.parametrize(
        "text",
        [
            "Quand est-ce que je vois Julie ?",
            "quand je vois Julie",
            "À quelle heure est le dentiste ?",
            "C'est quoi mon prochain rendez-vous ?",
            "Prochain rdv avec le médecin ?",
            "Qu'est-ce que j'ai de prévu demain ?",
            "J'ai quoi de prévu ce week-end",
            "Qu'est-ce que j'ai demain ?",
            "Qu'ai-je jeudi ?",
            "Mon agenda de la semaine",
            "Je suis libre quand ?",
            "[Contexte de l'écran]\nPage : x\n\nQuand est-ce qu'on déjeune ?",
        ],
    )
    def test_a_question_about_the_agenda(self, text):
        assert asks_about_the_agenda(text)

    @pytest.mark.parametrize(
        "text",
        [
            "",
            "Quel jour sommes-nous ?",
            "Quelle heure est-il ?",
            "À quelle heure est-il ?",
            "Ajoute du lait à ma liste",
            "Rappelle-moi de sortir le linge dans 4 minutes",
            "Merci !",
            "Crée un événement demain à 14h",
        ],
    )
    def test_anything_else_is_not(self, text):
        assert not asks_about_the_agenda(text)


class TestTheQuestionIsWhatTheUserSaid:
    def test_the_screen_block_is_not_the_question(self):
        page = "[Contexte de l'écran]\nPage : colis\nQuand sera livré mon colis ?\n\nC'est quoi ce produit ?"

        assert question_of([{"role": "user", "content": page}]) == "C'est quoi ce produit ?"
        assert not asks_about_the_agenda(question_of([{"role": "user", "content": page}]))

    def test_a_question_without_a_screen_is_kept(self):
        assert question_of([{"role": "user", "content": JULIE}]) == JULIE


class TestTheAnswer:
    @pytest.mark.parametrize(
        "text",
        [
            WRONG_DAY,
            TONIGHT,
            "Jeudi 8 octobre à 19 h.",
            "Le 12 mars.",
            "Le 1er novembre, toute la journée.",
            "C'est demain.",
            "Après-demain matin.",
            "Ce soir à 19:00.",
            "Samedi, à midi.",
            "À minuit.",
            "Le 2026-10-08 à 19h30.",
            "Vous voyez Julie vendredi.",
            "Votre prochain rendez-vous est dans trois jours.",
        ],
    )
    def test_a_date_or_an_hour_is_cited(self, text):
        assert cites_a_date(text)

    @pytest.mark.parametrize(
        "text",
        [
            "",
            "Je regarde votre agenda.",
            "De quelle Julie parlez-vous ?",
            "Je n'ai pas accès à cette information pour l'instant.",
            "Vous n'avez rien de prévu.",
            "Avec plaisir, monsieur.",
        ],
    )
    def test_anything_else_is_not(self, text):
        assert not cites_a_date(text)


class TestTheGuard:
    def guard(self, question: str = JULIE, tools: list[dict] | None = OFFERED) -> ClaimGuard:
        return ClaimGuard(tools, question=question)

    def test_a_date_cited_without_reading_the_agenda_is_sent_back_once_then_given_up(self):
        guard = self.guard()

        assert guard.review(WRONG_DAY) is Verdict.RETRY
        assert guard.review(WRONG_DAY) is Verdict.GIVE_UP
        assert guard.honest_answer() == NOT_READ_MESSAGE

    @pytest.mark.parametrize("tool", ["get_events_by_date", "get_upcoming_events"])
    def test_reading_the_agenda_backs_the_answer(self, tool):
        guard = self.guard()
        guard.record(tool, EVENTS)

        assert guard.review(TONIGHT) is Verdict.ACCEPT

    def test_an_empty_agenda_is_still_a_reading(self):
        guard = self.guard()
        guard.record("get_events_by_date", json.dumps({"member": []}))

        assert guard.review("Vous n'avez rien ce soir, ni demain à 19 h.") is Verdict.ACCEPT

    def test_a_list_result_is_a_reading_too(self):
        guard = self.guard()
        guard.record("get_upcoming_events", json.dumps([]))

        assert guard.review(TONIGHT) is Verdict.ACCEPT

    @pytest.mark.parametrize("result", ['{"error": "Tool call failed: timeout"}', "not json", ""])
    def test_a_failed_reading_does_not_count(self, result):
        guard = self.guard()
        guard.record("get_events_by_date", result)

        assert guard.review(WRONG_DAY) is Verdict.RETRY

    def test_the_clock_is_not_the_agenda(self):
        guard = self.guard()
        guard.record("date_time", '{"result": {"iso": "2026-10-08T13:45+02:00"}}')

        assert guard.review(WRONG_DAY) is Verdict.RETRY

    def test_a_task_list_backs_a_deadline(self):
        guard = self.guard()
        guard.record("get_tasks", json.dumps([{"title": "Rendre le dossier", "due": "2026-10-12"}]))

        assert guard.review("Le dossier est à rendre le 12 octobre.") is Verdict.ACCEPT

    def test_memory_is_not_the_agenda(self):
        guard = self.guard()
        guard.record("search_memory", json.dumps([{"id": "m-1", "content": "Julie habite à Rennes"}]))

        assert guard.review(WRONG_DAY) is Verdict.RETRY

    def test_an_event_just_created_backs_the_hour_it_announces(self):
        guard = self.guard("Quand est-ce que je vois Julie ? Crée-le.")
        guard.record("create_event", json.dumps({"event": {"id": "e-1", "startAt": "2026-10-08T19:00"}}), {})

        assert guard.review(TONIGHT) is Verdict.ACCEPT

    def test_a_reminder_scheduled_this_turn_backs_the_hour_it_announces(self):
        guard = self.guard("Quand est-ce que tu me rappelles ?")
        guard.record("schedule_proaction", json.dumps({"id": "p-1", "status": "pending", "error": None}))

        assert guard.review("Je vous rappellerai demain à 9 h.") is Verdict.ACCEPT

    def test_a_pending_reminder_listed_backs_the_hour_it_gives(self):
        guard = self.guard("Quand est-ce que tu me rappelles ?")
        guard.record("list_proactions", json.dumps([{"id": "p-1", "status": "pending"}]))

        assert guard.review("Demain à 9 h.") is Verdict.ACCEPT

    def test_a_question_that_is_not_about_the_agenda_is_left_alone(self):
        assert self.guard("Quel jour sommes-nous ?").review("Nous sommes jeudi 8 octobre.") is Verdict.ACCEPT
        assert self.guard("Merci !").review("Avec plaisir, à demain 9 h.") is Verdict.ACCEPT

    def test_an_answer_that_cites_nothing_is_left_alone(self):
        assert self.guard().review("De quelle Julie parlez-vous ?") is Verdict.ACCEPT

    def test_no_question_no_check(self):
        assert ClaimGuard(OFFERED).review(WRONG_DAY) is Verdict.ACCEPT

    def test_a_turn_not_offered_an_agenda_tool_is_left_alone(self):
        # An approved action or a sub-agent without the calendar has nothing to read it with.
        assert self.guard(tools=None).review(WRONG_DAY) is Verdict.ACCEPT
        assert self.guard(tools=[{"name": "date_time"}]).review(WRONG_DAY) is Verdict.ACCEPT

    def test_the_relaunch_says_to_read_the_agenda_and_not_to_quote_a_date_from_memory(self):
        guard = self.guard()
        guard.review(WRONG_DAY)
        nudge = guard.nudge()

        assert nudge == {"role": "user", "content": [{"type": "text", "text": AGENDA_NUDGE}]}
        assert "get_events_by_date" in AGENDA_NUDGE
        assert "get_upcoming_events" in AGENDA_NUDGE

    def test_the_honest_answer_gives_no_date(self):
        assert not cites_a_date(NOT_READ_MESSAGE)

    def test_silence_after_the_relaunch_is_a_failure(self):
        guard = self.guard()
        guard.review(WRONG_DAY)

        assert guard.review("  ") is Verdict.GIVE_UP

    def test_an_unbacked_reminder_is_still_named_before_the_agenda(self):
        guard = self.guard("Quand est-ce que tu me rappelles ?")

        assert guard.review("Je vous rappellerai demain à 9 h.") is Verdict.RETRY
        assert "schedule_proaction" in guard.nudge()["content"][0]["text"]


@pytest.fixture()
def fake_client() -> FakeAnthropicClient:
    return FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)


@pytest.fixture()
def agenda():
    """The calendar: only the MCP call is replaced, the loop and the guard are the real ones."""
    with patch("app.llm.tools.ToolRouter.call_tool", new_callable=AsyncMock) as call_tool:
        call_tool.return_value = EVENTS
        yield call_tool


class TestTheToolLoop:
    async def _run(self, client, question: str, router) -> tuple[dict, list]:
        messages = [{"role": "user", "content": question}]
        with patch("app.llm.runner.record_llm_usage"):
            result = await run_tool_loop(
                "Tu es Maggie.",
                messages,
                OFFERED,
                client=client,
                tool_router=router,
                user_id="user-1",
                model="fake",
            )
        return result, messages

    async def test_an_answer_from_memory_is_relaunched_and_the_agenda_is_read(self, fake_client):
        router = MagicMock()
        router.call_tool = AsyncMock(return_value=EVENTS)

        result, messages = await self._run(fake_client, JULIE, router)

        router.call_tool.assert_awaited_once()
        assert router.call_tool.await_args.args[0] == "get_upcoming_events"
        assert [call["name"] for call in result["tool_calls"]] == ["get_upcoming_events"]
        assert result["response"] == TONIGHT
        assert messages[2] == {"role": "user", "content": [{"type": "text", "text": AGENDA_NUDGE}]}

    async def test_an_answer_from_memory_twice_ends_on_the_truth(self, fake_client):
        router = MagicMock()
        router.call_tool = AsyncMock(return_value=EVENTS)

        result, _ = await self._run(fake_client, JULIE_TWICE, router)

        router.call_tool.assert_not_awaited()
        assert result["response"] == NOT_READ_MESSAGE


class TestTheStreamedPath:
    async def test_an_answer_from_memory_is_relaunched_and_the_agenda_is_read(self, fake_client, agenda):
        events, saved = await stream_turn(fake_client, JULIE, OFFERED)

        called = [e["toolName"] for e in events if e["type"] == "TOOL_CALL_END"]
        assert called == ["get_upcoming_events"]
        assert [message["content"] for message in saved] == [TONIGHT]
        assert the_bubble(events) == TONIGHT

    async def test_an_answer_from_memory_twice_ends_on_the_truth(self, fake_client, agenda):
        events, saved = await stream_turn(fake_client, JULIE_TWICE, OFFERED)

        assert [e for e in events if e["type"] == "TOOL_CALL_END"] == []
        assert [message["content"] for message in saved] == [NOT_READ_MESSAGE]
        assert the_bubble(events) == NOT_READ_MESSAGE

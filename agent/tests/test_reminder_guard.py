"""MAG-339: Maggie announced « je vous rappellerai » without scheduling the reminder.

Three layers, from the inside out: the guard alone (the pattern, the bookkeeping), the
non-streamed `run_tool_loop`, and the streamed `chat_stream` — the last two driven by the
shipped fake-llm scenarios, so the fixtures a journey can use are the ones proved here.
"""

import json
from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest
import yaml

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.reminder_guard import (
    NOT_SCHEDULED_MESSAGE,
    NUDGE,
    SCHEDULE_TOOL,
    ReminderGuard,
    Verdict,
    claims_reminder,
)
from app.llm.runner import run_tool_loop
from app.llm.streaming import StreamingGateway
from app.llm.tools import ToolRouter

WATER = "Rappelle-moi de boire de l'eau dans 1 minute"
PLANTS = "Rappelle-moi d'arroser les plantes dans 1 minute"
MOTHER = "Rappelle-moi d'appeler ma mère dans 5 minutes"
LAUNDRY = "Rappelle-moi de sortir le linge dans 4 minutes"
SCHEDULED_ANSWER = "C'est programmé, monsieur : je vous rappellerai de boire de l'eau à 16h05."
FALSE_CLAIM = "C'est noté, monsieur. Je vous rappellerai de boire de l'eau à 16h05."

OFFERED = [{"name": "date_time", "input_schema": {}}, {"name": SCHEDULE_TOOL, "input_schema": {}}]
SCHEDULED = json.dumps({"id": "pro-1", "status": "pending", "scheduledAt": "2099-10-07T14:05:00+00:00"})


class TestTheClaim:
    @pytest.mark.parametrize(
        "text",
        [
            "C'est noté, monsieur. Je vous rappellerai de boire de l'eau à 16h05.",
            "JE TE RAPPELLERAI dans une minute.",
            "Je te le rappellerai demain.",
            "je vous rappelle dans 5 minutes",
            "Rappel programmé pour 16h05.",
            "Votre rappel est bien programmé.",
            "J'ai programmé un rappel à 18h.",
            "Je t'enverrai un rappel ce soir.",
            "Je vous préviendrai à 9h.",
            "Je te préviendrai demain matin.",
            "Je vous rappelle à 16h05.",
            "Je vous préviens dans 5 minutes.",
            "Rappel enregistré pour 16h05.",
            "Je te préviendrai une heure avant.",
            "Je vous préviendrai lundi.",
            "Je t'enverrai une notification à 20h44 pour appeler ta mère.",
            "Je vous enverrai une notification à 20h43.",
            "Je t\u2019enverrai une alerte dans 4 minutes.",
            "Je vous enverrai un message à 9h pour sortir le linge.",
            "Je te notifierai à 20h44.",
            "Je vous notifierai dans 5 minutes.",
            "Je te ferai signe à 16h05.",
            "Tu recevras une notification à 20h44.",
            "Vous recevrez un rappel à 16h05.",
            "Je te notifie à 23h47 pour sortir le linge.",
            "Je vous notifie dans 5 minutes.",
            "Je t'envoie une notification à 23h50.",
            "Je vous envoie un rappel à 9h.",
            "Je t'alerte à 23h50 pour vérifier le four.",
            "Je t'alerterai dans 6 minutes.",
            "Je t'avertis à 23h50.",
            "Tu seras notifié à 23h47.",
            "Vous serez prévenu à 9h.",
            "Notification programmée à 23h47.",
        ],
    )
    def test_an_announced_reminder_is_a_claim(self, text):
        assert claims_reminder(text)

    @pytest.mark.parametrize(
        "text",
        [
            "",
            NOT_SCHEDULED_MESSAGE,
            "Je ne vous rappellerai pas, il manque l'heure.",
            "Le rappel n'a pas été programmé.",
            "Le rappel est déjà programmé pour 16h05.",
            "À quelle heure voulez-vous ce rappel ?",
            "Vous avez un déjeuner avec Alex à midi.",
            "Je te préviendrai si la météo change.",
            "Je vous rappelle que la réunion est demain à 9h.",
            "Je te préviens, la réunion est à 9h.",
            "Je ne t'enverrai pas de notification, il manque l'heure.",
            "Je t'enverrai le résumé de la réunion.",
            "Vous recevrez le devis par courriel.",
            "Je te ferai signe si j'ai du nouveau.",
            "Je ne te notifie pas, il manque l'heure.",
            "Je t'avertis que la réunion est à 9h.",
            "Je t'alerte : la réunion est à 9h.",
            "Je te notifie que ta réunion est à 9h.",
            "Je t'alerte si la réunion est à 9h.",
            "Je t'avertis : l'événement du 12 est annulé.",
            "Je te notifie dès que le colis arrive.",
            "Je t'envoie le résumé de la réunion.",
            "Tu seras notifié si le prix baisse.",
            "Vous serez prévenu de tout changement.",
        ],
    )
    def test_anything_else_is_not(self, text):
        assert not claims_reminder(text)


class TestTheGuard:
    def test_a_claim_without_the_tool_is_sent_back_once_then_given_up(self):
        guard = ReminderGuard(OFFERED)

        assert guard.review(FALSE_CLAIM) is Verdict.RETRY
        assert guard.review(FALSE_CLAIM) is Verdict.GIVE_UP

    def test_a_scheduled_reminder_is_never_sent_back(self):
        guard = ReminderGuard(OFFERED)
        guard.record(SCHEDULE_TOOL, SCHEDULED)

        assert guard.review(FALSE_CLAIM) is Verdict.ACCEPT

    def test_a_real_proaction_with_an_empty_error_field_backs_the_claim(self):
        # Production, 7 Oct.: the stored proaction carries `"error": null`, and the guard took
        # the key for a failure, so a reminder that was scheduled was told it was not.
        guard = ReminderGuard(OFFERED)
        guard.record(
            SCHEDULE_TOOL,
            json.dumps({"id": "p-1", "status": "pending", "response": None, "error": None, "scheduledAtLocal": "18h00"}),
        )

        assert guard.review(FALSE_CLAIM) is Verdict.ACCEPT

    def test_a_reminder_held_for_approval_is_not_scheduled_twice(self):
        guard = ReminderGuard(OFFERED)
        guard.record(SCHEDULE_TOOL, json.dumps({"status": "pending_approval", "approval_id": "a-1"}))

        assert guard.review(FALSE_CLAIM) is Verdict.ACCEPT

    @pytest.mark.parametrize("result", ['{"error": "Invalid datetime format: demain"}', "not json"])
    def test_a_failed_schedule_does_not_count(self, result):
        guard = ReminderGuard(OFFERED)
        guard.record(SCHEDULE_TOOL, result)

        assert guard.review(FALSE_CLAIM) is Verdict.RETRY

    def test_another_tool_does_not_count(self):
        guard = ReminderGuard(OFFERED)
        guard.record("date_time", '{"result": {"iso": "2026-10-07T16:05+02:00"}}')

        assert guard.review(FALSE_CLAIM) is Verdict.RETRY

    def test_an_event_with_reminders_backs_the_claim(self):
        # « préviens-moi une heure avant » is create_event's job (its `reminders`), not a proaction.
        guard = ReminderGuard(OFFERED)
        guard.record("create_event", '{"event": {"id": "e-1"}}', {"title": "Dentiste", "reminders": [60]})

        assert guard.review("C'est noté, je vous préviendrai à 9h, une heure avant.") is Verdict.ACCEPT

    def test_an_event_without_reminders_does_not(self):
        guard = ReminderGuard(OFFERED)
        guard.record("create_event", '{"event": {"id": "e-1"}}', {"title": "Dentiste"})

        assert guard.review(FALSE_CLAIM) is Verdict.RETRY

    def test_a_pending_reminder_from_an_earlier_turn_backs_the_claim(self):
        guard = ReminderGuard(OFFERED)
        guard.record("list_proactions", json.dumps([{"id": "pro-1", "status": "pending"}]))

        assert guard.review("Oui, je vous rappellerai à 16h05.") is Verdict.ACCEPT

    @pytest.mark.parametrize("listed", [[], [{"id": "pro-1", "status": "completed"}]])
    def test_no_pending_reminder_listed_does_not(self, listed):
        guard = ReminderGuard(OFFERED)
        guard.record("list_proactions", json.dumps(listed))

        assert guard.review(FALSE_CLAIM) is Verdict.RETRY

    def test_an_answer_without_a_claim_passes(self):
        assert ReminderGuard(OFFERED).review("Vous avez trois rendez-vous demain.") is Verdict.ACCEPT

    def test_a_turn_not_offered_the_tool_is_left_alone(self):
        # The announcement of an approved schedule_proaction runs with no tools at all.
        assert ReminderGuard(None).review(FALSE_CLAIM) is Verdict.ACCEPT
        assert ReminderGuard([{"name": "date_time"}]).review(FALSE_CLAIM) is Verdict.ACCEPT

    def test_silence_after_the_relaunch_is_a_failure(self):
        guard = ReminderGuard(OFFERED)
        guard.review(FALSE_CLAIM)

        assert guard.review("  ") is Verdict.GIVE_UP

    def test_silence_without_a_relaunch_is_left_alone(self):
        assert ReminderGuard(OFFERED).review("") is Verdict.ACCEPT

    def test_no_relaunch_without_room_for_it(self):
        assert ReminderGuard(OFFERED).review(FALSE_CLAIM, can_retry=False) is Verdict.GIVE_UP

    def test_the_refused_answer_is_sent_back_with_the_nudge(self):
        messages: list = []
        claim = [{"type": "text", "text": FALSE_CLAIM}]

        ReminderGuard(OFFERED).send_back(messages, claim)

        assert messages == [{"role": "assistant", "content": claim}, ReminderGuard.nudge()]

    def test_a_blank_answer_is_not_sent_back(self):
        # The API refuses an assistant message of blank text; the claim is in an earlier step.
        messages: list = []

        ReminderGuard(OFFERED).send_back(messages, [{"type": "text", "text": "  "}])

        assert messages == [ReminderGuard.nudge()]

    def test_the_nudge_is_a_round_of_the_loop_not_a_user_message(self):
        nudge = ReminderGuard.nudge()

        assert nudge["role"] == "user"
        assert nudge["content"] == [{"type": "text", "text": NUDGE}]


@pytest.fixture()
def fake_client() -> FakeAnthropicClient:
    return FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)


@pytest.fixture()
def proactions():
    """The proaction table: the real `schedule_proaction` handler runs, only the repository is replaced."""
    created = MagicMock()
    created.to_dict.return_value = {"id": "pro-1", "status": "pending", "scheduledAt": "2099-10-07T14:05:00+00:00"}
    with (
        patch("app.llm.tools.proaction_repo") as repo,
        patch("app.llm.time_tool.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
    ):
        repo.create = AsyncMock(return_value=created)
        yield repo


class TestTheToolLoop:
    """`run_tool_loop`, the non-streamed path: chat, proactions."""

    async def _run(self, client, question: str) -> tuple[dict, list]:
        messages = [{"role": "user", "content": question}]
        with patch("app.llm.runner.record_llm_usage"):
            result = await run_tool_loop(
                "Tu es Maggie.",
                messages,
                OFFERED,
                client=client,
                tool_router=ToolRouter(),
                user_id="user-1",
                model="fake",
            )
        return result, messages

    async def test_a_forgotten_reminder_is_scheduled_after_the_relaunch(self, fake_client, proactions):
        result, messages = await self._run(fake_client, WATER)

        assert [call["name"] for call in result["tool_calls"]] == ["date_time", SCHEDULE_TOOL]
        proactions.create.assert_awaited_once()
        kwargs = proactions.create.await_args.kwargs
        assert kwargs["user_id"] == "user-1"
        assert kwargs["scheduled_at"] == datetime(2099, 10, 7, 14, 5, tzinfo=UTC)
        assert result["response"] == SCHEDULED_ANSWER
        # The relaunch went out as a round of the loop, after the claim it corrects.
        assert messages[4] == ReminderGuard.nudge()
        assert messages[3]["role"] == "assistant"

    async def test_a_notification_promise_without_the_tool_is_relaunched(self, fake_client, proactions):
        result, _ = await self._run(fake_client, MOTHER)

        assert [call["name"] for call in result["tool_calls"]] == ["date_time", SCHEDULE_TOOL]
        proactions.create.assert_awaited_once()
        assert result["response"] == "C'est programmé : je t'enverrai une notification à 16h05 pour appeler ta mère."

    async def test_a_notification_promised_in_the_present_is_relaunched(self, fake_client, proactions):
        result, _ = await self._run(fake_client, LAUNDRY)

        assert [call["name"] for call in result["tool_calls"]] == ["date_time", SCHEDULE_TOOL]
        proactions.create.assert_awaited_once()
        assert result["response"] == "C'est programmé : je te notifie à 16h04 pour sortir le linge."

    async def test_two_forgotten_reminders_end_on_the_truth(self, fake_client, proactions):
        result, _ = await self._run(fake_client, PLANTS)

        assert result["response"] == NOT_SCHEDULED_MESSAGE
        assert "noté" not in result["response"]
        proactions.create.assert_not_awaited()

    async def test_a_reminder_scheduled_first_time_is_not_relaunched(self, proactions):
        client = MagicMock()
        tool_turn = MagicMock(stop_reason="tool_use", usage=None)
        call = MagicMock(type="tool_use", id="t1", input={"prompt": "Eau", "scheduled_at": "2099-10-07T16:05:00+02:00"})
        call.name = SCHEDULE_TOOL
        tool_turn.content = [call]
        answer = MagicMock(stop_reason="end_turn", usage=None)
        answer.content = [MagicMock(text="Je vous rappellerai de boire de l'eau à 16h05.")]
        client.messages.create = AsyncMock(side_effect=[tool_turn, answer])

        with patch("app.llm.runner.usage_kwargs", return_value={}):
            result, _ = await self._run(client, WATER)

        assert client.messages.create.await_count == 2
        assert result["response"] == "Je vous rappellerai de boire de l'eau à 16h05."


class TestTheConfirmedTime:
    async def test_the_result_carries_the_users_local_time(self, proactions):
        result = await ToolRouter().call_tool(
            SCHEDULE_TOOL,
            {"prompt": "Boire de l'eau", "scheduled_at": "2026-10-07T14:05:00+00:00"},
            user_id="user-1",
        )

        data = json.loads(result)
        assert data["id"] == "pro-1"
        assert data["scheduledAtLocal"] == "mercredi 7 octobre 2026, 16h05"
        assert data["timezone"] == "Europe/Paris"


def the_bubble(events: list[dict]) -> str:
    """What a client shows once the stream is over: a TEXT_MESSAGE_START empties the bubble."""
    text = ""
    for event in events:
        if event["type"] == "TEXT_MESSAGE_START":
            text = ""
        elif event["type"] == "TEXT_MESSAGE_CONTENT":
            text += event["delta"]
    return text


class TestTheStreamedPath:
    """`chat_stream`, what the web and the phone talk to."""

    async def _stream(self, client, question: str) -> tuple[list[dict], list[dict]]:
        saved: list[dict] = []
        router = ToolRouter()

        with (
            patch("app.llm.streaming.settings") as settings,
            patch("app.llm.streaming.message_repo") as message_repo,
            patch("app.llm.history.message_repo") as history_repo,
            patch("app.llm.history.context_repo") as history_contexts,
            patch("app.llm.contexts.message_repo") as routing_repo,
            patch("app.llm.contexts.context_repo") as context_repo,
            patch("app.llm.streaming.context_repo") as tool_log,
            patch("app.llm.streaming.record_llm_usage"),
        ):
            settings.anthropic_model = "fake"
            tool_log.append_tool_call = AsyncMock()
            persisted = MagicMock()
            persisted.id = "msg-1"
            persisted.role = "user"
            persisted.content = question
            persisted.context_id = "ctx-1"
            persisted.created_at = datetime(2026, 10, 7, 14, 4, tzinfo=UTC)
            history_repo.find_by_context = AsyncMock(return_value=[persisted])
            history_repo.find_recent = AsyncMock(return_value=[persisted])
            history_contexts.find_active = AsyncMock(return_value=[])
            message_repo.create = AsyncMock(side_effect=lambda **kwargs: saved.append(kwargs))
            routing_repo.update_context = AsyncMock()
            context_repo.find_active = AsyncMock(return_value=[])
            context_repo.append_tool_call = AsyncMock()
            created = MagicMock()
            created.id = "ctx-1"
            context_repo.create = AsyncMock(return_value=created)

            gateway = StreamingGateway()
            gateway.client = client
            gateway.personality = MagicMock()
            gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
            gateway.agent_memory = MagicMock()
            gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
            gateway.tool_router = MagicMock()
            gateway.tool_router.get_tool_definitions = AsyncMock(return_value=OFFERED)
            gateway.tool_router.call_tool = AsyncMock(side_effect=router.call_tool)

            events = [event async for event in gateway.chat_stream(question, "user-1", "msg-1")]
        return events, saved

    async def test_a_forgotten_reminder_is_scheduled_after_the_relaunch(self, fake_client, proactions):
        events, saved = await self._stream(fake_client, WATER)

        proactions.create.assert_awaited_once()
        called = [e["toolName"] for e in events if e["type"] == "TOOL_CALL_END"]
        assert called == ["date_time", SCHEDULE_TOOL]
        assert [message["content"] for message in saved] == [SCHEDULED_ANSWER]
        assert the_bubble(events) == SCHEDULED_ANSWER

    async def test_two_forgotten_reminders_end_on_the_truth(self, fake_client, proactions):
        events, saved = await self._stream(fake_client, PLANTS)

        proactions.create.assert_not_awaited()
        assert [message["content"] for message in saved] == [NOT_SCHEDULED_MESSAGE]
        # The claim may have streamed for a moment; what the bubble ends on is the truth.
        assert the_bubble(events) == NOT_SCHEDULED_MESSAGE
        types = [event["type"] for event in events]
        assert types.count("TEXT_MESSAGE_END") == 1
        assert types[-1] == "RUN_FINISHED"

    @staticmethod
    def _scripted(tmp_path, turns: list[dict]) -> FakeAnthropicClient:
        directory = tmp_path / "fake-llm"
        directory.mkdir()
        router = {"match": {"system_contains": "routeur de contexte"}, "turns": [{"text": '{"context_id": null}'}]}
        chat = {"match": {"user_contains": "boire de l'eau"}, "turns": turns}
        (directory / "10-router.yaml").write_text(yaml.safe_dump(router, allow_unicode=True))
        (directory / "20-chat.yaml").write_text(yaml.safe_dump(chat, allow_unicode=True))
        return FakeAnthropicClient(fixtures_dir=directory)

    async def test_a_reminder_scheduled_first_time_is_not_relaunched(self, tmp_path, proactions):
        client = self._scripted(
            tmp_path,
            [
                {"tools": [{"name": "date_time", "input": {"action": "add", "minutes": 1}}]},
                {"tools": [{"name": SCHEDULE_TOOL, "input": {"prompt": "Eau", "scheduled_at": "2099-10-07T16:05:00+02:00"}}]},
                {"text": SCHEDULED_ANSWER},
            ],
        )

        events, saved = await self._stream(client, WATER)

        proactions.create.assert_awaited_once()
        assert [message["content"] for message in saved] == [SCHEDULED_ANSWER]
        assert the_bubble(events) == SCHEDULED_ANSWER

    async def test_a_claim_made_before_a_tool_call_is_reviewed(self, tmp_path, proactions):
        # The last step says nothing, so the stored answer is the claim an earlier step made.
        client = self._scripted(
            tmp_path,
            [
                {"text": FALSE_CLAIM, "tools": [{"name": "date_time", "input": {"action": "add", "minutes": 1}}]},
                {"text": "  "},
                {"text": "  "},
            ],
        )

        events, saved = await self._stream(client, WATER)

        proactions.create.assert_not_awaited()
        assert [message["content"] for message in saved] == [NOT_SCHEDULED_MESSAGE]
        assert the_bubble(events) == NOT_SCHEDULED_MESSAGE

    async def test_a_claim_left_when_the_iterations_run_out_is_reviewed(self, tmp_path, proactions):
        lookup = {"tools": [{"name": "date_time", "input": {"action": "now"}}]}
        client = self._scripted(tmp_path, [{"text": FALSE_CLAIM, **lookup}, lookup, lookup, lookup, lookup])

        events, saved = await self._stream(client, WATER)

        assert [message["content"] for message in saved] == [NOT_SCHEDULED_MESSAGE]
        assert the_bubble(events) == NOT_SCHEDULED_MESSAGE
        assert [event["type"] for event in events].count("TEXT_MESSAGE_END") == 1

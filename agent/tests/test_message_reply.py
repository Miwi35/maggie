"""The owner answers one of Maggie's messages from the car (MAG-683)."""

import json
import time
from contextlib import contextmanager
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.container import container
from app.domain.approvals.errors import ApprovalNotPending
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.message_reply import (
    HEADER,
    ReplyTo,
    grant_approval_answer,
    handle_answer_approval,
    reply_block,
    with_reply,
)

REMINDER_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"


def a_reminder(**overrides) -> ReplyTo:
    fields = {
        "id": "notif-1",
        "type": "task_reminder",
        "title": "Appeler le garage",
        "text": "C'est pour aujourd'hui.",
        "reminder_id": REMINDER_ID,
        "buttons": ["done", "in10", "tonight"],
    }
    return ReplyTo(**{**fields, **overrides})


class TestReplyBlock:
    def test_a_reminder_names_its_id_and_only_the_buttons_the_phone_shows(self):
        block = reply_block(a_reminder())

        assert block.startswith(HEADER)
        assert "Appeler le garage — C'est pour aujourd'hui." in block
        assert f"reminderId : {REMINDER_ID}" in block
        assert "done = Fait" in block
        assert "tonight = Ce soir" in block
        assert "tomorrow" not in block.split("Ses boutons sur le téléphone :")[1].split(".")[0]
        assert "answer_task_reminder" in block

    def test_a_reminder_without_buttons_offers_all_of_them(self):
        block = reply_block(a_reminder(buttons=[]))

        assert "done = Fait" in block
        assert "tomorrow = Demain" in block

    def test_an_approval_names_its_id_and_the_two_decisions(self):
        block = reply_block(ReplyTo(id="n-2", title="Supprimer l'événement ?", approval_id="appr-1"))

        assert "approvalId : appr-1" in block
        assert "answer_approval" in block
        assert "approve" in block
        assert "deny" in block

    def test_a_plain_message_is_answered_as_an_instruction(self):
        block = reply_block(ReplyTo(id="n-3", text="Il pleut ce soir."))

        assert "Il pleut ce soir." in block
        assert "answer_task_reminder" not in block
        assert "answer_approval" not in block
        assert "consigne ordinaire" in block

    def test_a_very_long_message_is_cut(self):
        block = reply_block(ReplyTo(id="n-4", text="x" * 1500))

        assert "x" * 501 not in block

    def test_the_block_goes_in_front_of_the_screen(self):
        assert with_reply("[Contexte de l'écran]\nPage", a_reminder()).endswith("[Contexte de l'écran]\nPage")
        assert with_reply("écran", None) == "écran"
        assert with_reply(None, a_reminder()).startswith(HEADER)


@contextmanager
def deciding(approve=None, deny=None):
    """The approval service replaced by one whose answers the test sets; yields its `approve` and `deny`."""
    approve = approve or AsyncMock()
    deny = deny or AsyncMock()
    with container.override(approvals=MagicMock(approve=approve, deny=deny)):
        yield approve, deny


def approved(row: dict) -> MagicMock:
    return MagicMock(action=MagicMock(to_dict=MagicMock(return_value=row)))


def denied(row: dict) -> MagicMock:
    return MagicMock(to_dict=MagicMock(return_value=row))


async def owner_answers(approval_id: str = "appr-1", user_id: str = "user-1", answer: str = "approve") -> None:
    with patch("app.llm.message_reply.classify_approval_reply", new=AsyncMock(return_value=answer)):
        await grant_approval_answer(user_id, ReplyTo(id="n-1", approval_id=approval_id), "oui", None)


class TestAnswerApproval:
    async def test_the_model_cannot_decide_a_held_action_the_owner_did_not_answer(self):
        with deciding() as (approve, _):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-9", "decision": "approve"}, "user-1"))

        assert "error" in result
        approve.assert_not_awaited()

    async def test_the_owners_answer_decides_that_approval_once(self):
        await owner_answers("appr-2")
        with deciding(approve=AsyncMock(return_value=approved({"id": "appr-2"}))) as (approve, _):
            first = json.loads(await handle_answer_approval({"approval_id": "appr-2", "decision": "approve"}, "user-1"))
            second = json.loads(await handle_answer_approval({"approval_id": "appr-2", "decision": "approve"}, "user-1"))

        assert first["success"] is True
        assert "error" in second
        assert approve.await_count == 1

    async def test_the_answer_to_one_approval_does_not_decide_another_or_another_users(self):
        await owner_answers("appr-3")
        with deciding() as (approve, _):
            other_approval = json.loads(await handle_answer_approval({"approval_id": "appr-4", "decision": "approve"}, "user-1"))
            other_user = json.loads(await handle_answer_approval({"approval_id": "appr-3", "decision": "approve"}, "user-2"))

        assert "error" in other_approval and "error" in other_user
        approve.assert_not_awaited()

    async def test_a_yes_with_a_condition_decides_nothing(self):
        await owner_answers("appr-6", answer="other")
        with deciding() as (approve, deny):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-6", "decision": "approve"}, "user-1"))

        assert result == {"error": "Sa réponse n'est pas un oui ou un non net : rien n'est décidé."}
        approve.assert_not_awaited()
        deny.assert_not_awaited()

    async def test_the_model_cannot_turn_a_no_into_a_yes(self):
        await owner_answers("appr-7", answer="deny")
        with deciding() as (approve, _):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-7", "decision": "approve"}, "user-1"))

        assert "error" in result
        approve.assert_not_awaited()

    async def test_the_model_cannot_turn_a_yes_into_a_no(self):
        await owner_answers("appr-8", answer="approve")
        with deciding() as (_, deny):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-8", "decision": "deny"}, "user-1"))

        assert "error" in result
        deny.assert_not_awaited()

    async def test_a_reply_to_a_reminder_calls_no_classifier(self):
        with patch("app.llm.message_reply.classify_approval_reply", new=AsyncMock()) as classify:
            await grant_approval_answer("user-1", a_reminder(), "c'est fait", None)
            await grant_approval_answer("user-1", None, "bonjour", None)

        classify.assert_not_awaited()

    async def test_a_grant_expires(self):
        await owner_answers("appr-5")
        with patch("app.llm.message_reply.time.monotonic", return_value=time.monotonic() + 601), deciding() as (approve, _):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-5", "decision": "approve"}, "user-1"))

        assert "error" in result
        approve.assert_not_awaited()

    async def test_approve_runs_the_held_call_as_the_button_does(self):
        await owner_answers()
        with deciding(approve=AsyncMock(return_value=approved({"id": "appr-1", "status": "approved"}))) as (approve, _):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-1", "decision": "approve"}, "user-1"))

        assert result == {"success": True, "approval": {"id": "appr-1", "status": "approved"}}
        assert approve.await_args.args[0] == "appr-1"
        assert approve.await_args.kwargs["channel"] == "notification"
        assert approve.await_args.kwargs["user_id"] == "user-1"

    async def test_deny_refuses_it(self):
        await owner_answers(answer="deny")
        with deciding(deny=AsyncMock(return_value=denied({"id": "appr-1", "status": "denied"}))) as (_, deny):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-1", "decision": "deny"}, "user-1"))

        assert result["success"] is True
        assert deny.await_args.kwargs["channel"] == "notification"

    async def test_an_approval_already_decided_is_reported_not_raised(self):
        await owner_answers()
        gone = AsyncMock(side_effect=ApprovalNotPending())
        with deciding(approve=gone):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-1", "decision": "approve"}, "user-1"))

        assert result == {"error": "Approval is not pending"}

    @pytest.mark.parametrize(
        "arguments",
        [{}, {"approval_id": "appr-1"}, {"approval_id": "appr-1", "decision": "maybe"}, {"decision": "approve"}],
    )
    async def test_an_incomplete_call_decides_nothing(self, arguments):
        with deciding() as (approve, deny):
            result = json.loads(await handle_answer_approval(arguments, "user-1"))

        assert "error" in result
        approve.assert_not_awaited()
        deny.assert_not_awaited()


class TestFakeModelReadsTheReply:
    async def ask(self, message: str, reply: ReplyTo):
        client = FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)
        return await client.messages.create(
            model="fake",
            max_tokens=1024,
            system="Tu es Maggie.",
            messages=[{"role": "user", "content": f"{reply_block(reply)}\n\n{message}"}],
            tools=[{"name": "answer_task_reminder"}, {"name": "answer_approval"}],
        )

    async def test_c_est_fait_on_a_reminder_presses_done(self):
        answer = await self.ask("c'est fait", a_reminder())

        assert answer.content[0].name == "answer_task_reminder"
        assert answer.content[0].input == {"reminderId": REMINDER_ID, "action": "done"}

    async def test_a_dictated_apostrophe_is_read_the_same(self):
        answer = await self.ask("c’est fait", a_reminder())

        assert answer.content[0].name == "answer_task_reminder"

    async def test_yes_on_an_approval_authorizes_it(self):
        answer = await self.ask("oui, vas-y", ReplyTo(id="n-2", title="Supprimer ?", approval_id="a1b2c3d4e5f60718"))

        assert answer.content[0].name == "answer_approval"
        assert answer.content[0].input == {"approval_id": "a1b2c3d4e5f60718", "decision": "approve"}

    async def test_oui_mais_on_an_approval_is_scripted_as_a_mistaken_approve(self):
        answer = await self.ask("oui mais à 15 h", ReplyTo(id="n-3", title="Supprimer ?", approval_id="a1b2c3d4e5f60718"))

        assert answer.content[0].name == "answer_approval"
        assert answer.content[0].input["decision"] == "approve"

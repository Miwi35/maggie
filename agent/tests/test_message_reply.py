"""The owner answers one of Maggie's messages from the car (MAG-683)."""

import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import HTTPException

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.message_reply import HEADER, ReplyTo, handle_answer_approval, reply_block, with_reply

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


class TestAnswerApproval:
    async def test_approve_runs_the_held_call_as_the_button_does(self):
        with patch("app.api.routes.approve_action", new=AsyncMock(return_value={"id": "appr-1", "status": "approved"})) as approve:
            result = json.loads(await handle_answer_approval({"approval_id": "appr-1", "decision": "approve"}, "user-1"))

        assert result == {"success": True, "approval": {"id": "appr-1", "status": "approved"}}
        assert approve.await_args.args[0] == "appr-1"
        assert approve.await_args.kwargs["channel"] == "notification"
        assert approve.await_args.kwargs["user_id"] == "user-1"

    async def test_deny_refuses_it(self):
        with patch("app.api.routes.deny_action", new=AsyncMock(return_value={"id": "appr-1", "status": "denied"})) as deny:
            result = json.loads(await handle_answer_approval({"approval_id": "appr-1", "decision": "deny"}, "user-1"))

        assert result["success"] is True
        assert deny.await_args.kwargs["channel"] == "notification"

    async def test_an_approval_already_decided_is_reported_not_raised(self):
        gone = AsyncMock(side_effect=HTTPException(status_code=409, detail="Approval is not pending"))
        with patch("app.api.routes.approve_action", new=gone):
            result = json.loads(await handle_answer_approval({"approval_id": "appr-1", "decision": "approve"}, "user-1"))

        assert result == {"error": "Approval is not pending"}

    @pytest.mark.parametrize(
        "arguments",
        [{}, {"approval_id": "appr-1"}, {"approval_id": "appr-1", "decision": "maybe"}, {"decision": "approve"}],
    )
    async def test_an_incomplete_call_decides_nothing(self, arguments):
        with patch("app.api.routes.approve_action", new=AsyncMock()) as approve, patch(
            "app.api.routes.deny_action", new=AsyncMock()
        ) as deny:
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

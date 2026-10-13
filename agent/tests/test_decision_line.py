"""The line that keeps the user's answer to Maggie's request in the thread (MAG-336)."""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest

from app.db.message_repository import message_repo
from app.db.models import DECISION_ROLE
from app.db.pending_action_model import PendingAction, PendingActionStatus
from app.llm.history import build_history
from app.policy.decision import decision_line, record_decision

SUMMARY = "Supprimer la série « Psychomot Ael »"


def action(status: PendingActionStatus, **overrides) -> PendingAction:
    fields = {
        "id": "act-1",
        "user_id": "user-1",
        "tool_name": "delete_event",
        "arguments": {"eventId": "evt-1"},
        "source": "chat",
        "context_id": "ctx-1",
        "status": status,
        "summary": SUMMARY,
        "created_at": datetime.now(UTC),
        "expires_at": datetime.now(UTC) + timedelta(hours=23),
    }
    fields.update(overrides)
    return PendingAction(**fields)


class TestDecisionLine:
    def test_an_approval_reads_autoriser_and_lowers_the_summary_after_the_dash(self):
        line = decision_line(PendingActionStatus.APPROVED, SUMMARY)

        assert line == "Vous avez répondu : Autoriser — supprimer la série « Psychomot Ael »"

    def test_a_failed_run_is_still_an_approval(self):
        assert decision_line(PendingActionStatus.FAILED, SUMMARY).startswith("Vous avez répondu : Autoriser — ")

    def test_a_refusal_reads_refuser(self):
        line = decision_line(PendingActionStatus.DENIED, SUMMARY)

        assert line == "Vous avez répondu : Refuser — supprimer la série « Psychomot Ael »"

    def test_an_expiry_says_nobody_answered(self):
        line = decision_line(PendingActionStatus.EXPIRED, SUMMARY)

        assert line == "Sans réponse : la demande a expiré — supprimer la série « Psychomot Ael »"

    def test_a_pending_action_has_no_line(self):
        with pytest.raises(ValueError):
            decision_line(PendingActionStatus.PENDING, SUMMARY)


class TestRecordDecision:
    @pytest.mark.asyncio
    async def test_the_line_is_stored_in_the_thread_and_published(self, chat_db):
        stored = await record_decision(action(PendingActionStatus.APPROVED), "voice")

        assert stored is not None
        assert stored.role == DECISION_ROLE
        assert stored.user_id == "user-1"
        assert stored.context_id == "ctx-1"
        assert stored.content == "Vous avez répondu : Autoriser — supprimer la série « Psychomot Ael »"
        assert stored.decision == {
            "answer": "approved",
            "summary": SUMMARY,
            "channel": "voice",
            "actionId": "act-1",
        }
        in_db = await message_repo.get(stored.id)
        assert in_db is not None and in_db.content == stored.content
        chat_db.chat_published.assert_awaited_once()
        payload = chat_db.chat_published.await_args.args[1]
        assert payload["role"] == "decision"
        assert payload["decision"]["channel"] == "voice"

    @pytest.mark.asyncio
    async def test_an_expiry_has_no_channel(self, chat_db):
        stored = await record_decision(action(PendingActionStatus.EXPIRED), None)

        assert stored is not None
        assert stored.decision["answer"] == "expired"
        assert stored.decision["channel"] is None

    @pytest.mark.asyncio
    async def test_the_summary_falls_back_to_the_action_label(self, chat_db):
        stored = await record_decision(action(PendingActionStatus.DENIED, summary=None), "chat")

        assert stored is not None
        assert stored.content == "Vous avez répondu : Refuser — supprimer l'événement"

    @pytest.mark.asyncio
    async def test_a_failing_store_never_raises(self):
        with patch("app.policy.decision.message_repo.create", AsyncMock(side_effect=RuntimeError("db down"))):
            assert await record_decision(action(PendingActionStatus.APPROVED), "chat") is None


class TestDecisionInTheHistory:
    @pytest.mark.asyncio
    async def test_the_next_turn_reads_the_answer_as_the_users(self, chat_db):
        await message_repo.create(user_id="user-1", role="user", content="Supprime la série Psychomot", context_id="ctx-1")
        await message_repo.create(user_id="user-1", role="assistant", content="Je te demande confirmation.", context_id="ctx-1")
        await record_decision(action(PendingActionStatus.APPROVED), "notification")

        turns = await build_history("user-1", context_id="ctx-1", pending_message="Merci")

        assert [t["role"] for t in turns] == ["user", "assistant", "user"]
        note = turns[2]["content"]
        assert "Autoriser" in note
        assert "supprimer la série « Psychomot Ael »" in note
        assert "notification" in note
        assert note.endswith("Merci")

    @pytest.mark.asyncio
    async def test_a_refusal_and_an_expiry_are_told_to_the_model_too(self, chat_db):
        await message_repo.create(user_id="user-1", role="user", content="Supprime-la", context_id="ctx-1")
        await message_repo.create(user_id="user-1", role="assistant", content="Tu confirmes ?", context_id="ctx-1")
        await record_decision(action(PendingActionStatus.DENIED), "chat")
        await message_repo.create(user_id="user-1", role="assistant", content="D'accord, je n'y touche pas.", context_id="ctx-1")
        await record_decision(action(PendingActionStatus.EXPIRED, id="act-2"), None)

        turns = await build_history("user-1", context_id="ctx-1", pending_message="Alors ?")

        text = "\n".join(t["content"] for t in turns if isinstance(t["content"], str))
        assert "Refuser" in text
        assert "expiré" in text

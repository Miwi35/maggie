"""What storing a message sets off — Mercure and the conversation's memory — lives in the service (MAG-1050)."""

from unittest.mock import AsyncMock, patch

from app.db.message_repository import message_repo
from app.domain.conversation.service import message_service


class TestCreate:
    async def test_create_publishes_on_the_chat_topic_and_records_the_conversation(self, chat_db):
        record = AsyncMock()
        with patch("app.memory.conversations.record", new=record):
            msg = await message_service.create(user_id="user-1", role="assistant", content="Oui.")

        chat_db.chat_published.assert_awaited_once()
        topic, payload = chat_db.chat_published.await_args.args
        assert topic == "/chat/user-1"
        assert payload["id"] == msg.id
        record.assert_awaited_once_with(msg)

    async def test_create_without_announce_publishes_nothing_until_announce(self, chat_db):
        calls: list[str] = []
        chat_db.chat_published.side_effect = lambda *a: calls.append("mercure")
        record = AsyncMock(side_effect=lambda *a: calls.append("record"))
        with patch("app.memory.conversations.record", new=record):
            msg = await message_service.create(user_id="user-1", role="assistant", content="Oui.", announce=False)

            assert (await message_repo.get(msg.id)) is not None
            chat_db.chat_published.assert_not_awaited()
            record.assert_not_awaited()

            await message_service.announce(msg)

        assert calls == ["mercure", "record"]

    async def test_the_published_payload_does_not_carry_the_blocks(self, chat_db):
        blocks = [{"role": "assistant", "content": [{"type": "tool_use", "id": "t1", "name": "x", "input": {}}]}]
        with patch("app.memory.conversations.record", new=AsyncMock()):
            await message_service.create(user_id="user-1", role="assistant", content="La voilà.", blocks=blocks)

        _topic, payload = chat_db.chat_published.await_args.args
        assert "blocks" not in payload

    async def test_the_repository_alone_publishes_nothing(self, chat_db):
        record = AsyncMock()
        with patch("app.memory.conversations.record", new=record):
            await message_repo.create(user_id="user-1", role="assistant", content="Oui.")

        chat_db.chat_published.assert_not_awaited()
        record.assert_not_awaited()


class TestUpdateContext:
    async def test_update_context_records_the_thread_of_an_existing_message(self, chat_db):
        msg = await message_repo.create(user_id="user-1", role="user", content="Salut")
        record_thread = AsyncMock()
        with patch("app.memory.conversations.record_thread", new=record_thread):
            await message_service.update_context(msg.id, "ctx-1")

        record_thread.assert_awaited_once_with("user-1", "ctx-1")
        assert (await message_repo.get(msg.id)).context_id == "ctx-1"

    async def test_update_context_of_an_unknown_message_records_nothing(self, chat_db):
        record_thread = AsyncMock()
        with patch("app.memory.conversations.record_thread", new=record_thread):
            await message_service.update_context("unknown", "ctx-1")

        record_thread.assert_not_awaited()


class TestDeletion:
    async def test_a_deletion_is_published_and_a_miss_is_not(self, chat_db):
        msg = await message_repo.create(user_id="user-1", role="user", content="Salut")

        assert await message_service.delete_for_user(msg.id, "someone-else") is False
        chat_db.chat_published.assert_not_awaited()

        assert await message_service.delete_for_user(msg.id, "user-1") is True
        topic, payload = chat_db.chat_published.await_args.args
        assert topic == "/chat/user-1"
        assert payload == {"deleted": True, "contextId": None, "messageIds": [msg.id]}

    async def test_deleting_nothing_publishes_nothing(self, chat_db):
        assert await message_service.delete_many_for_user(["unknown"], "user-1") == []
        chat_db.chat_published.assert_not_awaited()

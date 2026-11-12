"""The digest message: its block, its opening, and what the model reads of it (MAG-808)."""

from datetime import date
from zoneinfo import ZoneInfo

import pytest

from app.db.message_repository import message_repo
from app.digest import DIGEST_AVAILABLE, digest_block
from app.llm.history import build_history

OWNER = "test-user"
TEXT = "Bonjour monsieur. Il fera beau aujourd'hui."


async def _digest(user: str = OWNER, blocks: list[dict] | None = None):
    block = digest_block(TEXT, date(2030, 11, 14), ZoneInfo("Europe/Paris"))
    return await message_repo.create(
        user_id=user,
        role="assistant",
        content=DIGEST_AVAILABLE,
        screen="digest",
        ui_blocks=[block] if blocks is None else blocks,
    )


class TestTheBlock:
    def test_the_digest_block_expires_at_two_pm_local_and_is_not_opened(self):
        block = digest_block(TEXT, date(2030, 11, 14), ZoneInfo("Europe/Paris"))

        assert block == {
            "type": "digest",
            "day": "2030-11-14",
            "text": TEXT,
            "expiresAt": "2030-11-14T14:00:00+01:00",
            "openedAt": None,
        }


class TestOpening:
    async def test_opening_the_digest_sets_opened_at_once_and_publishes(self, chat_db, authed_client):
        message = await _digest()

        first = authed_client.post(f"/messages/{message.id}/digest/opened")
        published = chat_db.chat_published.await_count
        stored = await message_repo.get(message.id)
        opened_at = stored.ui_blocks[0]["openedAt"]
        second = authed_client.post(f"/messages/{message.id}/digest/opened")
        again = await message_repo.get(message.id)

        assert (first.status_code, first.json()) == (200, {"status": "ok"})
        assert opened_at is not None
        assert (second.status_code, second.json()) == (200, {"status": "already"})
        assert again.ui_blocks[0]["openedAt"] == opened_at
        assert chat_db.chat_published.await_count == published
        assert chat_db.chat_published.await_args.args[1]["blocks"][0]["openedAt"] == opened_at

    async def test_opening_the_digest_of_another_user_is_404(self, chat_db, authed_client):
        message = await _digest(user="someone-else")

        assert authed_client.post(f"/messages/{message.id}/digest/opened").status_code == 404
        assert (await message_repo.get(message.id)).ui_blocks[0]["openedAt"] is None

    async def test_opening_a_message_without_digest_block_is_404(self, chat_db, authed_client):
        message = await _digest(blocks=[{"type": "question", "id": "Q"}])

        assert authed_client.post(f"/messages/{message.id}/digest/opened").status_code == 404
        assert authed_client.post("/messages/missing/digest/opened").status_code == 404

    def test_opening_the_digest_without_a_token_is_401(self, client):
        assert client.post("/messages/abc/digest/opened").status_code in (401, 403)


class TestTheModelReadsTheDigest:
    async def test_a_digest_message_reads_as_its_digest_text_for_the_model(self, chat_db):
        await message_repo.create(user_id=OWNER, role="user", content="Bonjour")
        await _digest()
        await message_repo.create(user_id=OWNER, role="user", content="Merci")

        turns = await build_history(OWNER)

        assert [t["content"] for t in turns] == ["Bonjour", TEXT, "Merci"]

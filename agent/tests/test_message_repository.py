"""The per-context reads a summary is written from (MAG-11) and a history is built from (MAG-13)."""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

from app.db.message_repository import message_repo
from app.db.models import Message


async def _write(
    session_factory, context_id: str | None, role: str, content: str, minutes_ago: int, user_id: str = "user-1"
) -> None:
    """A message at a known instant — `created_at` is what `since` filters on."""
    async with session_factory() as session:
        session.add(
            Message(
                user_id=user_id,
                role=role,
                content=content,
                context_id=context_id,
                created_at=datetime.now(UTC) - timedelta(minutes=minutes_ago),
            )
        )
        await session.commit()


class TestFindByContext:
    async def test_returns_one_thread_oldest_first(self, chat_db):
        await _write(chat_db.session, "ctx-1", "user", "Il me faut de la farine", 30)
        await _write(chat_db.session, "ctx-1", "assistant", "C'est noté", 20)
        await _write(chat_db.session, "ctx-2", "user", "Et mon budget ?", 25)

        messages = await message_repo.find_by_context("ctx-1")

        assert [m.content for m in messages] == ["Il me faut de la farine", "C'est noté"]

    async def test_since_keeps_only_what_the_summary_does_not_cover(self, chat_db):
        await _write(chat_db.session, "ctx-1", "user", "Déjà résumé", 30)
        await _write(chat_db.session, "ctx-1", "user", "Nouveau", 5)

        messages = await message_repo.find_by_context("ctx-1", since=datetime.now(UTC) - timedelta(minutes=10))

        assert [m.content for m in messages] == ["Nouveau"]

    async def test_the_limit_keeps_the_newest(self, chat_db):
        for minutes in (30, 20, 10):
            await _write(chat_db.session, "ctx-1", "user", f"il y a {minutes}", minutes)

        messages = await message_repo.find_by_context("ctx-1", limit=2)

        assert [m.content for m in messages] == ["il y a 20", "il y a 10"]

    async def test_a_thread_with_no_message_is_empty(self, chat_db):
        assert await message_repo.find_by_context("ctx-unknown") == []

    async def test_the_owner_gets_the_thread(self, chat_db):
        """`user_id` is the belt to the router's ownership check (MAG-203), for a history (MAG-13)."""
        await _write(chat_db.session, "ctx-1", "user", "Il me faut de la farine", 30)

        messages = await message_repo.find_by_context("ctx-1", user_id="user-1")

        assert [m.content for m in messages] == ["Il me faut de la farine"]

    async def test_another_user_gets_nothing_from_it(self, chat_db):
        await _write(chat_db.session, "ctx-1", "user", "Mon salaire est de 3000", 30)

        assert await message_repo.find_by_context("ctx-1", user_id="user-2") == []


class TestTheToolBlocksOfATurn:
    """The rounds a turn stores, and what a client is shown of them (MAG-211)."""

    @staticmethod
    def _blocks() -> list[dict]:
        return [
            {
                "role": "assistant",
                "content": [{"type": "tool_use", "id": "toolu_1", "name": "get_grocery_list", "input": {}}],
            },
            {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": "{}"}]},
        ]

    async def test_they_are_stored_and_read_back(self, chat_db):
        blocks = self._blocks()
        await message_repo.create(user_id="user-1", role="assistant", content="La voilà.", blocks=blocks)

        [stored] = await message_repo.find_recent("user-1")

        assert stored.blocks == blocks

    async def test_a_turn_that_called_nothing_has_none(self, chat_db):
        """`None` says « no round », which is not the same thing as a round that is empty."""
        await message_repo.create(user_id="user-1", role="assistant", content="Bonjour.", blocks=[])

        [stored] = await message_repo.find_recent("user-1")

        assert stored.blocks is None

    async def test_a_message_written_without_them_still_works(self, chat_db):
        """Every row of an existing database, and every user message."""
        await message_repo.create(user_id="user-1", role="user", content="Ma liste ?")

        [stored] = await message_repo.find_recent("user-1")

        assert stored.blocks is None

    async def test_what_a_client_is_shown_does_not_change(self, chat_db):
        """`GET /agent/messages`, the Mind panel and the phone read a message as its text."""
        message = await message_repo.create(
            user_id="user-1", role="assistant", content="La voilà.", blocks=self._blocks()
        )

        assert set(message.to_dict()) == {"id", "role", "content", "contextId", "createdAt"}

    async def test_the_published_payload_does_not_change_either(self, chat_db):
        """A phone parsing the Mercure echo must not meet a field it has never seen."""
        publish = AsyncMock()
        with patch("app.db.message_repository.message_repo.publisher.publish", new=publish):
            await message_repo.create(
                user_id="user-1", role="assistant", content="La voilà.", blocks=self._blocks()
            )

        _topic, payload = publish.await_args.args
        assert "blocks" not in payload


class TestCountByContext:
    async def test_counts_one_thread_only(self, chat_db):
        await _write(chat_db.session, "ctx-1", "user", "un", 30)
        await _write(chat_db.session, "ctx-1", "assistant", "deux", 20)
        await _write(chat_db.session, "ctx-2", "user", "ailleurs", 20)
        await _write(chat_db.session, None, "user", "sans contexte", 20)

        assert await message_repo.count_by_context("ctx-1") == 2

    async def test_counts_from_the_last_summary(self, chat_db):
        await _write(chat_db.session, "ctx-1", "user", "avant", 30)
        await _write(chat_db.session, "ctx-1", "user", "après", 5)

        assert await message_repo.count_by_context("ctx-1", since=datetime.now(UTC) - timedelta(minutes=10)) == 1

    async def test_an_unknown_thread_counts_zero(self, chat_db):
        assert await message_repo.count_by_context("ctx-unknown") == 0

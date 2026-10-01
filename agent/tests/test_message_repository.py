"""The per-context reads a summary is written from (MAG-11) and a history is built from (MAG-13)."""

from datetime import UTC, datetime, timedelta

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

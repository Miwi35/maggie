"""What the model is sent of the conversation, built around the routed thread (MAG-13).

The behaviour under test is observable from the returned list alone, so these read it
rather than asserting that a repository was called. `chat_db` gives the two repositories a
real database, which is what makes the dedup and the ordering assertions mean anything:
with mocks they would only be testing the mocks' return order.
"""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest

from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.llm.history import build_history

OWNER = "user-1"
NEIGHBOUR = "user-2"

BASE = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)


@pytest.fixture()
def say(chat_db):
    """Store a message at `BASE + minutes`, through the repository the code under test uses."""

    async def _store(role: str, content: str, *, context: str | None = None, minutes: int = 0, user: str = OWNER):
        message = await message_repo.create(user_id=user, role=role, content=content, context_id=context)
        # `created_at` defaults to "now", and every message of a test would then share a
        # timestamp at SQLite's resolution — which is exactly the tie the ordering has to
        # survive. Set explicitly, through the same session factory the repository uses.
        async with chat_db.session() as session:
            from sqlalchemy import select

            from app.db.models import Message

            stored = (await session.execute(select(Message).where(Message.id == message.id))).scalar_one()
            stored.created_at = BASE + timedelta(minutes=minutes)
            await session.commit()
        return message

    return _store


@pytest.fixture()
def thread(chat_db):
    """Open a context, so a message can be routed into a thread that really exists."""

    async def _open(label: str, *, user: str = OWNER):
        return await context_repo.create(user, label)

    return _open


class TestTheThreadIsTheConversation:
    async def test_the_threads_messages_are_sent(self, say, thread):
        courses = await thread("Courses")
        await say("user", "Il me faut de la farine", context=courses.id, minutes=1)
        await say("assistant", "C'est noté.", context=courses.id, minutes=2)

        turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [
            {"role": "user", "content": "Il me faut de la farine"},
            {"role": "assistant", "content": "C'est noté."},
        ]

    async def test_a_message_older_than_the_global_window_is_still_sent(self, say, thread):
        """The point of the ticket: the thread is not whatever happened recently."""
        budget = await thread("Budget")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)

        other = await thread("Courses")
        for minute in range(3, 15):
            await say("user", f"message {minute}", context=other.id, minutes=minute)

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 2
            turns = await build_history(OWNER, context_id=budget.id)

        assert "Où en est mon budget ?" in turns[0]["content"]

    async def test_the_global_window_completes_the_thread(self, say, thread):
        """« Et ça aussi » said a minute ago in a neighbouring thread is still there."""
        budget = await thread("Budget")
        courses = await thread("Courses")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        await say("user", "Ajoute du beurre", context=courses.id, minutes=3)

        turns = await build_history(OWNER, context_id=budget.id)

        assert len(turns) == 3
        assert "Ajoute du beurre" in turns[2]["content"]

    async def test_a_message_from_another_thread_says_so(self, say, thread):
        budget = await thread("Budget")
        courses = await thread("Courses")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        await say("user", "Ajoute du beurre", context=courses.id, minutes=3)

        turns = await build_history(OWNER, context_id=budget.id)

        # Labelled with the thread it was said in, so the model does not read it as the
        # next line of this conversation — which is the confusion this ticket removes.
        assert turns[2]["content"] == "[fil « Courses »] Ajoute du beurre"
        # And the current thread's own messages are untouched.
        assert turns[0]["content"] == "Où en est mon budget ?"

    async def test_a_message_from_a_thread_that_is_gone_is_still_marked(self, say, thread):
        budget = await thread("Budget")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        await say("user", "Et le reste ?", context="ctx-vanished", minutes=3)

        turns = await build_history(OWNER, context_id=budget.id)

        assert turns[2]["content"] == "[autre fil] Et le reste ?"

    async def test_an_untagged_message_is_marked_too(self, say, thread):
        """A message nobody could route is not part of the thread, and must not look like it."""
        budget = await thread("Budget")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        await say("user", "Un message jamais routé", context=None, minutes=3)

        turns = await build_history(OWNER, context_id=budget.id)

        assert turns[2]["content"] == "[autre fil] Un message jamais routé"

    async def test_the_message_being_answered_is_never_marked(self, say, thread):
        """`route_message` swallows a failed tag, which would make Maggie read the question as a neighbour's."""
        budget = await thread("Budget")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        # Stored, then the `update_context` that would have tagged it failed.
        answering = await say("user", "Et le mois prochain ?", context=None, minutes=3)

        turns = await build_history(OWNER, context_id=budget.id, current_message_id=answering.id)

        assert turns[2]["content"] == "Et le mois prochain ?"

    async def test_the_thread_window_caps_what_is_loaded(self, say, thread):
        """`context_history_messages` is the ceiling, and it keeps the newest."""
        courses = await thread("Courses")
        # Odd minutes are the user's, so a window of two opens on a user turn and the
        # leading-assistant trim does not eat one of the messages under test.
        for minute in range(1, 7):
            await say("user" if minute % 2 else "assistant", f"message {minute}", context=courses.id, minutes=minute)

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 2
            settings.recent_history_messages = 1
            turns = await build_history(OWNER, context_id=courses.id)

        joined = " ".join(turn["content"] for turn in turns)
        assert "message 5" in joined
        assert "message 6" in joined
        assert "message 1" not in joined
        assert "message 4" not in joined

    async def test_an_answer_never_sorts_before_its_question(self, say, thread):
        """Two messages of one exchange can share a timestamp at the database's resolution."""
        courses = await thread("Courses")
        await say("assistant", "C'est noté.", context=courses.id, minutes=1)
        await say("user", "De la farine", context=courses.id, minutes=1)

        turns = await build_history(OWNER, context_id=courses.id)

        assert [turn["role"] for turn in turns] == ["user", "assistant"]
        assert turns[0]["content"] == "De la farine"

    async def test_with_no_thread_nothing_is_marked(self, say, thread):
        """No model to route with, or a routing call that failed: the window is all there is."""
        courses = await thread("Courses")
        await say("user", "Il me faut de la farine", context=courses.id, minutes=1)

        turns = await build_history(OWNER, context_id=None)

        assert turns == [{"role": "user", "content": "Il me faut de la farine"}]

    async def test_another_users_thread_is_never_loaded(self, say, thread):
        """The id comes from a model's answer (MAG-203); the query checks the owner anyway."""
        neighbour_thread = await thread("Budget du voisin", user=NEIGHBOUR)
        await say("user", "Mon salaire est de 3000", context=neighbour_thread.id, minutes=1, user=NEIGHBOUR)
        await say("user", "Bonjour", context=None, minutes=2)

        turns = await build_history(OWNER, context_id=neighbour_thread.id)

        assert all("3000" not in turn["content"] for turn in turns)


class TestTheShapeTheApiAccepts:
    async def test_a_message_in_both_windows_is_sent_once(self, say, thread):
        courses = await thread("Courses")
        await say("user", "Il me faut de la farine", context=courses.id, minutes=1)

        turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [{"role": "user", "content": "Il me faut de la farine"}]

    async def test_consecutive_turns_of_the_same_role_are_merged(self, say, thread):
        """The API refuses two user turns in a row, and a thread does get them."""
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)
        await say("user", "Et du beurre", context=courses.id, minutes=2)
        await say("assistant", "C'est noté.", context=courses.id, minutes=3)

        turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [
            {"role": "user", "content": "De la farine\nEt du beurre"},
            {"role": "assistant", "content": "C'est noté."},
        ]

    async def test_a_history_opening_on_maggie_is_trimmed(self, say, thread):
        """A proaction, or a window cutting mid-exchange, would otherwise be a 400 every time."""
        courses = await thread("Courses")
        await say("assistant", "Pense à sortir les poubelles.", context=courses.id, minutes=1)
        await say("user", "Merci", context=courses.id, minutes=2)

        turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [{"role": "user", "content": "Merci"}]

    async def test_order_is_chronological_across_both_windows(self, say, thread):
        budget = await thread("Budget")
        courses = await thread("Courses")
        await say("user", "premier", context=budget.id, minutes=1)
        await say("assistant", "deuxième", context=courses.id, minutes=2)
        await say("user", "troisième", context=budget.id, minutes=3)

        turns = await build_history(OWNER, context_id=budget.id)

        assert [turn["role"] for turn in turns] == ["user", "assistant", "user"]
        assert "premier" in turns[0]["content"]
        assert "deuxième" in turns[1]["content"]
        assert "troisième" in turns[2]["content"]

    async def test_an_empty_message_is_dropped(self, say, thread):
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)
        await say("assistant", "", context=courses.id, minutes=2)

        turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [{"role": "user", "content": "De la farine"}]

    async def test_a_role_the_api_does_not_know_is_dropped(self, say, thread):
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)
        await say("system", "note interne", context=courses.id, minutes=2)

        turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [{"role": "user", "content": "De la farine"}]


class TestThePendingMessage:
    async def test_a_caller_that_stored_nothing_hands_its_message_over(self, say, thread):
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)
        await say("assistant", "C'est noté.", context=courses.id, minutes=2)

        turns = await build_history(OWNER, context_id=courses.id, pending_message="Et du beurre")

        assert turns[-1] == {"role": "user", "content": "Et du beurre"}

    async def test_it_is_merged_into_a_trailing_user_turn(self, say, thread):
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)

        turns = await build_history(OWNER, context_id=courses.id, pending_message="Et du beurre")

        assert turns == [{"role": "user", "content": "De la farine\nEt du beurre"}]

    async def test_a_caller_that_stored_it_does_not_send_it_twice(self, say, thread):
        """`LLMGateway.chat` used to append the message the route had just persisted."""
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)

        turns = await build_history(OWNER, context_id=courses.id, pending_message=None)

        assert turns == [{"role": "user", "content": "De la farine"}]


class TestWhenTheDatabaseWillNotAnswer:
    async def test_the_turn_still_has_the_users_message(self):
        """A history that would not load costs the model the conversation, not the user the answer."""
        with patch("app.llm.history.message_repo") as repo:
            repo.find_by_context = AsyncMock(side_effect=RuntimeError("no database"))
            repo.find_recent = AsyncMock(side_effect=RuntimeError("no database"))

            turns = await build_history(OWNER, context_id="ctx-1", pending_message="Et du beurre")

        assert turns == [{"role": "user", "content": "Et du beurre"}]

    async def test_a_caller_that_stored_its_message_still_sends_something(self):
        """The API refuses an empty conversation: `[]` here would be a 400, not a degraded answer."""
        with patch("app.llm.history.message_repo") as repo:
            repo.find_by_context = AsyncMock(return_value=[])
            repo.find_recent = AsyncMock(side_effect=RuntimeError("no database"))

            turns = await build_history(OWNER, context_id="ctx-1", fallback_message="Et du beurre")

        assert turns == [{"role": "user", "content": "Et du beurre"}]

    async def test_the_fallback_is_only_used_when_there_is_nothing_else(self, say, thread):
        """It is a floor, not a second copy of the message the history already holds."""
        courses = await thread("Courses")
        await say("user", "De la farine", context=courses.id, minutes=1)

        turns = await build_history(OWNER, context_id=courses.id, fallback_message="De la farine")

        assert turns == [{"role": "user", "content": "De la farine"}]

    async def test_without_a_fallback_the_history_is_simply_empty(self):
        """The caller then has nothing to answer either — a proaction's prompt is not a history."""
        with patch("app.llm.history.message_repo") as repo:
            repo.find_by_context = AsyncMock(return_value=[])
            repo.find_recent = AsyncMock(side_effect=RuntimeError("no database"))

            assert await build_history(OWNER, context_id="ctx-1") == []

    async def test_labels_that_will_not_load_cost_the_label_only(self, say, thread):
        """The neutral marker still says the one thing that matters: this was not said here."""
        budget = await thread("Budget")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        await say("user", "Ajoute du beurre", context="ctx-other", minutes=3)

        with patch("app.llm.history.context_repo") as contexts:
            contexts.find_active = AsyncMock(side_effect=RuntimeError("no database"))
            turns = await build_history(OWNER, context_id=budget.id)

        assert turns[0]["content"] == "Où en est mon budget ?"
        assert turns[2]["content"] == "[autre fil] Ajoute du beurre"

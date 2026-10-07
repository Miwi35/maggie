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
from app.llm.tool_blocks import record

OWNER = "user-1"
NEIGHBOUR = "user-2"

BASE = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)


@pytest.fixture(autouse=True)
def _it_is_the_first_of_october():
    """The day the messages below are written: nothing in them is « from another day » unless a test says so."""
    with patch("app.llm.history._now", return_value=BASE + timedelta(hours=3)):
        yield


@pytest.fixture()
def say(chat_db):
    """Store a message at `BASE + minutes`, through the repository the code under test uses."""

    async def _store(
        role: str,
        content: str,
        *,
        context: str | None = None,
        minutes: int = 0,
        user: str = OWNER,
        message_id: str | None = None,
        blocks: list[dict] | None = None,
    ):
        message = await message_repo.create(
            user_id=user, role=role, content=content, context_id=context, blocks=blocks
        )
        # `created_at` defaults to "now", and every message of a test would then share a
        # timestamp at SQLite's resolution — which is exactly the tie the ordering has to
        # survive. Set explicitly, through the same session factory the repository uses.
        async with chat_db.session() as session:
            from sqlalchemy import select

            from app.db.models import Message

            stored = (await session.execute(select(Message).where(Message.id == message.id))).scalar_one()
            stored.created_at = BASE + timedelta(minutes=minutes)
            # The id is settable too, because it is the *second* sort key: a tie on
            # `created_at` has to be decided by the role, and a random id cannot prove that.
            if message_id is not None:
                stored.id = message_id
            await session.commit()
            await session.refresh(stored)
        return stored

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
            settings.tool_replay_turns = 2
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
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=courses.id)

        joined = " ".join(turn["content"] for turn in turns)
        assert "message 5" in joined
        assert "message 6" in joined
        assert "message 1" not in joined
        assert "message 4" not in joined

    async def test_an_answer_never_sorts_before_its_question(self, say, thread):
        """Two messages of one exchange can share a timestamp at the database's resolution.

        The ids are set so that sorting on them would put every answer first: that is what
        the old key did, and what makes this test fail if the role stops breaking the tie.
        """
        courses = await thread("Courses")
        await say("assistant", "C'est noté.", context=courses.id, minutes=1, message_id="aaa1")
        await say("user", "De la farine", context=courses.id, minutes=1, message_id="bbb1")
        await say("assistant", "Ajouté.", context=courses.id, minutes=2, message_id="aaa2")
        await say("user", "Et du beurre", context=courses.id, minutes=2, message_id="bbb2")

        turns = await build_history(OWNER, context_id=courses.id)

        assert [turn["role"] for turn in turns] == ["user", "assistant", "user", "assistant"]
        assert turns[0]["content"] == "De la farine"
        assert turns[2]["content"] == "Et du beurre"

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


class TestWhatTheToolsSaid:
    """A `tool_use` and its `tool_result` survive the turn they were made in (MAG-211).

    What the model is sent is the only observable: these read the turns, and the one thing
    every assertion comes back to is the API's own rule — a `tool_result` sits
    immediately behind the `tool_use` whose id it carries, or nothing is sent at all.
    """

    @staticmethod
    def _round(tool_id: str = "toolu_1", result: str = '{"items": ["Pile LR03"]}') -> list[dict]:
        call = {"id": tool_id, "name": "get_grocery_list", "input": {"includeDeferred": False}, "result": result}
        return record([call])

    async def test_the_call_and_its_result_reach_the_next_turn(self, say, thread):
        courses = await thread("Courses")
        await say("user", "Qu'est-ce qu'il me faut acheter ?", context=courses.id, minutes=1)
        await say("assistant", "Des tomates et des piles.", context=courses.id, minutes=2, blocks=self._round())
        await say("user", "Et le dernier article, c'est quoi ?", context=courses.id, minutes=3)

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=courses.id)

        assert [turn["role"] for turn in turns] == ["user", "assistant", "user", "assistant", "user"]
        # The round, in front of the text it led to, and paired by `tool_use_id`.
        assert turns[1]["content"][0]["name"] == "get_grocery_list"
        assert turns[2]["content"][0]["tool_use_id"] == turns[1]["content"][0]["id"]
        assert "Pile LR03" in turns[2]["content"][0]["content"]
        # And the message still says what it said.
        assert turns[3] == {"role": "assistant", "content": "Des tomates et des piles."}

    async def test_beyond_the_window_a_turn_keeps_its_text_and_loses_its_blocks(self, say, thread):
        courses = await thread("Courses")
        await say("user", "Ma liste ?", context=courses.id, minutes=1)
        await say("assistant", "La voilà.", context=courses.id, minutes=2, blocks=self._round("toolu_old"))
        await say("user", "Et mon agenda ?", context=courses.id, minutes=3)
        await say("assistant", "Rien aujourd'hui.", context=courses.id, minutes=4, blocks=self._round("toolu_new"))

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 1
            turns = await build_history(OWNER, context_id=courses.id)

        sent = str(turns)
        assert "toolu_new" in sent
        assert "toolu_old" not in sent
        # The turn that lost its blocks is still in the conversation, as its text.
        assert {"role": "assistant", "content": "La voilà."} in turns

    async def test_a_window_of_zero_sends_the_text_alone(self, say, thread):
        courses = await thread("Courses")
        await say("user", "Ma liste ?", context=courses.id, minutes=1)
        await say("assistant", "La voilà.", context=courses.id, minutes=2, blocks=self._round())

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 0
            turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [
            {"role": "user", "content": "Ma liste ?"},
            {"role": "assistant", "content": "La voilà."},
        ]

    async def test_blocks_that_cannot_be_vouched_for_cost_the_blocks_only(self, say, thread):
        """A `tool_use` whose result went missing is a rejected call, so the text answers alone."""
        courses = await thread("Courses")
        broken = self._round()[:1]  # the call, without its result
        await say("user", "Ma liste ?", context=courses.id, minutes=1)
        await say("assistant", "La voilà.", context=courses.id, minutes=2, blocks=broken)

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [
            {"role": "user", "content": "Ma liste ?"},
            {"role": "assistant", "content": "La voilà."},
        ]

    async def test_another_threads_call_is_never_replayed(self, say, thread):
        """Its text comes in, which is all a neighbouring thread is there for."""
        budget = await thread("Budget")
        courses = await thread("Courses")
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=1)
        await say("assistant", "Il tient la route.", context=budget.id, minutes=2)
        await say("assistant", "Ta liste est prête.", context=courses.id, minutes=3, blocks=self._round())

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=budget.id)

        assert "get_grocery_list" not in str(turns)
        assert "Ta liste est prête." in str(turns)

    async def test_an_answer_just_before_a_round_is_folded_into_it(self, say, thread):
        """The API refuses two of Maggie's turns in a row, and a proaction lands between two."""
        courses = await thread("Courses")
        await say("user", "Ma liste ?", context=courses.id, minutes=1)
        await say("assistant", "Pense à sortir les poubelles.", context=courses.id, minutes=2)
        await say("assistant", "La voilà.", context=courses.id, minutes=3, blocks=self._round())

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=courses.id)

        assert [turn["role"] for turn in turns] == ["user", "assistant", "user", "assistant"]
        # The proaction is still there, as the text block in front of the call.
        assert turns[1]["content"][0] == {"type": "text", "text": "Pense à sortir les poubelles."}
        assert turns[1]["content"][1]["type"] == "tool_use"

    async def test_a_history_opening_on_a_batch_of_results_is_trimmed(self, say, thread):
        """An orphan `tool_result` at the front is refused exactly as flatly as a leading answer."""
        courses = await thread("Courses")
        await say("assistant", "La voilà.", context=courses.id, minutes=1, blocks=self._round())
        await say("user", "Merci", context=courses.id, minutes=2)

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=courses.id)

        assert turns == [{"role": "user", "content": "Merci"}]

    async def test_with_no_thread_nothing_is_replayed(self, say, thread):
        """No thread, no conversation to replay a call into — the window is all there is."""
        courses = await thread("Courses")
        await say("user", "Ma liste ?", context=courses.id, minutes=1)
        await say("assistant", "La voilà.", context=courses.id, minutes=2, blocks=self._round())

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=None)

        assert turns == [
            {"role": "user", "content": "Ma liste ?"},
            {"role": "assistant", "content": "La voilà."},
        ]

    async def test_a_pending_message_still_arrives_after_a_replayed_round(self, say, thread):
        """The A2A path hands its message over, and a round must not swallow it."""
        courses = await thread("Courses")
        await say("user", "Ma liste ?", context=courses.id, minutes=1)
        await say("assistant", "La voilà.", context=courses.id, minutes=2, blocks=self._round())

        with patch("app.llm.history.settings") as settings:
            settings.context_history_messages = 40
            settings.recent_history_messages = 8
            settings.tool_replay_turns = 2
            turns = await build_history(OWNER, context_id=courses.id, pending_message="Et le dernier article ?")

        assert turns[-1] == {"role": "user", "content": "Et le dernier article ?"}


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


SCREEN = "[Contexte de l'écran]\nApplication : Chrome (com.android.chrome)\nPage : https://dice.fm/event/x"


class TestTheScreenTheAssistantWasSummonedFrom:
    """The model reads the screen; the conversation does not keep it (MAG-30).

    Recette refused the first delivery, where the block was stored inside the user's
    message: every reader of the history then showed the page instead of the question.
    It is put back here, on the one turn it was attached to.
    """

    async def test_the_block_comes_back_on_the_turn_being_answered(self, say, thread):
        sorties = await thread("Sorties")
        asked = await say("user", "De quoi parle cette page ?", context=sorties.id, minutes=1)

        turns = await build_history(
            OWNER, context_id=sorties.id, current_message_id=asked.id, screen_context=SCREEN
        )

        assert turns == [{"role": "user", "content": f"{SCREEN}\n\nDe quoi parle cette page ?"}]

    async def test_the_earlier_turns_keep_only_what_was_said(self, say, thread):
        """« ajoute ça à mon agenda » was about the screen; the follow-up is about the answer."""
        sorties = await thread("Sorties")
        await say("user", "C'est quoi ce produit ?", context=sorties.id, minutes=1)
        await say("assistant", "Un billet de concert.", context=sorties.id, minutes=2)
        asked = await say("user", "Et le prix ?", context=sorties.id, minutes=3)

        turns = await build_history(
            OWNER, context_id=sorties.id, current_message_id=asked.id, screen_context=SCREEN
        )

        assert turns[0]["content"] == "C'est quoi ce produit ?"
        assert turns[2]["content"] == f"{SCREEN}\n\nEt le prix ?"

    async def test_no_context_leaves_the_conversation_untouched(self, say, thread):
        sorties = await thread("Sorties")
        asked = await say("user", "De quoi parle cette page ?", context=sorties.id, minutes=1)

        turns = await build_history(OWNER, context_id=sorties.id, current_message_id=asked.id)

        assert turns == [{"role": "user", "content": "De quoi parle cette page ?"}]

    async def test_a_history_that_would_not_load_still_sends_the_screen(self):
        """The fallback is the whole conversation then — without the block the model is blind."""
        with patch("app.llm.history.message_repo") as repo:
            repo.find_by_context = AsyncMock(return_value=[])
            repo.find_recent = AsyncMock(side_effect=RuntimeError("no database"))

            turns = await build_history(
                OWNER, context_id="ctx-1", fallback_message="De quoi parle cette page ?", screen_context=SCREEN
            )

        assert turns == [{"role": "user", "content": f"{SCREEN}\n\nDe quoi parle cette page ?"}]


DAY = timedelta(days=1)
THURSDAY_8 = datetime(2026, 10, 8, 11, 45, tzinfo=UTC)


class TestEveryPastDayIsDated:
    """8 Oct. (MAG-349): « demain vendredi 3 octobre », written on the 2nd, was read on the 8th as tomorrow.

    A message from another day carries its date, so a « demain » or a « mercredi » in it
    cannot be mistaken for the present's.
    """

    @pytest.fixture(autouse=True)
    def _it_is_the_8th(self):
        with patch("app.llm.history._now", return_value=THURSDAY_8):
            yield

    async def test_a_past_day_opens_with_its_date(self, say):
        await say("user", "Quand est-ce que je vois Julie ?", minutes=0)
        turns = await build_history(OWNER)

        assert turns[0]["content"].startswith("— le jeudi 1er octobre —\n")
        assert turns[0]["content"].endswith("Quand est-ce que je vois Julie ?")

    async def test_the_answers_are_not_dated(self, say):
        """She would copy it, the way she copied the thread label (MAG-341)."""
        await say("user", "Mon agenda demain ?", minutes=0)
        await say("assistant", "Demain vendredi 2 octobre : rien.", minutes=1)

        turns = await build_history(OWNER)

        assert turns[1] == {"role": "assistant", "content": "Demain vendredi 2 octobre : rien."}

    async def test_a_day_is_announced_once(self, say):
        await say("user", "Premier message", minutes=0)
        await say("assistant", "Réponse", minutes=1)
        await say("user", "Second message", minutes=2)

        turns = await build_history(OWNER)

        assert [t["content"].count("— le jeudi 1er octobre —") for t in turns] == [1, 0, 0]

    async def test_a_new_day_is_announced_again_and_today_is_named(self, say):
        await say("user", "Hier soir", minutes=0)
        await say("assistant", "Réponse d'hier", minutes=1)
        await say("user", "Deux jours plus tard", minutes=2 * 24 * 60)
        await say("assistant", "Réponse", minutes=2 * 24 * 60 + 1)
        await say("user", "Aujourd'hui", minutes=7 * 24 * 60 + 60)

        turns = await build_history(OWNER)
        users = [t["content"] for t in turns if t["role"] == "user"]

        assert users == [
            "— le jeudi 1er octobre —\nHier soir",
            "— le samedi 3 octobre —\nDeux jours plus tard",
            "— aujourd'hui, jeudi 8 octobre —\nAujourd'hui",
        ]

    async def test_a_history_of_today_is_left_as_it_was(self, say):
        await say("user", "Bonjour", minutes=7 * 24 * 60 + 60)
        await say("assistant", "Bonjour monsieur.", minutes=7 * 24 * 60 + 61)

        turns = await build_history(OWNER)

        assert turns == [
            {"role": "user", "content": "Bonjour"},
            {"role": "assistant", "content": "Bonjour monsieur."},
        ]

    async def test_the_day_is_the_users_not_utc(self, say):
        """22h30 UTC on the 1st is half past midnight on the 2nd in Paris."""
        await say("user", "Tard le soir", minutes=13 * 60 + 30)

        turns = await build_history(OWNER)

        assert turns[0]["content"].startswith("— le vendredi 2 octobre —\n")

    async def test_the_users_timezone_decides(self, say):
        from zoneinfo import ZoneInfo

        await say("user", "Tard le soir", minutes=13 * 60 + 30)

        turns = await build_history(OWNER, tz=ZoneInfo("America/Martinique"))

        assert turns[0]["content"].startswith("— le jeudi 1er octobre —\n")

    async def test_the_message_of_another_thread_keeps_its_label_after_the_date(self, say, thread):
        budget = await thread("Budget")
        courses = await thread("Courses")
        await say("user", "Ajoute du beurre", context=courses.id, minutes=0)
        await say("user", "Où en est mon budget ?", context=budget.id, minutes=7 * 24 * 60 + 60)

        turns = await build_history(OWNER, context_id=budget.id)

        assert turns == [
            {
                "role": "user",
                "content": "— le jeudi 1er octobre —\n[fil « Courses »] Ajoute du beurre\n"
                "— aujourd'hui, jeudi 8 octobre —\nOù en est mon budget ?",
            }
        ]

    async def test_another_year_says_so(self, say):
        await say("user", "Un an plus tôt", minutes=-365 * 24 * 60)

        turns = await build_history(OWNER)

        assert turns[0]["content"].startswith("— le mercredi 1er octobre 2025 —\n")

    async def test_the_reproduction_a_tomorrow_written_on_the_2nd(self, say):
        """The incident: the history says « demain vendredi 3 octobre » and the question is « quel jour sommes-nous »."""
        await say("user", "Quand est-ce que je vois Julie ?", minutes=24 * 60 + 60)
        await say("assistant", "Demain, vendredi 3 octobre, de 19 h à minuit.", minutes=24 * 60 + 61)
        asked = await say("user", "Quel jour sommes-nous ?", minutes=7 * 24 * 60 + 60)

        turns = await build_history(OWNER, current_message_id=asked.id)

        assert turns[0]["content"].startswith("— le vendredi 2 octobre —\n")
        assert turns[2]["content"] == "— aujourd'hui, jeudi 8 octobre —\nQuel jour sommes-nous ?"

    async def test_a_pending_message_after_a_past_day_is_today(self, say):
        await say("user", "Il y a une semaine", minutes=0)

        turns = await build_history(OWNER, pending_message="Et maintenant ?")

        assert turns[-1]["content"].endswith("— aujourd'hui, jeudi 8 octobre —\nEt maintenant ?")


class TestThePictureTheQuestionCameWith:
    """The screenshot is read on its turn and kept nowhere (MAG-214)."""

    @pytest.fixture()
    def image(self):
        from app.llm.image import ChatImage

        return ChatImage(media_type="image/jpeg", data="/9j/AAAA")

    async def test_it_goes_on_the_turn_being_answered_before_the_question(self, chat_db, thread, image):
        boutique = await thread("Boutique")
        asked = await message_repo.create(
            user_id=OWNER, role="user", content="C'est quoi ce produit ?", context_id=boutique.id, has_image=True
        )

        turns = await build_history(
            OWNER, context_id=boutique.id, current_message_id=asked.id, screen_context=SCREEN, image=image
        )

        assert turns == [
            {
                "role": "user",
                "content": [image.block(), {"type": "text", "text": f"{SCREEN}\n\nC'est quoi ce produit ?"}],
            }
        ]

    async def test_an_earlier_turn_that_had_one_says_it_is_gone(self, chat_db, say, thread):
        from app.llm.image import GONE_MARKER

        from sqlalchemy import select

        from app.db.models import Message

        boutique = await thread("Boutique")
        before = await say("user", "C'est quoi ce produit ?", context=boutique.id)
        await say("assistant", "Une cafetière italienne.", context=boutique.id, minutes=1)
        asked = await say("user", "Et son prix ?", context=boutique.id, minutes=2)
        async with chat_db.session() as session:
            stored = (await session.execute(select(Message).where(Message.id == before.id))).scalar_one()
            stored.has_image = True
            await session.commit()

        turns = await build_history(OWNER, context_id=boutique.id, current_message_id=asked.id)

        assert turns[0]["content"] == f"{GONE_MARKER} C'est quoi ce produit ?"
        assert turns[2]["content"] == "Et son prix ?"

    async def test_a_history_that_would_not_load_still_sends_it(self, image):
        with patch("app.llm.history.message_repo") as repo:
            repo.find_by_context = AsyncMock(return_value=[])
            repo.find_recent = AsyncMock(side_effect=RuntimeError("no database"))

            turns = await build_history(OWNER, context_id="ctx-1", fallback_message="C'est quoi ?", image=image)

        assert turns == [{"role": "user", "content": [image.block(), {"type": "text", "text": "C'est quoi ?"}]}]

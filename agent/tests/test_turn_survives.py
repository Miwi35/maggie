"""A turn goes to its end whether or not the client is still there (MAG-344).

On 7 Oct. the owner left the app to wait for a notification and two messages never got an
answer: the turn lived in the generator of the streamed response, and the response died with
the connection.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import select

from app.api import routes
from app.db.message_repository import message_repo
from app.db.models import TURN_EXPIRED, Message
from app.llm.history import build_history
from app.llm.streaming import answer_message_id
from app.llm.turns import TurnRunner, lease_deadline


class SlowGateway:
    """A gateway that answers after the client has had time to leave — and stores it like the real one."""

    def __init__(self):
        self.runs = 0

    async def chat_stream(self, message, user_id, user_msg_id, **_kwargs):
        self.runs += 1
        yield {"type": "RUN_STARTED", "runId": "run-1"}
        await asyncio.sleep(0.05)
        await message_repo.create(user_id=user_id, role="assistant", content="Rappel programmé.")
        yield {"type": "RUN_FINISHED", "runId": "run-1"}


async def stored_assistant_messages(user_id: str, timeout: float = 2.0) -> list:
    """The assistant's messages, once there is one — or after `timeout` if there never is."""
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        rows = [m for m in await message_repo.find_recent(user_id) if m.role == "assistant"]
        if rows or asyncio.get_running_loop().time() >= deadline:
            return rows
        await asyncio.sleep(0.02)


class TestAClientThatLeaves:
    @pytest.mark.asyncio
    async def test_the_answer_is_stored_and_published_anyway(self, chat_db):
        gateway = SlowGateway()
        publish = AsyncMock()

        with (
            patch.object(routes, "streaming_gateway", gateway),
            patch.object(message_repo.publisher, "publish", publish),
        ):
            response = await routes.chat_stream(
                routes.ChatRequest(message="rappelle-moi de me servir un café dans 1 min"), user_id="user-1"
            )
            stream = response.body_iterator
            await stream.__anext__()
            # The app is closed: the server stops reading, and the connection goes with it.
            await stream.aclose()

            answers = await stored_assistant_messages("user-1")

        assert [m.content for m in answers] == ["Rappel programmé."]
        published_roles = [call.args[1]["role"] for call in publish.await_args_list]
        assert published_roles == ["user", "assistant"]


NOW = datetime.now(UTC)


class RecordingGateway:
    """Stores its answer under the id the real gateway uses, and remembers what it was asked."""

    def __init__(self):
        self.asked: list[str] = []
        self.screens: list[str | None] = []

    async def chat_stream(self, message, user_id, user_msg_id, screen_context=None, **_kwargs):
        self.asked.append(message)
        self.screens.append(screen_context)
        yield {"type": "RUN_STARTED", "runId": "run-1"}
        await message_repo.create(
            user_id=user_id, role="assistant", content="C'est fait.", message_id=answer_message_id(user_msg_id)
        )
        yield {"type": "RUN_FINISHED", "runId": "run-1"}


async def orphan(chat_db, *, content: str, age: timedelta, user_id: str = "user-1"):
    """A message whose turn was lost with its process: running, its lease long lapsed, `age` old."""
    message = await message_repo.create(
        user_id=user_id, role="user", content=content, turn_lease_until=NOW - age + timedelta(seconds=30)
    )
    async with chat_db.session() as session:
        stored = (await session.execute(select(Message).where(Message.id == message.id))).scalar_one()
        stored.created_at = NOW - age
        await session.commit()
    return message


def assistant_contents(rows) -> list[str]:
    return [m.content for m in rows if m.role == "assistant"]


class TestAnAgentThatRestarts:
    @pytest.mark.asyncio
    async def test_a_recent_message_is_answered_once_when_the_agent_comes_back(self, chat_db):
        lost = await orphan(chat_db, content="rappelle-moi de me servir un café dans 1 min", age=timedelta(minutes=2))
        gateway = RecordingGateway()
        runner = TurnRunner()

        assert await runner.resume_lost_turns(gateway) == 1
        await runner.idle()
        # A second agent coming up right after, or the sweeper passing again.
        assert await runner.resume_lost_turns(gateway) == 0
        await runner.idle()

        assert gateway.asked == ["rappelle-moi de me servir un café dans 1 min"]
        assert assistant_contents(await message_repo.find_recent("user-1")) == ["C'est fait."]
        assert (await message_repo.get(lost.id)).turn_status is None

    @pytest.mark.asyncio
    async def test_two_processes_never_take_the_same_message(self, chat_db):
        await orphan(chat_db, content="et du pain", age=timedelta(minutes=1))

        first = await message_repo.claim_resumable_turns(NOW - timedelta(minutes=5), lease_deadline())
        second = await message_repo.claim_resumable_turns(NOW - timedelta(minutes=5), lease_deadline())

        assert [len(first), len(second)] == [1, 0]

    @pytest.mark.asyncio
    async def test_a_message_too_old_is_given_up_on_and_never_executed(self, chat_db):
        stale = await orphan(chat_db, content="rappelle-moi de me servir un café dans 1 min", age=timedelta(hours=2))
        gateway = RecordingGateway()
        runner = TurnRunner()

        assert await runner.resume_lost_turns(gateway) == 0
        await runner.idle()

        assert gateway.asked == []
        assert (await message_repo.get(stale.id)).turn_status == TURN_EXPIRED
        assert assistant_contents(await message_repo.find_recent("user-1")) == []

    @pytest.mark.asyncio
    async def test_a_turn_still_held_by_a_live_process_is_left_alone(self, chat_db):
        await message_repo.create(user_id="user-1", role="user", content="en cours", turn_lease_until=lease_deadline())
        gateway = RecordingGateway()
        runner = TurnRunner()

        assert await runner.resume_lost_turns(gateway) == 0
        assert gateway.asked == []


    @pytest.mark.asyncio
    async def test_a_resumed_turn_gets_back_the_screen_it_was_summoned_from(self, chat_db):
        lost = await orphan(chat_db, content="résume cette page", age=timedelta(minutes=1))
        async with chat_db.session() as session:
            stored = (await session.execute(select(Message).where(Message.id == lost.id))).scalar_one()
            stored.turn_screen_context = "[Contexte de l'écran]\nPage : https://exemple.fr"
            await session.commit()
        gateway = RecordingGateway()
        runner = TurnRunner()

        await runner.resume_lost_turns(gateway)
        await runner.idle()

        assert gateway.screens == ["[Contexte de l'écran]\nPage : https://exemple.fr"]
        assert (await message_repo.get(lost.id)).turn_screen_context is None


class TestTheHistoryDoesNotReplayAnOrphan:
    @pytest.mark.asyncio
    async def test_neither_an_expired_request_nor_one_awaiting_pickup_is_sent_to_the_model(self, chat_db):
        await orphan(chat_db, content="vieille demande", age=timedelta(hours=3))
        await orphan(chat_db, content="demande perdue", age=timedelta(minutes=1))
        await message_repo.create(user_id="user-1", role="user", content="en cours", turn_lease_until=lease_deadline())
        await message_repo.create(user_id="user-1", role="user", content="déjà répondu")

        turns = await build_history("user-1")

        assert turns == [{"role": "user", "content": "en cours\ndéjà répondu"}]


    @pytest.mark.asyncio
    async def test_a_resumed_turn_is_not_sent_the_answer_to_the_request_retyped_after_it(self, chat_db):
        lost = await orphan(chat_db, content="rappelle-moi le café", age=timedelta(minutes=2))
        retyped = await message_repo.create(user_id="user-1", role="user", content="rappelle-moi le café !")
        await message_repo.create(user_id="user-1", role="assistant", content="C'est noté.")

        turns = await build_history("user-1", current_message_id=lost.id)

        assert retyped.id != lost.id
        assert turns[-1]["role"] == "user"
        assert turns == [{"role": "user", "content": "rappelle-moi le café"}]


class TestTheSameMessageSentTwice:
    @pytest.mark.asyncio
    async def test_the_same_key_makes_one_message_and_one_answer(self, chat_db):
        gateway = RecordingGateway()
        request = routes.ChatRequest(message="rappelle-moi de me servir un café dans 1 min", idempotency_key="k-1")

        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            first = await routes.chat_stream(request, user_id="user-1")
            body_one = [chunk async for chunk in first.body_iterator]
            # The connection dropped on the way back; the client sends the same message again.
            second = await routes.chat_stream(request, user_id="user-1")
            body_two = [chunk async for chunk in second.body_iterator]

        rows = await message_repo.find_recent("user-1")
        assert [m.role for m in rows].count("user") == 1
        assert assistant_contents(rows) == ["C'est fait."]
        assert gateway.asked == ["rappelle-moi de me servir un café dans 1 min"]
        assert any("C'est fait." in chunk for chunk in body_two)
        assert body_one

    @pytest.mark.asyncio
    async def test_another_user_with_the_same_key_is_another_message(self, chat_db):
        gateway = RecordingGateway()
        runner = TurnRunner()

        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", runner):
            for user in ("user-1", "user-2"):
                response = await routes.chat_stream(routes.ChatRequest(message="salut", idempotency_key="k"), user)
                [chunk async for chunk in response.body_iterator]

        assert gateway.asked == ["salut", "salut"]

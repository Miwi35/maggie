"""The same message sent again while Maggie still answers it is the same message (MAG-386).

On 10 Oct. the owner's request to swap two labels arrived twice, a minute apart, because the
answer was slow. Both turns ran: the second swap undid the first.
"""

import asyncio
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import update

from app.api import routes
from app.db.message_repository import message_repo
from app.db.models import Message
from app.llm import turns
from app.llm.duplicates import normalize
from app.llm.gateway import API_ERROR_MESSAGE
from app.llm.image import ChatImage
from app.llm.streaming import answer_message_id
from app.llm.turns import TurnRunner, lease_deadline

TEXT = "inverse les libellés Laure et Meven sur les événements de l'agenda Garde Ael"


class ToolGateway:
    """Calls its tool once per turn, slowly, and stores its answer under the id the real gateway uses."""

    def __init__(self, duration: float = 0.3):
        self.duration = duration
        self.tool_calls = 0
        self.asked: list[str] = []

    async def chat_stream(self, message, user_id, user_msg_id, **_kwargs):
        self.asked.append(message)
        yield {"type": "RUN_STARTED", "runId": "run-1"}
        self.tool_calls += 1
        await asyncio.sleep(self.duration)
        await message_repo.create(
            user_id=user_id, role="assistant", content="C'est fait.", message_id=answer_message_id(user_msg_id)
        )
        yield {"type": "RUN_FINISHED", "runId": "run-1"}


@pytest.fixture(autouse=True)
def quick_polling():
    with patch.object(turns, "REPLAY_POLL_SECONDS", 0.02):
        yield


async def send(text: str, *, user_id: str = "user-1", **fields):
    response = await routes.chat_stream(routes.ChatRequest(message=text, **fields), user_id=user_id)
    return [chunk async for chunk in response.body_iterator]


async def age_everything(chat_db, seconds: int, *, user_id: str = "user-1") -> None:
    """Make every stored message `seconds` older: the time of the first turn's end, as far as the rule sees."""
    async with chat_db.session() as session:
        for message in (await message_repo.find_recent(user_id, limit=50)):
            await session.execute(
                update(Message)
                .where(Message.id == message.id)
                .values(created_at=datetime.now(UTC) - timedelta(seconds=seconds))
            )
        await session.commit()


async def stored(user_id: str = "user-1") -> list[Message]:
    return await message_repo.find_recent(user_id, limit=50)


class TestTheSameTextWhileTheTurnRuns:
    @pytest.mark.asyncio
    async def test_a_second_post_follows_the_running_turn_instead_of_opening_one(self, chat_db):
        gateway = ToolGateway()
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            first = await routes.chat_stream(routes.ChatRequest(message=TEXT), user_id="user-1")
            await asyncio.sleep(0.05)
            # « La réponse tardait » : the owner sends it again, with a space and a capital more.
            second = await routes.chat_stream(routes.ChatRequest(message=TEXT.capitalize() + " "), user_id="user-1")
            first_body, second_body = await asyncio.gather(
                _read(first), _read(second)
            )

        rows = await stored()
        assert gateway.tool_calls == 1
        assert [m.role for m in rows] == ["user", "assistant"]
        assert rows[0].content == TEXT
        assert any("RUN_FINISHED" in chunk for chunk in first_body)
        assert any("C'est fait." in chunk for chunk in second_body)

    @pytest.mark.asyncio
    async def test_a_dictation_said_twice_is_the_same_case(self, chat_db):
        gateway = ToolGateway()
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            first = await routes.chat_stream(routes.ChatRequest(message="ajoute du basilic"), user_id="user-1")
            second = await routes.chat_stream(routes.ChatRequest(message="Ajoute du  basilic"), user_id="user-1")
            await asyncio.gather(_read(first), _read(second))

        assert gateway.tool_calls == 1

    @pytest.mark.asyncio
    async def test_two_posts_at_once_still_open_one_turn(self, chat_db):
        gateway = ToolGateway()
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await asyncio.gather(send(TEXT), send(TEXT))

        assert gateway.tool_calls == 1
        assert [m.role for m in await stored()] == ["user", "assistant"]


class TestTheSameTextOnceTheTurnIsOver:
    @pytest.mark.asyncio
    async def test_within_a_minute_the_answer_is_replayed_and_nothing_runs(self, chat_db):
        gateway = ToolGateway(duration=0)
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send(TEXT)
            await age_everything(chat_db, 30)
            body = await send(TEXT)

        assert gateway.tool_calls == 1
        assert [m.role for m in await stored()] == ["user", "assistant"]
        assert any("C'est fait." in chunk for chunk in body)

    @pytest.mark.asyncio
    async def test_ninety_seconds_later_it_is_a_new_turn(self, chat_db):
        gateway = ToolGateway(duration=0)
        runner = TurnRunner()
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", runner):
            await send(TEXT)
            await age_everything(chat_db, 90)
            await send(TEXT)

        assert gateway.tool_calls == 2
        assert [m.role for m in await stored()].count("user") == 2

    @pytest.mark.asyncio
    async def test_an_answer_the_owner_cut_off_does_not_stop_him_from_asking_again(self, chat_db):
        gateway = ToolGateway(duration=0)
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send(TEXT)
            answer = (await stored())[-1]
            await message_repo.mark_interrupted(answer.id, "(rien dit)")
            await send(TEXT)

        assert gateway.tool_calls == 2


class TestAnAnswerThatDoesNotStand:
    @pytest.mark.asyncio
    async def test_a_cut_off_answer_of_a_turn_still_running_is_not_attached_to(self, chat_db):
        """`chat_interrupt` stores the cut answer while the turn goes on, until it finds its row taken."""
        question = await message_repo.create(
            user_id="user-1", role="user", content=TEXT, turn_lease_until=lease_deadline()
        )
        await message_repo.create(
            user_id="user-1",
            role="assistant",
            content="(rien dit)",
            message_id=answer_message_id(question.id),
            interrupted=True,
        )
        gateway = ToolGateway(duration=0)
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send(TEXT)

        assert gateway.tool_calls == 1

    @pytest.mark.asyncio
    async def test_the_apology_of_a_failed_turn_does_not_stop_the_owner_from_retrying(self, chat_db):
        question = await message_repo.create(user_id="user-1", role="user", content=TEXT)
        await message_repo.create(
            user_id="user-1", role="assistant", content=API_ERROR_MESSAGE, message_id=answer_message_id(question.id)
        )
        gateway = ToolGateway(duration=0)
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send(TEXT)

        assert gateway.tool_calls == 1

    @pytest.mark.asyncio
    async def test_the_same_goes_for_the_non_streamed_route(self, chat_db):
        chat = AsyncMock(return_value={"response": API_ERROR_MESSAGE, "error": True})
        with patch.object(routes.llm_gateway, "chat", chat):
            await routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1")
            await routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1")

        assert chat.await_count == 2


class TestWhatIsNotTheSameMessage:
    @pytest.mark.asyncio
    async def test_another_text_is_a_new_turn(self, chat_db):
        gateway = ToolGateway()
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            first = await routes.chat_stream(routes.ChatRequest(message=TEXT), user_id="user-1")
            second = await routes.chat_stream(routes.ChatRequest(message="et ajoute du pain"), user_id="user-1")
            await asyncio.gather(_read(first), _read(second))

        assert gateway.asked == [TEXT, "et ajoute du pain"]

    @pytest.mark.asyncio
    async def test_only_the_last_message_counts(self, chat_db):
        gateway = ToolGateway(duration=0)
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send("ajoute du lait")
            await send("et du pain")
            await send("ajoute du lait")

        assert gateway.asked == ["ajoute du lait", "et du pain", "ajoute du lait"]

    @pytest.mark.asyncio
    async def test_another_user_saying_the_same_thing_is_not_a_duplicate(self, chat_db):
        gateway = ToolGateway()
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await asyncio.gather(send(TEXT, user_id="user-1"), send(TEXT, user_id="user-2"))

        assert gateway.tool_calls == 2

    @pytest.mark.asyncio
    async def test_a_screenshot_makes_it_another_message(self, chat_db):
        gateway = ToolGateway(duration=0)
        image = ChatImage(media_type="image/jpeg", data="/9j/AAAA")
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send("c'est quoi ?")
            await send("c'est quoi ?", image=image)

        assert gateway.tool_calls == 2

    @pytest.mark.asyncio
    async def test_a_turn_that_never_answered_is_asked_again(self, chat_db):
        """No answer and no running turn (it failed, or expired): repeating it is how the owner retries."""
        await message_repo.create(user_id="user-1", role="user", content=TEXT)
        gateway = ToolGateway(duration=0)
        with patch.object(routes, "streaming_gateway", gateway), patch.object(routes, "turn_runner", TurnRunner()):
            await send(TEXT)

        assert gateway.tool_calls == 1


class TestTheNonStreamedRoute:
    @pytest.mark.asyncio
    async def test_a_second_post_shares_the_turn_in_flight(self, chat_db):
        calls = 0

        async def slow_chat(message, user_id, **_kwargs):
            nonlocal calls
            calls += 1
            await asyncio.sleep(0.2)
            return {"response": "C'est fait.", "tool_calls": [{"name": "update_event"}]}

        with patch.object(routes.llm_gateway, "chat", slow_chat):
            first = asyncio.create_task(routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1"))
            await asyncio.sleep(0.05)
            second = await routes.chat(routes.ChatRequest(message=TEXT.upper()), user_id="user-1")
            first = await first

        assert calls == 1
        assert second.response == first.response == "C'est fait."
        assert [m.role for m in await stored()] == ["user", "assistant"]

    @pytest.mark.asyncio
    async def test_within_a_minute_of_the_answer_it_returns_that_answer(self, chat_db):
        chat = AsyncMock(return_value={"response": "C'est fait."})
        with patch.object(routes.llm_gateway, "chat", chat):
            await routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1")
            await age_everything(chat_db, 30)
            again = await routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1")

        assert chat.await_count == 1
        assert again.response == "C'est fait."
        assert [m.role for m in await stored()] == ["user", "assistant"]

    @pytest.mark.asyncio
    async def test_ninety_seconds_later_it_is_a_new_turn(self, chat_db):
        chat = AsyncMock(return_value={"response": "C'est fait."})
        with patch.object(routes.llm_gateway, "chat", chat):
            await routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1")
            await age_everything(chat_db, 90)
            await routes.chat(routes.ChatRequest(message=TEXT), user_id="user-1")

        assert chat.await_count == 2


class TestNormalize:
    def test_case_and_spaces_do_not_matter(self):
        assert normalize("  Ajoute   du\nBasilic ") == normalize("ajoute du basilic")

    def test_another_word_does_matter(self):
        assert normalize("ajoute du basilic") != normalize("retire du basilic")


async def _read(response) -> list[str]:
    return [chunk async for chunk in response.body_iterator]

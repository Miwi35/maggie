"""Maggie knowing when the conversation last happened (MAG-10)."""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.context_model import ConversationContext
from app.db.message_repository import message_repo
from app.db.models import Message
from app.llm.gateway import LLMGateway
from app.llm.last_exchange import last_exchange_section
from app.llm.streaming import StreamingGateway


async def _write(
    chat_db,
    content: str,
    minutes_ago: int,
    *,
    user_id: str = "user-1",
    role: str = "user",
    context_id: str | None = None,
    message_id: str | None = None,
) -> str:
    async with chat_db.session() as session:
        message = Message(
            user_id=user_id,
            role=role,
            content=content,
            context_id=context_id,
            created_at=datetime.now(UTC) - timedelta(minutes=minutes_ago),
        )
        if message_id:
            message.id = message_id
        session.add(message)
        await session.commit()
        return message.id


async def _context(chat_db, label: str) -> str:
    async with chat_db.session() as session:
        context = ConversationContext(user_id="user-1", label=label)
        session.add(context)
        await session.commit()
        return context.id


class TestFindLast:
    async def test_returns_the_newest_message_whatever_its_role(self, chat_db):
        await _write(chat_db, "Il me faut de la farine", 30)
        await _write(chat_db, "C'est noté", 20, role="assistant")

        last = await message_repo.find_last("user-1")

        assert last is not None
        assert last.content == "C'est noté"

    async def test_skips_the_message_being_answered(self, chat_db):
        await _write(chat_db, "Hier soir", 600, role="assistant")
        await _write(chat_db, "Salut", 0, message_id="current")

        last = await message_repo.find_last("user-1", exclude_id="current")

        assert last is not None
        assert last.content == "Hier soir"

    async def test_another_users_messages_do_not_count(self, chat_db):
        await _write(chat_db, "Pas à lui", 5, user_id="user-2")

        assert await message_repo.find_last("user-1") is None

    async def test_a_user_with_no_message_has_no_last_one(self, chat_db):
        assert await message_repo.find_last("user-1") is None


class TestLastExchangeSection:
    async def test_the_line_carries_the_gap_and_the_thread_label(self, chat_db):
        context_id = await _context(chat_db, "Menus de la semaine")
        await _write(chat_db, "On mange quoi ?", 90, context_id=context_id)

        section = await last_exchange_section("user-1")

        assert section.startswith("\nDernière conversation : ")
        assert "(il y a 1h30)" in section
        assert section.endswith(", sujet : Menus de la semaine.")

    async def test_a_message_without_thread_has_no_topic(self, chat_db):
        await _write(chat_db, "Salut", 10)

        section = await last_exchange_section("user-1")

        assert "(il y a 10 min)" in section
        assert "sujet" not in section

    async def test_the_message_being_answered_is_not_the_last_conversation(self, chat_db):
        await _write(chat_db, "Hier soir", 600)
        await _write(chat_db, "Salut", 0, message_id="current")

        section = await last_exchange_section("user-1", exclude_message_id="current")

        assert "(il y a 10h)" in section

    async def test_a_first_conversation_adds_nothing(self, chat_db):
        assert await last_exchange_section("user-1") == ""

    async def test_a_first_message_adds_nothing(self, chat_db):
        """Only the message being answered exists: there is no earlier conversation to mention."""
        await _write(chat_db, "Bonjour", 0, message_id="current")

        assert await last_exchange_section("user-1", exclude_message_id="current") == ""

    async def test_an_unreadable_thread_costs_the_topic_not_the_line(self, chat_db):
        await _write(chat_db, "Salut", 10, context_id="gone")

        with patch("app.llm.last_exchange.context_repo") as contexts:
            contexts.get = AsyncMock(side_effect=RuntimeError("boom"))
            section = await last_exchange_section("user-1")

        assert "(il y a 10 min)" in section
        assert "sujet" not in section

    async def test_a_database_failure_costs_the_line_not_the_answer(self):
        with patch.object(message_repo, "find_last", AsyncMock(side_effect=RuntimeError("boom"))):
            assert await last_exchange_section("user-1") == ""


def _streaming_gateway() -> StreamingGateway:
    gateway = StreamingGateway()
    gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
    gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=""))
    return gateway


def _llm_gateway() -> LLMGateway:
    gateway = LLMGateway()
    gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
    gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=""))
    return gateway


class TestSystemPrompts:
    """The paths that speak to the user each put the line in the volatile block."""

    async def test_a_streamed_answer_knows_the_last_conversation(self, chat_db):
        await _write(chat_db, "Hier soir", 600, role="assistant")
        await _write(chat_db, "Salut", 0, message_id="current")

        with (
            patch("app.llm.streaming.last_exchange_section", last_exchange_section),
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.skill_index") as skills,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.refresh = AsyncMock()
            blocks = await _streaming_gateway()._build_system_prompt("user-1", exclude_message_id="current")

        assert "Dernière conversation : " in blocks[1]["text"]
        assert "(il y a 10h)" in blocks[1]["text"]
        assert "Dernière conversation" not in blocks[0]["text"]
        assert "il est " in blocks[1]["text"]

    async def test_a_proaction_knows_it_too(self, chat_db):
        await _write(chat_db, "Hier soir", 600, role="assistant")

        with (
            patch("app.llm.gateway.last_exchange_section", last_exchange_section),
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.gateway.skill_index") as skills,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.refresh = AsyncMock()
            blocks = await _llm_gateway()._build_system_prompt("user-1", preamble="\n\nTu es en mode proaction.")

        assert "(il y a 10h)" in blocks[1]["text"]
        assert blocks[1]["text"].endswith("Tu es en mode proaction.")

    async def test_the_streamed_run_excludes_the_message_it_is_answering(self):
        gateway = _streaming_gateway()
        gateway.client = MagicMock()
        gateway._build_system_prompt = AsyncMock(side_effect=RuntimeError("stop here"))
        gateway._resolve_context = AsyncMock(return_value=None)
        gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]))

        with patch("app.llm.streaming.build_history", AsyncMock(return_value=[])):
            try:
                async for _ in gateway.chat_stream("Salut", "user-1", "msg-42"):
                    pass
            except RuntimeError:
                pass

        assert gateway._build_system_prompt.await_args.kwargs["exclude_message_id"] == "msg-42"

    async def test_the_sync_chat_excludes_the_message_it_is_answering(self):
        gateway = _llm_gateway()
        gateway.client = MagicMock()
        gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]))
        gateway._build_system_prompt = AsyncMock(return_value=[])

        with (
            patch("app.llm.gateway.run_tool_loop", AsyncMock(return_value={"response": "ok", "tool_calls": []})),
            patch("app.llm.gateway.route_message", AsyncMock(return_value=None)),
            patch("app.llm.gateway.build_history", AsyncMock(return_value=[{"role": "user", "content": "Salut"}])),
        ):
            await gateway.chat("Salut", "user-1", exclude_message_id="msg-42")

        assert gateway._build_system_prompt.await_args.kwargs["exclude_message_id"] == "msg-42"

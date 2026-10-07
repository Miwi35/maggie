"""No internal thread label in what Maggie says (MAG-341).

On 7 Oct. she answered « [fil « Où noter l'information »] Ah, d'accord monsieur… ». The
label came from the history: the messages it borrows from other threads were all labelled,
her own answers included, and she took the pattern for the way she writes. The history now
labels the user's side only, and both chat paths take off a label copied anyway — before a
delta is shown, since a shown delta cannot be taken back.
"""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeUsage
from app.llm.gateway import LLMGateway
from app.llm.history import build_history, label_settled, strip_thread_label
from app.llm.streaming import StreamingGateway

COPIED = "[fil « Où noter l'information »] Ah, d'accord monsieur, je le note dans vos directives."
CLEAN = "Ah, d'accord monsieur, je le note dans vos directives."


class TestStripThreadLabel:
    @pytest.mark.parametrize(
        ("text", "expected"),
        [
            (COPIED, CLEAN),
            ("[autre fil] Bonjour", "Bonjour"),
            ("  [fil « A »] [autre fil]  Bonjour", "Bonjour"),
            ("Bonjour [fil « A »]", "Bonjour [fil « A »]"),
            ("[1] Première étape", "[1] Première étape"),
            ("", ""),
        ],
    )
    def test_only_a_leading_label_goes(self, text, expected):
        assert strip_thread_label(text) == expected


class TestLabelSettled:
    @pytest.mark.parametrize(
        ("text", "settled"),
        [
            ("", False),
            ("   ", False),
            ("Ah", True),
            ("[", False),
            ("[fil « Où noter", False),
            ("[fil « Où noter l'information »]", False),
            ("[fil « Où noter l'information »] Ah", True),
            ("[1] Première", True),
            ("[" + "x" * 200, True),
        ],
    )
    def test_the_head_is_held_until_it_is_known(self, text, settled):
        assert label_settled(text) is settled


class TestTheHistoryLabelsTheUserSideOnly:
    async def test_an_answer_from_another_thread_is_not_labelled(self, chat_db):
        left = await context_repo.create("user-1", "Où noter l'information")
        current = await context_repo.create("user-1", "Courses")
        await message_repo.create(user_id="user-1", role="user", content="Où l'as-tu noté ?", context_id=left.id)
        await message_repo.create(user_id="user-1", role="assistant", content="Dans mes notes.", context_id=left.id)
        asked = await message_repo.create(user_id="user-1", role="user", content="Mes courses", context_id=current.id)

        turns = await build_history("user-1", context_id=current.id, current_message_id=asked.id)

        assert turns[0]["content"] == "[fil « Où noter l'information »] Où l'as-tu noté ?"
        assert turns[1] == {"role": "assistant", "content": "Dans mes notes."}


def _streaming_gateway(answer: str) -> StreamingGateway:
    gw = StreamingGateway()
    gw.client = MagicMock()
    gw.client.messages.stream = lambda **_kwargs: FakeStream(
        FakeMessage(
            content=[FakeTextBlock(text=answer)],
            stop_reason="end_turn",
            usage=FakeUsage(input_tokens=10, output_tokens=5),
        )
    )
    gw.personality = MagicMock()
    gw.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gw.agent_memory = MagicMock()
    gw.agent_memory.get_memory_context = AsyncMock(return_value="")
    gw.tool_router = MagicMock()
    gw.tool_router.get_tool_definitions = AsyncMock(return_value=[])
    gw._resolve_context = AsyncMock(
        return_value={"action": "matched", "id": "ctx-1", "label": "Courses", "status": "active", "summary": None}
    )
    return gw


async def _stream(answer: str) -> tuple[list[dict], AsyncMock]:
    with (
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.llm.streaming.message_repo") as messages,
        patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "Où ?"}])),
        patch("app.llm.streaming.skill_index") as skills,
        patch("app.llm.streaming.context_summarizer") as summarizer,
    ):
        contexts.find_active = AsyncMock(return_value=[])
        skills.get_skills_index.return_value = ""
        messages.create = AsyncMock()
        summarizer.maybe_summarize = AsyncMock(return_value=None)
        gw = _streaming_gateway(answer)
        events = [event async for event in gw.chat_stream("Où ?", "user-1", "msg-1")]
        await asyncio.gather(*gw._background)
    return events, messages.create


def _shown(events: list[dict]) -> str:
    return "".join(e["delta"] for e in events if e["type"] == "TEXT_MESSAGE_CONTENT")


class TestTheStreamedAnswerCarriesNoLabel:
    async def test_a_copied_label_is_neither_shown_nor_stored(self):
        events, create = await _stream(COPIED)

        assert _shown(events) == CLEAN
        assert create.await_args.kwargs["content"] == CLEAN

    async def test_an_answer_opening_on_a_bracket_is_left_alone(self):
        events, create = await _stream("[1] Sortir les poubelles, [2] arroser.")

        assert _shown(events) == "[1] Sortir les poubelles, [2] arroser."
        assert create.await_args.kwargs["content"] == "[1] Sortir les poubelles, [2] arroser."

    async def test_a_head_that_never_settles_is_still_shown_at_the_end(self):
        events, create = await _stream("[voir plus haut")

        assert _shown(events) == "[voir plus haut"
        assert create.await_args.kwargs["content"] == "[voir plus haut"
        assert [e["type"] for e in events].count("TEXT_MESSAGE_START") == 1
        assert [e["type"] for e in events].count("TEXT_MESSAGE_END") == 1


class TestThePlainChatCarriesNoLabel:
    async def test_a_copied_label_is_not_returned(self):
        gateway = LLMGateway()
        gateway.client = MagicMock()
        gateway.personality = MagicMock()
        gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
        gateway.agent_memory = MagicMock()
        gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
        gateway.tool_router = MagicMock()
        gateway.tool_router.get_tool_definitions = AsyncMock(return_value=[])

        with (
            patch("app.llm.gateway.run_tool_loop", AsyncMock(return_value={"response": COPIED, "tool_calls": []})),
            patch("app.llm.gateway.route_message", AsyncMock(return_value=None)),
            patch("app.llm.gateway.build_history", AsyncMock(return_value=[{"role": "user", "content": "Où ?"}])),
            patch("app.llm.gateway.skill_index", MagicMock(get_skills_index=MagicMock(return_value=""))),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[]))),
        ):
            result = await gateway.chat("Où ?", "user-1", exclude_message_id="msg-1")

        assert result["response"] == CLEAN

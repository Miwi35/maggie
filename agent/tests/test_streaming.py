"""Tests for StreamingGateway AG-UI event emission."""

import asyncio
from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.db.context_model import ContextStatus
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeUsage
from app.llm.streaming import StreamingGateway


@pytest.fixture()
def gateway():
    """Create a StreamingGateway with mocked dependencies."""
    with (
        patch("app.llm.streaming.settings") as mock_settings,
        patch("app.llm.streaming.message_repo"),
        patch("app.llm.streaming.context_repo"),
    ):
        mock_settings.anthropic_api_key = "test-key"
        mock_settings.anthropic_model = "claude-test"
        mock_settings.max_conversation_history = 10
        gw = StreamingGateway()
        gw.client = MagicMock()
        gw.personality = MagicMock()
        gw.tool_router = MagicMock()
        gw.agent_memory = MagicMock()
        yield gw


class TestStreamingGateway:
    """Test StreamingGateway event emission."""

    @pytest.mark.asyncio
    async def test_no_client_returns_error(self):
        """When no API key is configured, emit error text."""
        with patch("app.llm.streaming.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            gw = StreamingGateway()
            assert gw.client is None

            events = []
            async for event in gw.chat_stream("hello", "user-1", "msg-1"):
                events.append(event)

            types = [e["type"] for e in events]
            assert types[0] == "RUN_STARTED"
            assert "TEXT_MESSAGE_START" in types
            assert "TEXT_MESSAGE_CONTENT" in types
            assert "TEXT_MESSAGE_END" in types
            assert types[-1] == "RUN_FINISHED"

    @pytest.mark.asyncio
    async def test_resolve_context_creates_new(self):
        """When no contexts exist, creates a new one."""
        with (
            patch("app.llm.streaming.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            mock_repo.find_active = AsyncMock(return_value=[])
            ctx = MagicMock()
            ctx.id = "ctx-new"
            ctx.label = "Liste de courses"
            ctx.status = MagicMock()
            ctx.status.value = "active"
            mock_repo.create = AsyncMock(return_value=ctx)
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            # Mock the Anthropic client response
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": null, "label": "Liste de courses"}')]
            response.usage.input_tokens = 50
            response.usage.output_tokens = 20
            gw.client = MagicMock()
            gw.client.messages = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Qu'est-ce que j'ai sur ma liste ?", "user-1", "msg-1")

            assert result["action"] == "created"
            assert result["label"] == "Liste de courses"
            mock_repo.create.assert_called_once_with("user-1", "Liste de courses")

    @pytest.mark.asyncio
    async def test_resolve_context_matches_existing(self):
        """When an existing context matches, returns it."""
        with (
            patch("app.llm.streaming.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            existing = MagicMock()
            existing.id = "ctx-123"
            existing.label = "Tâches urgentes"
            existing.status = MagicMock()
            existing.status.value = "active"
            existing.updated_at = datetime.now(UTC)
            mock_repo.find_active = AsyncMock(return_value=[existing])
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": "ctx-123"}')]
            response.usage.input_tokens = 80
            response.usage.output_tokens = 15
            gw.client = MagicMock()
            gw.client.messages = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Marque la première comme faite", "user-1", "msg-2")

            assert result["action"] == "matched"
            assert result["id"] == "ctx-123"
            mock_repo.create.assert_not_called()

    @pytest.mark.asyncio
    async def test_resolve_context_returns_none_on_error(self):
        """On LLM error, returns None gracefully."""
        with patch("app.llm.streaming.context_repo") as mock_repo:
            mock_repo.find_active = AsyncMock(return_value=[])

            gw = StreamingGateway()
            gw.client = MagicMock()
            gw.client.messages = MagicMock()
            gw.client.messages.create = AsyncMock(side_effect=Exception("API error"))

            result = await gw._resolve_context("test", "user-1", "msg-1")
            assert result is None

    @pytest.mark.asyncio
    async def test_resolve_context_no_client(self):
        """When no client configured, returns None."""
        gw = StreamingGateway()
        gw.client = None
        result = await gw._resolve_context("test", "user-1", "msg-1")
        assert result is None

    @pytest.mark.asyncio
    async def test_resolve_context_carries_the_summary(self):
        """The Mind panel replaces the whole context on this event — a missing summary erases it (MAG-11)."""
        with (
            patch("app.llm.streaming.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            existing = MagicMock()
            existing.id = "ctx-123"
            existing.label = "Courses de la semaine"
            existing.summary = "L'utilisateur prépare ses courses."
            existing.updated_at = datetime.now(UTC)
            mock_repo.find_active = AsyncMock(return_value=[existing])
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": "ctx-123"}')]
            response.usage.input_tokens = 80
            response.usage.output_tokens = 15
            gw.client = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Et aussi du beurre", "user-1", "msg-2")

            assert result["summary"] == "L'utilisateur prépare ses courses."

    @pytest.mark.asyncio
    async def test_resolve_context_new_has_no_summary_yet(self):
        with (
            patch("app.llm.streaming.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            mock_repo.find_active = AsyncMock(return_value=[])
            ctx = MagicMock()
            ctx.id = "ctx-new"
            mock_repo.create = AsyncMock(return_value=ctx)
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": null, "label": "Budget"}')]
            response.usage.input_tokens = 50
            response.usage.output_tokens = 20
            gw.client = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Où en est mon budget ?", "user-1", "msg-1")

            assert result["summary"] is None


def _context(label: str, summary: str | None = None, status: ContextStatus = ContextStatus.ACTIVE) -> MagicMock:
    ctx = MagicMock()
    ctx.label = label
    ctx.summary = summary
    ctx.status = status
    return ctx


async def _system_blocks(contexts: list) -> list[dict]:
    """The system blocks the gateway would send, with everything but the contexts emptied."""
    with (
        patch("app.llm.streaming.context_repo") as repo,
        patch("app.llm.streaming.skill_index") as skills,
        patch("app.llm.streaming.behavior_directives_section", AsyncMock(return_value="")),
    ):
        repo.find_active = AsyncMock(return_value=contexts)
        skills.get_skills_index.return_value = ""
        gw = StreamingGateway()
        gw.personality = MagicMock()
        gw.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
        gw.agent_memory = MagicMock()
        gw.agent_memory.get_memory_context = AsyncMock(return_value="")
        return await gw._build_system_prompt("user-1")


class TestSystemPromptSummaries:
    """What a thread leaves behind once it has scrolled past the raw history (MAG-11)."""

    async def test_an_open_thread_carries_its_summary(self):
        blocks = await _system_blocks([_context("Courses de la semaine", "Deux kilos de farine à acheter.")])

        volatile = blocks[1]["text"]
        assert "Courses de la semaine" in volatile
        assert "Résumé : Deux kilos de farine à acheter." in volatile

    async def test_a_thread_without_one_is_just_its_label(self):
        blocks = await _system_blocks([_context("Budget")])

        assert "Budget" in blocks[1]["text"]
        assert "Résumé" not in blocks[1]["text"]

    async def test_the_summary_stays_out_of_the_cached_prefix(self):
        """A summary changes while the personality and the skill index do not — caching it would waste the prefix."""
        blocks = await _system_blocks([_context("Courses", "Deux kilos de farine.")])

        assert blocks[0]["cache_control"] == {"type": "ephemeral"}
        assert "Deux kilos de farine." not in blocks[0]["text"]

    async def test_only_the_most_recent_threads_carry_one(self):
        """Every thread keeps its label; the tokens are capped on the summaries alone."""
        contexts = [_context(f"Fil {rank}", f"Résumé du fil {rank}.") for rank in range(7)]

        volatile = (await _system_blocks(contexts))[1]["text"]

        assert "Fil 6" in volatile  # the label of the oldest open thread is still there
        assert "Résumé du fil 4." in volatile
        assert "Résumé du fil 5." not in volatile


class TestSummaryTrigger:
    """The summary is spawned after the exchange is stored, and the run does not wait for it."""

    @staticmethod
    def _gateway():
        gw = StreamingGateway()
        gw.client = MagicMock()
        gw.client.messages.stream = lambda **_kwargs: FakeStream(
            FakeMessage(
                content=[FakeTextBlock(text="C'est noté.")],
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

    async def test_the_run_finishes_without_waiting_for_the_summary(self):
        running = asyncio.Event()
        release = asyncio.Event()

        async def never_finishes(context_id: str) -> None:
            running.set()
            await release.wait()

        with (
            patch("app.llm.streaming.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            messages.find_recent = AsyncMock(return_value=[])
            messages.create = AsyncMock()
            summarizer.maybe_summarize = never_finishes

            gw = self._gateway()
            events = [event async for event in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1")]

            # The stream is over while the summary is still mid-flight: that is the
            # whole point of spawning it, since nobody is waiting for the result.
            assert events[-1]["type"] == "RUN_FINISHED"
            await asyncio.sleep(0)
            assert running.is_set()

            release.set()
            await asyncio.gather(*gw._background)

    async def test_it_summarizes_the_thread_the_exchange_landed_in(self):
        with (
            patch("app.llm.streaming.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            messages.find_recent = AsyncMock(return_value=[])
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = self._gateway()
            async for _ in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1"):
                pass
            await asyncio.gather(*gw._background)

            summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")

    async def test_a_run_with_no_context_summarizes_nothing(self):
        with (
            patch("app.llm.streaming.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            messages.find_recent = AsyncMock(return_value=[])
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = self._gateway()
            gw._resolve_context = AsyncMock(return_value=None)
            async for _ in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1"):
                pass

            assert gw._background == set()
            summarizer.maybe_summarize.assert_not_awaited()

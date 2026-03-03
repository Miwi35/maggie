"""Tests for StreamingGateway AG-UI event emission."""

from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

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

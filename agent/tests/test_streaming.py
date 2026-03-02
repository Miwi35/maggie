"""Tests for StreamingGateway AG-UI event emission."""

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
    async def test_handle_manage_context_create(self):
        """Test creating a new context via manage_context."""
        with patch("app.llm.streaming.context_repo") as mock_repo:
            ctx = MagicMock()
            ctx.id = "ctx-123"
            ctx.label = "Shopping list"
            ctx.status = MagicMock()
            ctx.status.value = "active"
            mock_repo.create = AsyncMock(return_value=ctx)

            gw = StreamingGateway()
            result = await gw._handle_manage_context(
                {"action": "create", "label": "Shopping list"}, "user-1"
            )

            assert result["id"] == "ctx-123"
            assert result["action"] == "created"
            mock_repo.create.assert_called_once_with("user-1", "Shopping list")

    @pytest.mark.asyncio
    async def test_handle_manage_context_close(self):
        """Test closing a context."""
        with patch("app.llm.streaming.context_repo") as mock_repo:
            ctx = MagicMock()
            ctx.id = "ctx-123"
            ctx.label = "Done topic"
            ctx.status = MagicMock()
            ctx.status.value = "closed"
            mock_repo.update_status = AsyncMock(return_value=ctx)

            gw = StreamingGateway()
            result = await gw._handle_manage_context(
                {"action": "close", "context_id": "ctx-123"}, "user-1"
            )

            assert result["id"] == "ctx-123"
            assert result["action"] == "closed"

    @pytest.mark.asyncio
    async def test_handle_manage_context_switch(self):
        """Test switching to a dormant context."""
        with patch("app.llm.streaming.context_repo") as mock_repo:
            ctx = MagicMock()
            ctx.id = "ctx-456"
            ctx.label = "Old topic"
            ctx.status = MagicMock()
            ctx.status.value = "active"
            mock_repo.update_status = AsyncMock(return_value=ctx)

            gw = StreamingGateway()
            result = await gw._handle_manage_context(
                {"action": "switch", "context_id": "ctx-456"}, "user-1"
            )

            assert result["id"] == "ctx-456"
            assert result["action"] == "switched"

    @pytest.mark.asyncio
    async def test_handle_manage_context_create_requires_label(self):
        """Create without label returns error."""
        gw = StreamingGateway()
        result = await gw._handle_manage_context({"action": "create"}, "user-1")
        assert "error" in result

    @pytest.mark.asyncio
    async def test_handle_manage_context_close_requires_id(self):
        """Close without context_id returns error."""
        gw = StreamingGateway()
        result = await gw._handle_manage_context({"action": "close"}, "user-1")
        assert "error" in result

    @pytest.mark.asyncio
    async def test_handle_manage_context_unknown_action(self):
        """Unknown action returns error."""
        gw = StreamingGateway()
        result = await gw._handle_manage_context({"action": "unknown"}, "user-1")
        assert "error" in result

    @pytest.mark.asyncio
    async def test_handle_manage_context_not_found(self):
        """Closing a non-existent context returns error."""
        with patch("app.llm.streaming.context_repo") as mock_repo:
            mock_repo.update_status = AsyncMock(return_value=None)

            gw = StreamingGateway()
            result = await gw._handle_manage_context(
                {"action": "close", "context_id": "nonexistent"}, "user-1"
            )

            assert "error" in result

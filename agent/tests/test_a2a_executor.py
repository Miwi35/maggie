from unittest.mock import AsyncMock, MagicMock, patch

from app.a2a.executor import MaggieAgentExecutor


class TestMaggieAgentExecutor:
    @patch.object(MaggieAgentExecutor, "__init__", lambda self: None)
    async def test_execute_calls_gateway_chat(self):
        """Executor should bridge A2A request to LLMGateway.chat()."""
        executor = MaggieAgentExecutor()
        executor.gateway = MagicMock()
        executor.gateway.chat = AsyncMock(return_value={
            "response": "Hello from Maggie!",
            "tool_calls": [],
        })

        # Mock context with a text message
        context = MagicMock()
        part = MagicMock()
        part.root.text = "What's on my calendar?"
        context.message.parts = [part]

        event_queue = MagicMock()
        event_queue.enqueue_event = AsyncMock()

        await executor.execute(context, event_queue)

        executor.gateway.chat.assert_awaited_once_with("What's on my calendar?", "a2a")
        event_queue.enqueue_event.assert_awaited_once()

    @patch.object(MaggieAgentExecutor, "__init__", lambda self: None)
    async def test_execute_with_no_message(self):
        """Executor should return error message if no text found in request."""
        executor = MaggieAgentExecutor()
        executor.gateway = MagicMock()

        context = MagicMock()
        context.message.parts = []

        event_queue = MagicMock()
        event_queue.enqueue_event = AsyncMock()

        await executor.execute(context, event_queue)

        event_queue.enqueue_event.assert_awaited_once()
        # Gateway should NOT be called
        executor.gateway.chat = AsyncMock()
        executor.gateway.chat.assert_not_awaited()

    @patch.object(MaggieAgentExecutor, "__init__", lambda self: None)
    async def test_cancel_raises(self):
        """Cancel should raise an exception (not supported)."""
        executor = MaggieAgentExecutor()

        import pytest

        with pytest.raises(Exception, match="cancel not supported"):
            await executor.cancel(MagicMock(), MagicMock())

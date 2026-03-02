from datetime import UTC
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.db.proaction_model import ProactionStatus


class TestProactionModel:
    def test_proaction_status_values(self):
        """ProactionStatus enum should have the expected values."""
        assert ProactionStatus.PENDING == "pending"
        assert ProactionStatus.RUNNING == "running"
        assert ProactionStatus.COMPLETED == "completed"
        assert ProactionStatus.FAILED == "failed"

    def test_proaction_to_dict(self):
        """Proaction.to_dict() should serialize correctly."""
        from datetime import datetime

        from app.db.proaction_model import Proaction

        proaction = Proaction(
            id="abc123",
            user_id="user-1",
            prompt="Check calendar",
            status=ProactionStatus.PENDING,
            scheduled_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
            created_at=datetime(2026, 2, 19, 10, 0, tzinfo=UTC),
        )

        d = proaction.to_dict()
        assert d["id"] == "abc123"
        assert d["userId"] == "user-1"
        assert d["prompt"] == "Check calendar"
        assert d["status"] == "pending"
        assert d["scheduledAt"] == "2026-02-20T09:00:00+00:00"
        assert d["response"] is None
        assert d["error"] is None
        assert d["completedAt"] is None


class TestProactionRouteAuth:
    def test_proactions_endpoint_requires_auth(self, client):
        """GET /proactions without auth returns 401 or 403."""
        response = client.get("/proactions")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.proaction_repo")
    def test_proactions_endpoint_with_auth(self, mock_repo, authed_client):
        """GET /proactions with valid auth returns proactions list."""
        mock_proaction = MagicMock()
        mock_proaction.to_dict.return_value = {
            "id": "abc123",
            "userId": "test-user",
            "prompt": "Check calendar",
            "status": "pending",
        }
        mock_repo.find_by_user = AsyncMock(return_value=[mock_proaction])

        response = authed_client.get("/proactions")

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["id"] == "abc123"


class TestProactionConsumer:
    """Tests for proaction_consumer message delivery."""

    @pytest.mark.asyncio
    @patch("app.queue.proaction_consumer.message_repo")
    @patch("app.queue.proaction_consumer.proaction_repo")
    @patch("app.queue.proaction_consumer.LLMGateway")
    async def test_consumer_creates_chat_message(self, mock_gateway_cls, mock_repo, mock_msg_repo):
        """Consumer should create a chat message from the proaction response."""
        from app.queue.proaction_consumer import start_consumer

        # Set up mocks
        proaction = MagicMock()
        proaction.id = "pro-1"
        proaction.user_id = "user-1"
        proaction.prompt = "Rappelle à l'utilisateur d'appeler le plombier."

        mock_repo.get = AsyncMock(return_value=proaction)
        mock_repo.mark_running = AsyncMock()
        mock_repo.mark_completed = AsyncMock()

        gateway_instance = MagicMock()
        gateway_instance.proaction = AsyncMock(
            return_value={"response": "C'est l'heure d'appeler le plombier !", "tool_calls": []}
        )
        mock_gateway_cls.return_value = gateway_instance

        mock_msg_repo.create = AsyncMock()

        # Simulate the on_message callback
        mock_message = MagicMock()
        mock_message.body = b"pro-1"
        mock_message.process = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(), __aexit__=AsyncMock()))

        # Get access to on_message by capturing it from queue.consume
        mock_channel = MagicMock()
        mock_queue = MagicMock()
        mock_queue.consume = AsyncMock()

        captured_callback = None

        async def capture_consume(callback):
            nonlocal captured_callback
            captured_callback = callback

        mock_queue.consume = capture_consume

        with patch("app.queue.proaction_consumer.get_channel", return_value=mock_channel):
            mock_channel.declare_queue = AsyncMock(return_value=mock_queue)
            await start_consumer()

        # Now call the captured callback
        assert captured_callback is not None
        await captured_callback(mock_message)

        # Verify gateway.proaction called without silent (defaults to False)
        gateway_instance.proaction.assert_called_once_with(proaction.prompt, proaction.user_id)

        # Verify message_repo.create was called with the response
        mock_msg_repo.create.assert_called_once_with(
            user_id="user-1",
            role="assistant",
            content="C'est l'heure d'appeler le plombier !",
        )

    @pytest.mark.asyncio
    @patch("app.queue.proaction_consumer.message_repo")
    @patch("app.queue.proaction_consumer.proaction_repo")
    @patch("app.queue.proaction_consumer.LLMGateway")
    async def test_consumer_skips_empty_response(self, mock_gateway_cls, mock_repo, mock_msg_repo):
        """Consumer should NOT create a chat message when response is empty."""
        from app.queue.proaction_consumer import start_consumer

        proaction = MagicMock()
        proaction.id = "pro-2"
        proaction.user_id = "user-1"
        proaction.prompt = "Some task"

        mock_repo.get = AsyncMock(return_value=proaction)
        mock_repo.mark_running = AsyncMock()
        mock_repo.mark_completed = AsyncMock()

        gateway_instance = MagicMock()
        gateway_instance.proaction = AsyncMock(return_value={"response": "", "tool_calls": []})
        mock_gateway_cls.return_value = gateway_instance

        mock_msg_repo.create = AsyncMock()

        mock_message = MagicMock()
        mock_message.body = b"pro-2"
        mock_message.process = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(), __aexit__=AsyncMock()))

        captured_callback = None

        async def capture_consume(callback):
            nonlocal captured_callback
            captured_callback = callback

        mock_channel = MagicMock()
        mock_queue = MagicMock()
        mock_queue.consume = capture_consume

        with patch("app.queue.proaction_consumer.get_channel", return_value=mock_channel):
            mock_channel.declare_queue = AsyncMock(return_value=mock_queue)
            await start_consumer()

        await captured_callback(mock_message)

        # message_repo.create should NOT be called for empty response
        mock_msg_repo.create.assert_not_called()

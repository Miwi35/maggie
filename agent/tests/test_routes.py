from unittest.mock import AsyncMock, patch

from app.db.models import Message


class TestRoutes:
    def test_health_endpoint(self, client):
        """GET /health returns status ok and service name."""
        response = client.get("/health")

        assert response.status_code == 200

        data = response.json()
        assert data["status"] == "ok"
        assert data["service"] == "maggie-agent-hub"

    def test_chat_endpoint_requires_auth(self, client):
        """POST /chat without auth returns 401."""
        response = client.post("/chat", json={"message": "Hello"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.llm_gateway")
    def test_chat_endpoint_without_api_key(self, mock_gateway, mock_msg_repo, authed_client):
        """POST /chat without ANTHROPIC_API_KEY returns a not-configured message."""
        mock_gateway.client = None

        async def mock_chat(message, user_id):
            return {"response": "AI service is not configured.", "tool_calls": []}

        mock_gateway.chat = mock_chat

        fake_msg = Message(id="test", user_id="test-user", role="user", content="Hello")
        mock_msg_repo.create = AsyncMock(return_value=fake_msg)

        response = authed_client.post(
            "/chat",
            json={"message": "Hello"},
        )

        assert response.status_code == 200

        data = response.json()
        assert "not configured" in data["response"].lower()
        assert data["tool_calls"] == []

    def test_proactions_endpoint_requires_auth(self, client):
        """GET /proactions without auth returns 401."""
        response = client.get("/proactions")
        assert response.status_code in (401, 403)

    def test_messages_endpoint_requires_auth(self, client):
        """GET /messages without auth returns 401."""
        response = client.get("/messages")
        assert response.status_code in (401, 403)

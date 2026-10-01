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

        async def mock_chat(message, user_id, *, exclude_message_id=None):
            assert exclude_message_id == "test"
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

    @patch("app.api.routes.message_repo")
    def test_messages_with_before_param(self, mock_msg_repo, authed_client):
        """GET /messages?before=<id> calls find_before and returns messages."""
        fake_msg = Message(id="msg-1", user_id="test-user", role="user", content="Older message")
        mock_msg_repo.find_before = AsyncMock(return_value=[fake_msg])

        response = authed_client.get("/messages", params={"before": "msg-2"})

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["id"] == "msg-1"
        mock_msg_repo.find_before.assert_called_once_with("test-user", before_id="msg-2", limit=20)

    @patch("app.api.routes.message_repo")
    def test_messages_with_limit_param(self, mock_msg_repo, authed_client):
        """GET /messages?limit=5 passes custom limit."""
        mock_msg_repo.find_recent = AsyncMock(return_value=[])

        response = authed_client.get("/messages", params={"limit": 5})

        assert response.status_code == 200
        mock_msg_repo.find_recent.assert_called_once_with("test-user", limit=5)

    def test_messages_search_requires_auth(self, client):
        """GET /messages/search without auth returns 401."""
        response = client.get("/messages/search", params={"q": "hello"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    def test_messages_search(self, mock_msg_repo, authed_client):
        """GET /messages/search?q=hello returns matching messages."""
        fake_msg = Message(id="msg-1", user_id="test-user", role="assistant", content="Hello there!")
        mock_msg_repo.search = AsyncMock(return_value=[fake_msg])

        response = authed_client.get("/messages/search", params={"q": "hello"})

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["content"] == "Hello there!"
        mock_msg_repo.search.assert_called_once_with("test-user", "hello", limit=20)

    def test_messages_search_without_query_returns_422(self, authed_client):
        """GET /messages/search without q param returns 422."""
        response = authed_client.get("/messages/search")
        assert response.status_code == 422

    def test_messages_context_requires_auth(self, client):
        """GET /messages/context without auth returns 401."""
        response = client.get("/messages/context", params={"around": "msg-1"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.message_repo")
    def test_messages_context(self, mock_msg_repo, authed_client):
        """GET /messages/context?around=<id> returns messages with targetIndex."""
        fake_msgs = [
            Message(id="msg-0", user_id="test-user", role="user", content="Before"),
            Message(id="msg-1", user_id="test-user", role="assistant", content="Target"),
            Message(id="msg-2", user_id="test-user", role="user", content="After"),
        ]
        mock_msg_repo.find_around = AsyncMock(return_value={"messages": fake_msgs, "targetIndex": 1})

        response = authed_client.get("/messages/context", params={"around": "msg-1"})

        assert response.status_code == 200
        data = response.json()
        assert len(data["messages"]) == 3
        assert data["targetIndex"] == 1
        assert data["messages"][1]["id"] == "msg-1"

    def test_messages_context_without_around_returns_422(self, authed_client):
        """GET /messages/context without around param returns 422."""
        response = authed_client.get("/messages/context")
        assert response.status_code == 422

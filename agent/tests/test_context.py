"""Tests for ConversationContext model, repository, and routes."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.auth import get_current_user_id
from app.db.context_model import ContextStatus, ConversationContext


class TestContextModel:
    """Test ConversationContext model."""

    def test_context_status_values(self):
        assert ContextStatus.ACTIVE == "active"
        assert ContextStatus.DORMANT == "dormant"
        assert ContextStatus.CLOSED == "closed"

    def test_to_dict(self):
        ctx = ConversationContext(
            id="abc123",
            user_id="user-1",
            label="Liste de courses",
            status=ContextStatus.ACTIVE,
            tool_calls_log=[{"name": "add_item", "status": "success"}],
        )
        d = ctx.to_dict()
        assert d["id"] == "abc123"
        assert d["userId"] == "user-1"
        assert d["label"] == "Liste de courses"
        assert d["status"] == "active"
        assert d["toolCallsLog"] == [{"name": "add_item", "status": "success"}]

    def test_to_dict_closed(self):
        ctx = ConversationContext(
            id="abc123",
            user_id="user-1",
            label="Done topic",
            status=ContextStatus.CLOSED,
            tool_calls_log=[],
        )
        d = ctx.to_dict()
        assert d["status"] == "closed"


class TestContextRouteAuth:
    """Test context routes require authentication."""

    @pytest.fixture()
    def client(self):
        test_app = FastAPI()
        test_app.include_router(router)
        with TestClient(test_app) as c:
            yield c

    @pytest.fixture()
    def authed_client(self):
        test_app = FastAPI()
        test_app.include_router(router)
        test_app.dependency_overrides[get_current_user_id] = lambda: "test-user"
        with TestClient(test_app) as c:
            yield c

    def test_contexts_requires_auth(self, client):
        response = client.get("/contexts")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.context_repo")
    def test_contexts_returns_list(self, mock_repo, authed_client):
        ctx = MagicMock()
        ctx.to_dict.return_value = {
            "id": "ctx-1",
            "userId": "test-user",
            "label": "Shopping",
            "status": "active",
            "toolCallsLog": [],
            "createdAt": "2026-01-01T00:00:00+00:00",
            "updatedAt": "2026-01-01T00:00:00+00:00",
            "closedAt": None,
        }
        mock_repo.find_active = AsyncMock(return_value=[ctx])

        response = authed_client.get("/contexts")
        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["label"] == "Shopping"
        mock_repo.find_active.assert_called_once_with("test-user")

    @patch("app.api.routes.context_repo")
    def test_contexts_empty(self, mock_repo, authed_client):
        mock_repo.find_active = AsyncMock(return_value=[])

        response = authed_client.get("/contexts")
        assert response.status_code == 200
        assert response.json() == []


class TestStreamRoute:
    """Test the streaming chat route."""

    @pytest.fixture()
    def client(self):
        test_app = FastAPI()
        test_app.include_router(router)
        with TestClient(test_app) as c:
            yield c

    @pytest.fixture()
    def authed_client(self):
        test_app = FastAPI()
        test_app.include_router(router)
        test_app.dependency_overrides[get_current_user_id] = lambda: "test-user"
        with TestClient(test_app) as c:
            yield c

    def test_stream_requires_auth(self, client):
        response = client.post("/chat/stream", json={"message": "test"})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.streaming_gateway")
    @patch("app.api.routes.message_repo")
    def test_stream_returns_sse(self, mock_msg_repo, mock_gateway, authed_client):
        fake_msg = MagicMock()
        fake_msg.id = "msg-1"
        mock_msg_repo.create = AsyncMock(return_value=fake_msg)

        async def fake_stream(message, user_id, user_msg_id):
            yield {"type": "RUN_STARTED", "runId": "run-1"}
            yield {"type": "TEXT_MESSAGE_START", "messageId": "m1", "role": "assistant"}
            yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": "m1", "delta": "Hello!"}
            yield {"type": "TEXT_MESSAGE_END", "messageId": "m1"}
            yield {"type": "RUN_FINISHED", "runId": "run-1"}

        mock_gateway.chat_stream = fake_stream

        response = authed_client.post("/chat/stream", json={"message": "Hi"})
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")

        # Parse SSE data
        lines = response.text.strip().split("\n\n")
        events = []
        for line in lines:
            if line.startswith("data: "):
                import json

                events.append(json.loads(line[6:]))

        assert len(events) == 5
        assert events[0]["type"] == "RUN_STARTED"
        assert events[1]["type"] == "TEXT_MESSAGE_START"
        assert events[2]["type"] == "TEXT_MESSAGE_CONTENT"
        assert events[2]["delta"] == "Hello!"
        assert events[3]["type"] == "TEXT_MESSAGE_END"
        assert events[4]["type"] == "RUN_FINISHED"

    @patch("app.api.routes.streaming_gateway")
    @patch("app.api.routes.message_repo")
    def test_stream_creates_user_message(self, mock_msg_repo, mock_gateway, authed_client):
        fake_msg = MagicMock()
        fake_msg.id = "msg-1"
        mock_msg_repo.create = AsyncMock(return_value=fake_msg)

        async def fake_stream(message, user_id, user_msg_id):
            yield {"type": "RUN_STARTED", "runId": "run-1"}
            yield {"type": "RUN_FINISHED", "runId": "run-1"}

        mock_gateway.chat_stream = fake_stream

        authed_client.post("/chat/stream", json={"message": "Test message"})
        mock_msg_repo.create.assert_called_once_with(
            user_id="test-user", role="user", content="Test message"
        )

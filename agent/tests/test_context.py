"""Tests for ConversationContext model, repository, and routes."""

from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.auth import get_current_user_id
from app.db.context_model import ContextStatus, ConversationContext
from app.db.context_repository import context_repo


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

    def test_to_dict_carries_the_summary(self):
        """What the admin reads off `GET /contexts` and off the Mercure payload (MAG-11)."""
        written_at = datetime(2026, 10, 1, 9, 30, tzinfo=UTC)
        ctx = ConversationContext(
            id="abc123",
            user_id="user-1",
            label="Courses de la semaine",
            status=ContextStatus.ACTIVE,
            tool_calls_log=[],
            summary="L'utilisateur prépare ses courses.",
            summary_updated_at=written_at,
        )
        d = ctx.to_dict()
        assert d["summary"] == "L'utilisateur prépare ses courses."
        assert d["summaryUpdatedAt"] == written_at.isoformat()

    def test_to_dict_without_a_summary(self):
        ctx = ConversationContext(id="abc123", user_id="user-1", label="Neuf", status=ContextStatus.ACTIVE)
        d = ctx.to_dict()
        assert d["summary"] is None
        assert d["summaryUpdatedAt"] is None


class TestContextRepositorySummary:
    """`set_summary` — the only writer of the two columns (MAG-11)."""

    async def test_stores_the_summary_and_stamps_it(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses de la semaine")

        updated = await context_repo.set_summary(str(ctx.id), "L'utilisateur prépare ses courses.")

        assert updated is not None
        stored = await context_repo.get(str(ctx.id))
        assert stored.summary == "L'utilisateur prépare ses courses."
        # The stamp is what the next pass reads messages from, so an unset one would
        # make every re-summary read the whole thread again.
        assert stored.summary_updated_at is not None

    async def test_publishes_the_context_so_an_open_panel_sees_it(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses de la semaine")
        chat_db.published.reset_mock()

        await context_repo.set_summary(str(ctx.id), "Deux kilos de farine à acheter.")

        # The summary is written after the stream closed: Mercure is the only way it
        # reaches a Mind panel that is already open.
        chat_db.published.assert_awaited_once()
        topic, payload = chat_db.published.await_args.args
        assert topic.endswith("user-1")
        assert payload["summary"] == "Deux kilos de farine à acheter."

    async def test_leaves_updated_at_alone(self, chat_db):
        """A summary is written *about* a thread, not *in* it — the router ranks on `updated_at`."""
        ctx = await context_repo.create("user-1", "Courses de la semaine")
        spoken_at = (await context_repo.get(str(ctx.id))).updated_at

        await context_repo.set_summary(str(ctx.id), "Un résumé.")

        assert (await context_repo.get(str(ctx.id))).updated_at == spoken_at

    async def test_unknown_context_writes_nothing(self, chat_db):
        assert await context_repo.set_summary("does-not-exist", "Un résumé.") is None
        chat_db.published.assert_not_awaited()

    async def test_a_mercure_failure_keeps_the_summary(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses de la semaine")
        chat_db.published.side_effect = RuntimeError("hub down")

        updated = await context_repo.set_summary(str(ctx.id), "Un résumé.")

        assert updated is not None
        assert (await context_repo.get(str(ctx.id))).summary == "Un résumé."

    async def test_run_migrations_adds_both_columns(self):
        """The agent database has no migration tool: a column only in the model never ships."""
        executed = []
        conn = AsyncMock()
        conn.execute.side_effect = lambda stmt: executed.append(str(stmt))
        begin = MagicMock()
        begin.__aenter__ = AsyncMock(return_value=conn)
        begin.__aexit__ = AsyncMock(return_value=False)

        with patch("app.db.context_repository.agent_engine") as engine:
            engine.begin.return_value = begin
            await context_repo.run_migrations()

        statements = " ".join(executed)
        assert "conversation_context ADD COLUMN IF NOT EXISTS summary TEXT" in statements
        assert "conversation_context ADD COLUMN IF NOT EXISTS summary_updated_at TIMESTAMPTZ" in statements
        # The column the method already carried, which this one must not displace.
        assert "agent_message ADD COLUMN IF NOT EXISTS context_id" in statements


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
            user_id="test-user", role="user", content="Test message", publish=False
        )

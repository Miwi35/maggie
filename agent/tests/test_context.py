"""Tests for ConversationContext model, repository, and routes."""

from datetime import UTC, datetime, timedelta
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

    async def test_stamps_the_last_message_the_summary_covers(self, chat_db):
        """Not "now": a message that arrives during the model call has not been summarized."""
        ctx = await context_repo.create("user-1", "Courses de la semaine")
        last_message_at = datetime(2026, 10, 1, 9, 30, tzinfo=UTC)

        await context_repo.set_summary(str(ctx.id), "Un résumé.", covers_up_to=last_message_at)

        stored = await context_repo.get(str(ctx.id))
        assert stored.summary_updated_at.replace(tzinfo=UTC) == last_message_at

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

    async def test_run_migrations_adds_every_column_it_carries(self):
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
        # MAG-22. Dropped here and the suite stays green, while the next deploy
        # boots against a production database without the column: every read of
        # `instruction` — the admin's list, `list_instructions`, the daily
        # planner's `find_user_ids` — raises UndefinedColumn.
        assert "instruction ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'planning'" in statements
        # MAG-211. Without it, every chat on an existing database raises UndefinedColumn
        # on the first `SELECT` of a message — the column is in the model, so it is in
        # every query the repository builds, read or write.
        assert "agent_message ADD COLUMN IF NOT EXISTS blocks JSONB" in statements


def _back_date(chat_db, context_id: str, **ago) -> None:
    """Make a context look idle for `ago` (a timedelta's keywords) — the only way to age a row in a test."""
    session = chat_db.session()
    ctx = session._session.get(ConversationContext, context_id)
    ctx.updated_at = datetime.now(UTC) - timedelta(**ago)
    session._session.commit()
    session._session.close()


class TestContextRepositoryLifecycle:
    """`find_idle`, `update_status` and `touch` — what MAG-12's lifecycle is made of."""

    async def test_find_idle_returns_the_quiet_contexts_of_every_user(self, chat_db):
        mine = await context_repo.create("user-1", "Courses")
        theirs = await context_repo.create("user-2", "Budget")
        fresh = await context_repo.create("user-1", "Récent")
        _back_date(chat_db, str(mine.id), hours=30)
        _back_date(chat_db, str(theirs.id), hours=48)

        idle = await context_repo.find_idle([ContextStatus.ACTIVE], datetime.now(UTC) - timedelta(hours=24))

        assert {str(c.id) for c in idle} == {str(mine.id), str(theirs.id)}
        assert str(fresh.id) not in {str(c.id) for c in idle}

    async def test_find_idle_only_returns_the_asked_statuses(self, chat_db):
        dormant = await context_repo.create("user-1", "Dormant")
        closed = await context_repo.create("user-1", "Clos")
        for ctx in (dormant, closed):
            _back_date(chat_db, str(ctx.id), days=20)
        await context_repo.update_status(str(dormant.id), ContextStatus.DORMANT)
        await context_repo.update_status(str(closed.id), ContextStatus.CLOSED)
        _back_date(chat_db, str(dormant.id), days=20)
        _back_date(chat_db, str(closed.id), days=20)

        idle = await context_repo.find_idle([ContextStatus.ACTIVE], datetime.now(UTC) - timedelta(hours=24))

        assert idle == []

    async def test_update_status_closing_stamps_closed_at_and_publishes(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses")
        chat_db.published.reset_mock()

        updated = await context_repo.update_status(str(ctx.id), ContextStatus.CLOSED)

        assert updated.status == ContextStatus.CLOSED
        assert updated.closed_at is not None
        chat_db.published.assert_awaited_once()
        assert chat_db.published.await_args.args[1]["status"] == "closed"

    async def test_update_status_leaves_updated_at_alone(self, chat_db):
        """A status change is not a message: the idle clock, the router's ranking and the panel read `updated_at`."""
        ctx = await context_repo.create("user-1", "Courses")
        _back_date(chat_db, str(ctx.id), hours=30)
        spoken_at = (await context_repo.get(str(ctx.id))).updated_at

        await context_repo.update_status(str(ctx.id), ContextStatus.DORMANT)

        assert (await context_repo.get(str(ctx.id))).updated_at == spoken_at

    async def test_update_status_refuses_a_context_spoken_in_since(self, chat_db):
        """The summary takes seconds: a message that lands meanwhile must not be put to sleep."""
        ctx = await context_repo.create("user-1", "Courses")
        chat_db.published.reset_mock()
        cutoff = datetime.now(UTC) - timedelta(hours=24)  # the context was spoken in after it

        assert await context_repo.update_status(str(ctx.id), ContextStatus.DORMANT, idle_before=cutoff) is None

        assert (await context_repo.get(str(ctx.id))).status == ContextStatus.ACTIVE
        chat_db.published.assert_not_awaited()

    async def test_update_status_accepts_a_context_still_idle(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses")
        _back_date(chat_db, str(ctx.id), hours=30)

        updated = await context_repo.update_status(
            str(ctx.id), ContextStatus.DORMANT, idle_before=datetime.now(UTC) - timedelta(hours=24)
        )

        assert updated.status == ContextStatus.DORMANT

    async def test_touch_moves_updated_at_without_publishing_an_active_context(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses")
        _back_date(chat_db, str(ctx.id), hours=5)
        before = (await context_repo.get(str(ctx.id))).updated_at
        chat_db.published.reset_mock()

        touched = await context_repo.touch(str(ctx.id))

        assert touched.status == ContextStatus.ACTIVE
        assert touched.updated_at > before
        # Nothing changed for the panel, which already got the `context_update` of the stream.
        chat_db.published.assert_not_awaited()

    async def test_touch_wakes_a_dormant_context_and_publishes_it(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses")
        await context_repo.update_status(str(ctx.id), ContextStatus.DORMANT)
        chat_db.published.reset_mock()

        touched = await context_repo.touch(str(ctx.id))

        assert touched.status == ContextStatus.ACTIVE
        assert (await context_repo.get(str(ctx.id))).status == ContextStatus.ACTIVE
        chat_db.published.assert_awaited_once()
        assert chat_db.published.await_args.args[1]["status"] == "active"

    async def test_touch_reopens_a_context_closed_in_the_meantime(self, chat_db):
        ctx = await context_repo.create("user-1", "Courses")
        await context_repo.update_status(str(ctx.id), ContextStatus.CLOSED)

        touched = await context_repo.touch(str(ctx.id))

        assert touched.status == ContextStatus.ACTIVE
        assert touched.closed_at is None

    async def test_touch_unknown_context_does_nothing(self, chat_db):
        assert await context_repo.touch("does-not-exist") is None
        chat_db.published.assert_not_awaited()


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

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.context_repo")
    def test_contexts_returns_list(self, mock_repo, mock_messages, authed_client):
        ctx = MagicMock()
        ctx.id = "ctx-1"
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
        mock_messages.count_by_user_contexts = AsyncMock(return_value={"ctx-1": 4})

        response = authed_client.get("/contexts")
        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["label"] == "Shopping"
        assert data[0]["messageCount"] == 4
        mock_repo.find_active.assert_called_once_with("test-user")

    @patch("app.api.routes.message_repo")
    @patch("app.api.routes.context_repo")
    def test_contexts_empty(self, mock_repo, mock_messages, authed_client):
        mock_repo.find_active = AsyncMock(return_value=[])
        mock_messages.count_by_user_contexts = AsyncMock(return_value={})

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

        async def fake_stream(message, user_id, user_msg_id, **_kwargs):
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

        async def fake_stream(message, user_id, user_msg_id, **_kwargs):
            yield {"type": "RUN_STARTED", "runId": "run-1"}
            yield {"type": "RUN_FINISHED", "runId": "run-1"}

        mock_gateway.chat_stream = fake_stream

        authed_client.post("/chat/stream", json={"message": "Test message"})
        mock_msg_repo.create.assert_called_once()
        stored = mock_msg_repo.create.call_args.kwargs
        assert (stored["user_id"], stored["role"], stored["content"]) == ("test-user", "user", "Test message")

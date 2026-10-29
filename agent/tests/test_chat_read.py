"""The shared chat read position (MAG-687): `GET /chat/unread`, `PUT /chat/read`."""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.routes import router
from app.auth import get_current_user_id
from app.db.chat_read_state_model import ChatReadState
from app.db.models import Message
from tests.conftest import _SyncSessionAsAsync


@pytest.fixture()
def read_db():
    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    Message.__table__.create(engine)
    ChatReadState.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with patch("app.db.chat_read_state_repository.agent_session", open_session):
        yield factory
    engine.dispose()


@pytest.fixture()
def published():
    with patch("app.api.routes.read_publisher.publish", new=AsyncMock()) as publish:
        yield publish


def _add(factory, message_id: str, role: str, minutes_ago: int, user_id: str = "test-user") -> None:
    with factory() as session:
        session.add(
            Message(
                id=message_id,
                user_id=user_id,
                role=role,
                content=message_id,
                created_at=datetime.now(UTC) - timedelta(minutes=minutes_ago),
            )
        )
        session.commit()


def test_unread_requires_auth(client):
    assert client.get("/chat/unread").status_code in (401, 403)


def test_read_requires_auth(client):
    assert client.put("/chat/read", json={"messageId": "m1"}).status_code in (401, 403)


def test_a_first_read_starts_at_the_latest_message(authed_client, read_db):
    _add(read_db, "m1", "assistant", 30)
    _add(read_db, "m2", "assistant", 20)

    assert authed_client.get("/chat/unread").json() == {"lastReadMessageId": "m2", "unreadCount": 0}

    _add(read_db, "m3", "assistant", 10)
    _add(read_db, "m4", "user", 5)
    _add(read_db, "m5", "assistant", 1)
    assert authed_client.get("/chat/unread").json() == {"lastReadMessageId": "m2", "unreadCount": 2}


def test_a_user_without_messages_has_no_position(authed_client, read_db):
    assert authed_client.get("/chat/unread").json() == {"lastReadMessageId": None, "unreadCount": 0}


def test_reading_moves_the_position_and_tells_the_other_screens(authed_client, read_db, published):
    _add(read_db, "m1", "assistant", 30)
    authed_client.get("/chat/unread")
    _add(read_db, "m2", "assistant", 20)
    _add(read_db, "m3", "assistant", 10)

    response = authed_client.put("/chat/read", json={"messageId": "m2"})

    assert response.status_code == 200
    assert response.json() == {"lastReadMessageId": "m2", "unreadCount": 1}
    published.assert_awaited_once_with("/chat-read/test-user", {"lastReadMessageId": "m2", "unreadCount": 1})


def test_an_older_message_never_moves_the_position_back(authed_client, read_db, published):
    _add(read_db, "m1", "assistant", 30)
    _add(read_db, "m2", "assistant", 20)
    assert authed_client.put("/chat/read", json={"messageId": "m2"}).status_code == 200

    response = authed_client.put("/chat/read", json={"messageId": "m1"})

    assert response.json() == {"lastReadMessageId": "m2", "unreadCount": 0}
    assert authed_client.get("/chat/unread").json()["lastReadMessageId"] == "m2"


def test_another_users_message_is_a_404(authed_client, read_db, published):
    _add(read_db, "other", "assistant", 5, user_id="someone-else")

    assert authed_client.put("/chat/read", json={"messageId": "other"}).status_code == 404
    assert authed_client.put("/chat/read", json={"messageId": "unknown"}).status_code == 404
    published.assert_not_awaited()


def test_a_mercure_failure_does_not_fail_the_read(read_db):
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_current_user_id] = lambda: "test-user"
    _add(read_db, "m1", "assistant", 5)

    with (
        patch("app.api.routes.read_publisher.publish", new=AsyncMock(side_effect=RuntimeError("hub down"))),
        TestClient(app) as c,
    ):
        assert c.put("/chat/read", json={"messageId": "m1"}).status_code == 200

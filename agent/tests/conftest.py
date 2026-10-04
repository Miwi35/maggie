from collections.abc import Callable
from dataclasses import dataclass
from typing import Any
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.compiler import compiles

from app.api.routes import router
from app.auth import get_current_user_id


@pytest.fixture(autouse=True)
def no_last_exchange_lookup():
    """Keep the « last conversation » lookup (MAG-10) off the database in tests that only build a prompt.

    The gateways import the function by name, so that is where it is replaced; the tests of the
    lookup itself call `app.llm.last_exchange` directly and are not affected.
    """
    with (
        patch("app.llm.streaming.last_exchange_section", AsyncMock(return_value="")),
        patch("app.llm.gateway.last_exchange_section", AsyncMock(return_value="")),
    ):
        yield


@pytest.fixture()
def client():
    """FastAPI test client with lifespan disabled to avoid MCP connection attempts."""
    test_app = FastAPI()
    test_app.include_router(router)

    with TestClient(test_app) as c:
        yield c


@pytest.fixture()
def authed_client():
    """FastAPI test client with auth dependency overridden to return 'test-user'."""
    test_app = FastAPI()
    test_app.include_router(router)
    test_app.dependency_overrides[get_current_user_id] = lambda: "test-user"

    with TestClient(test_app) as c:
        yield c


class _SyncSessionAsAsync:
    """Async-shaped facade over a sync SQLAlchemy session, so repositories run real SQL on SQLite."""

    def __init__(self, session):
        self._session = session

    def add(self, obj):
        self._session.add(obj)

    async def execute(self, stmt):
        return self._session.execute(stmt)

    async def commit(self):
        self._session.commit()

    async def refresh(self, obj):
        self._session.refresh(obj)

    async def delete(self, obj):
        self._session.delete(obj)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        self._session.close()


@dataclass
class ChatDb:
    """What a chat_db test needs: a session factory, and the Mercure publication to assert."""

    session: Callable[[], Any]
    published: AsyncMock


@compiles(JSONB, "sqlite")
def _jsonb_as_json(type_, compiler, **kw):  # pragma: no cover — DDL only
    """Render a Postgres `JSONB` column as `JSON` when the dialect is SQLite.

    `tool_calls_log` is `JSONB` and SQLite has no such type, so the DDL compiler refuses
    to render it and `CREATE TABLE` raises. This is purely about the DDL: `JSONB` derives
    from SQLAlchemy's own `JSON`, so values are serialised through the dialect either
    way, and the repositories under test run exactly the queries they run in production.

    Registered at module scope on purpose — it is process-global state, and a fixture
    body would hide that while never undoing it.
    """
    return "JSON"


@pytest.fixture()
def chat_db():
    """In-memory database behind the context and message repositories (real queries, no mocks)."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import StaticPool

    from app.db.context_model import ConversationContext
    from app.db.models import Message

    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    ConversationContext.__table__.create(engine)
    Message.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    published = AsyncMock()
    with (
        patch("app.db.context_repository.agent_session", open_session),
        patch("app.db.message_repository.agent_session", open_session),
        patch("app.db.context_repository.context_repo.publisher.publish", new=published),
        patch("app.db.message_repository.message_repo.publisher.publish", new=AsyncMock()),
    ):
        yield ChatDb(session=open_session, published=published)
    engine.dispose()


@pytest.fixture()
def agent_db():
    """In-memory database behind the memory, instruction and skill repositories (real queries, no mocks)."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import StaticPool

    from app.db.instruction_model import Instruction
    from app.db.memory_model import Memory
    from app.db.skill_model import Skill

    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    Memory.__table__.create(engine)
    Instruction.__table__.create(engine)
    Skill.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with (
        patch("app.db.memory_repository.agent_session", open_session),
        patch("app.db.instruction_repository.agent_session", open_session),
        patch("app.db.skill_repository.agent_session", open_session),
        patch("app.db.instruction_repository.instruction_repo.publisher.publish", new=AsyncMock()),
    ):
        yield factory
    engine.dispose()


@pytest.fixture()
def memory_db():
    """In-memory database behind the note index, its journal and its outbox (real queries, no mocks)."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import StaticPool

    from app.db.memory_note_model import MemoryEvent, MemoryNote, MemoryOutbox

    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    for model in (MemoryNote, MemoryEvent, MemoryOutbox):
        model.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with patch("app.db.memory_note_repository.agent_session", open_session):
        yield factory
    engine.dispose()

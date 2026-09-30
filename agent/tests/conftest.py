from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.auth import get_current_user_id


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


@pytest.fixture()
def agent_db():
    """In-memory database behind the memory and instruction repositories (real queries, no mocks)."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import StaticPool

    from app.db.instruction_model import Instruction
    from app.db.memory_model import Memory

    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    Memory.__table__.create(engine)
    Instruction.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with (
        patch("app.db.memory_repository.agent_session", open_session),
        patch("app.db.instruction_repository.agent_session", open_session),
        patch("app.db.instruction_repository.instruction_repo.publisher.publish", new=AsyncMock()),
    ):
        yield factory
    engine.dispose()

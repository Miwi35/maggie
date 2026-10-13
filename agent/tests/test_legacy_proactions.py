"""The move out of the agent's own `proaction` table (MAG-327): pending ones go to the API, then the table goes."""

from contextlib import asynccontextmanager
from datetime import UTC, datetime
from unittest.mock import patch

import pytest
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.pool import StaticPool

from app.proactions import legacy
from tests.fake_proaction_api import FakeProactionApi

NOW = datetime(2030, 11, 13, 11, 0, tzinfo=UTC)

# The table as `app/db/proaction_model.py` created it before MAG-327.
LEGACY_DDL = """
CREATE TABLE proaction (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    prompt TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    response TEXT,
    error TEXT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
)
"""


class _AsyncConnection:
    def __init__(self, connection):
        self._connection = connection

    async def execute(self, statement, parameters=None):
        return self._connection.execute(statement, parameters or {})

    async def run_sync(self, fn):
        return fn(self._connection)


class _SyncEngineAsAsync:
    """The two calls `legacy` makes on `agent_engine`, over a sync SQLite engine (no async driver here)."""

    def __init__(self, engine):
        self._engine = engine

    @asynccontextmanager
    async def connect(self):
        with self._engine.connect() as connection:
            yield _AsyncConnection(connection)

    @asynccontextmanager
    async def begin(self):
        with self._engine.begin() as connection:
            yield _AsyncConnection(connection)


@pytest.fixture()
def engine():
    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    with engine.begin() as connection:
        connection.execute(text(LEGACY_DDL))
    yield engine
    engine.dispose()


@pytest.fixture()
def api():
    return FakeProactionApi(now=NOW)


@pytest.fixture(autouse=True)
def wired(engine, api, monkeypatch):
    monkeypatch.setattr(legacy, "_done", False)
    monkeypatch.setattr(legacy, "_earliest", None)
    monkeypatch.setattr(legacy, "_utcnow", lambda: NOW)
    with (
        patch.object(legacy, "agent_engine", _SyncEngineAsAsync(engine)),
        patch("app.proactions.api.mcp_client", api),
    ):
        yield


def seed(engine, row_id: str, *, user_id: str = "user-1", status: str = "pending", at: datetime, prompt: str = "Rappelle le pain"):
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO proaction (id, user_id, prompt, status, scheduled_at, created_at) "
                "VALUES (:id, :user_id, :prompt, :status, :at, :at)"
            ),
            {"id": row_id, "user_id": user_id, "prompt": prompt, "status": status, "at": at.isoformat()},
        )


def table_exists(engine) -> bool:
    return inspect(engine).has_table("proaction")


class TestTheMove:
    async def test_pending_ones_are_scheduled_through_the_api_then_the_table_is_dropped(self, engine, api):
        seed(engine, "a", at=datetime(2030, 11, 13, 17, 0, tzinfo=UTC), prompt="Rappelle le pain")
        seed(engine, "b", user_id="user-2", at=datetime(2030, 11, 14, 7, 30, tzinfo=UTC), prompt="Rappelle le chien")
        seed(engine, "done", status="completed", at=datetime(2030, 11, 12, 9, 0, tzinfo=UTC))
        seed(engine, "failed", status="failed", at=datetime(2030, 11, 12, 9, 0, tzinfo=UTC))

        assert await legacy.migrate_legacy_proactions() is True

        assert [(p["prompt"], p["scheduledAt"], p["status"]) for p in api.of("user-1")] == [
            ("Rappelle le pain", "2030-11-13T17:00:00+00:00", "pending")
        ]
        assert [p["prompt"] for p in api.of("user-2")] == ["Rappelle le chien"]
        assert not table_exists(engine)

    async def test_one_that_fell_due_during_the_deploy_still_runs_a_minute_later(self, engine, api):
        seed(engine, "late", at=datetime(2030, 11, 13, 10, 55, tzinfo=UTC))

        await legacy.migrate_legacy_proactions()

        assert [p["scheduledAt"] for p in api.of("user-1")] == ["2030-11-13T11:01:00+00:00"]

    async def test_an_unreachable_api_keeps_the_table_and_the_next_attempt_resumes_without_doubles(self, engine, api):
        seed(engine, "a", at=datetime(2030, 11, 13, 17, 0, tzinfo=UTC), prompt="Rappelle le pain")
        seed(engine, "b", at=datetime(2030, 11, 13, 18, 0, tzinfo=UTC), prompt="Rappelle le chien")
        answered = api.call_tool

        async def down_after_the_first(name, arguments, user_id=None):
            api.down = len(api.calls) >= 1
            return await answered(name, arguments, user_id)

        api.call_tool = down_after_the_first
        assert await legacy.migrate_legacy_proactions() is False
        assert table_exists(engine)
        with engine.connect() as connection:
            assert [r.id for r in connection.execute(text("SELECT id FROM proaction"))] == ["b"]

        api.call_tool = answered
        api.down = False
        assert await legacy.migrate_legacy_proactions() is True

        assert [p["prompt"] for p in api.of("user-1")] == ["Rappelle le pain", "Rappelle le chien"]
        assert not table_exists(engine)

    async def test_one_the_api_refuses_is_dropped_not_retried_for_ever(self, engine, api):
        seed(engine, "empty", at=datetime(2030, 11, 13, 17, 0, tzinfo=UTC), prompt=" ")
        seed(engine, "a", at=datetime(2030, 11, 13, 18, 0, tzinfo=UTC))

        assert await legacy.migrate_legacy_proactions() is True

        assert [p["prompt"] for p in api.of("user-1")] == ["Rappelle le pain"]
        assert not table_exists(engine)

    async def test_without_the_table_there_is_nothing_to_do_and_the_api_is_not_called(self, engine, api):
        with engine.begin() as connection:
            connection.execute(text("DROP TABLE proaction"))

        assert await legacy.migrate_legacy_proactions() is True
        assert await legacy.migrate_legacy_proactions() is True

        assert api.calls == []

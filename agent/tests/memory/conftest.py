from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from unittest.mock import patch

import pytest

from app.memory.bucket import FakeBucket
from app.memory.service import MemoryService
from tests.conftest import _SyncSessionAsAsync

USER_A = "01J9Z3K5Q8W2X4Y6A7B8C9D0E1"
USER_B = "01J9Z3K5Q8W2X4Y6A7B8C9D0E2"


class Clock:
    def __init__(self) -> None:
        self.now = datetime(2026, 10, 13, 9, 30, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs: float) -> None:
        self.now += timedelta(**kwargs)


@dataclass
class Env:
    service: MemoryService
    bucket: FakeBucket
    clock: Clock
    root: object

    def key(self, rel: str, user_id: str = USER_A) -> str:
        return f"{user_id}/Mémoire/{rel}"

    def remote(self, rel: str, user_id: str = USER_A) -> str | None:
        obj = self.bucket.objects.get(self.key(rel, user_id))
        return obj.body.decode("utf-8") if obj else None

    def local(self, rel: str, user_id: str = USER_A) -> str | None:
        return self.service.folder(user_id).read(rel)


@pytest.fixture()
def memory_db():
    """The five memory tables on SQLite behind the repositories (real queries, no mocks)."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import StaticPool

    from app.db.memory_index_model import MemoryFile, MemoryLink, MemoryOutbox, MemoryTerm, MemoryUsage

    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    for model in (MemoryFile, MemoryTerm, MemoryLink, MemoryUsage, MemoryOutbox):
        model.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with patch("app.db.memory_index_repository.agent_session", open_session):
        yield factory
    engine.dispose()


@pytest.fixture()
def env(memory_db, tmp_path):
    clock = Clock()
    bucket = FakeBucket(clock=clock)
    service = MemoryService(bucket, tmp_path / "local", clock=clock, push_after_write=False)
    return Env(service, bucket, clock, tmp_path / "local")


def fiche(title: str = "", *, resume: str = "", evoque: list[str] | None = None, ident: str | None = None) -> str:
    lines = ["---"]
    if ident:
        lines.append(f"id: {ident}")
    if resume:
        lines.append(f"resume: {resume}")
    if evoque:
        lines.append("evoque: [" + ", ".join(evoque) + "]")
    lines += ["---", "", title or "Texte."]
    return "\n".join(lines) + "\n"

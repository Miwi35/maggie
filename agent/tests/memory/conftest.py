"""Shared fixtures of the memory tests: a world with a fake bucket, the real index on SQLite, and a clock we move."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

import asyncio

import pytest
from sqlalchemy import select

from app.db.memory_note_model import MemoryEvent, MemoryNote, MemoryOutbox
from app.db.memory_note_repository import MemoryNoteRepository
from app.memory.bucket import FakeBucket, ObjectData, ObjectInfo
from app.memory.frontmatter import NoteDoc, render_note
from app.memory.locks import PathLocks
from app.memory.notifier import MemoryNotifier
from app.memory.reconciler import Reconciler
from app.memory.store import NoteStore
from app.memory.summary import SummaryWriter
from app.memory.sync import MemorySync
from app.memory.ulid import new_ulid

T0 = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)


class Clock:
    def __init__(self, now: datetime = T0) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now

    def advance(self, seconds: float) -> datetime:
        self.now += timedelta(seconds=seconds)
        return self.now


class RecordingPublisher:
    def __init__(self) -> None:
        self.published: list[tuple[str, dict]] = []
        self.fail = False

    async def publish(self, topic: str, data: dict) -> None:
        if self.fail:
            raise RuntimeError("hub is down")
        self.published.append((topic, data))

    def kinds(self, user_id: str | None = None) -> list[str]:
        return [d["type"] for t, d in self.published if user_id is None or t == f"/memory/{user_id}"]

    def clear(self) -> None:
        self.published.clear()


class RacyBucket(FakeBucket):
    """A FakeBucket the test can interleave with: act right after a GET, hold a PUT open, see every PUT's conditions."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.after_get: Callable[[str], None] | None = None
        self.put_gate: asyncio.Event | None = None
        self.put_started = asyncio.Event()
        self.puts: list[tuple[str, str | None, bool]] = []
        self.list_gate: asyncio.Event | None = None
        self.list_started = asyncio.Event()

    async def list_objects(self, prefix: str = "") -> list[ObjectInfo]:
        if self.list_gate is not None:
            self.list_started.set()
            await self.list_gate.wait()
        return await super().list_objects(prefix)

    async def get(self, key: str) -> ObjectData:
        data = await super().get(key)
        if self.after_get is not None:
            self.after_get(key)
        return data

    async def put(self, key: str, body: bytes, *, if_match: str | None = None, if_none_match: bool = False) -> str:
        self.puts.append((key, if_match, if_none_match))
        if self.put_gate is not None:
            self.put_started.set()
            await self.put_gate.wait()
        return await super().put(key, body, if_match=if_match, if_none_match=if_none_match)


@dataclass
class Seeded:
    key: str
    id: str
    etag: str


@dataclass
class World:
    factory: Callable[[], Any]
    clock: Clock
    bucket: FakeBucket
    repo: MemoryNoteRepository
    locks: PathLocks
    publisher: RecordingPublisher
    notifier: MemoryNotifier
    summary: SummaryWriter
    store: NoteStore
    reconciler: Reconciler
    sync: MemorySync
    user: str
    other_user: str

    # -- bucket side: what the owner does with his editor

    def seed(
        self,
        key: str,
        title: str = "Titre",
        body: str = "Corps",
        note_id: str | None = None,
        at: datetime | None = None,
        **fields: Any,
    ) -> Seeded:
        note_id = note_id or new_ulid()
        etag = self.bucket.seed(key, render_note(NoteDoc(title=title, body=body, id=note_id, **fields)), at)
        return Seeded(key, note_id, etag)

    def seed_many(self, user: str, count: int, prefix: str = "n") -> list[Seeded]:
        return [self.seed(f"{user}/{prefix}{i}.md", title=f"{prefix}{i}", body=f"body {i}") for i in range(count)]

    # -- index side

    def rows(self, user: str | None = None, live_only: bool = False) -> list[MemoryNote]:
        with self.factory() as session:
            stmt = select(MemoryNote).order_by(MemoryNote.path)
            if user:
                stmt = stmt.where(MemoryNote.user_id == user)
            found = list(session.execute(stmt).scalars().all())
        return [r for r in found if r.deleted_at is None] if live_only else found

    def row(self, note_id: str) -> MemoryNote:
        found = [r for r in self.rows() if r.id == note_id]
        assert len(found) == 1, f"expected one row for {note_id}, got {len(found)}"
        return found[0]

    def row_at(self, key: str) -> MemoryNote:
        found = [r for r in self.rows() if r.path == key and r.deleted_at is None]
        assert len(found) == 1, f"expected one live row at {key}, got {len(found)}"
        return found[0]

    def events(self, user: str | None = None, kind: str | None = None) -> list[MemoryEvent]:
        with self.factory() as session:
            stmt = select(MemoryEvent).order_by(MemoryEvent.id)
            found = list(session.execute(stmt).scalars().all())
        return [e for e in found if (user is None or e.user_id == user) and (kind is None or e.kind == kind)]

    def outbox(self) -> list[MemoryOutbox]:
        with self.factory() as session:
            return list(session.execute(select(MemoryOutbox).order_by(MemoryOutbox.id)).scalars().all())

    def update_row(self, note_id: str, **values: Any) -> None:
        with self.factory() as session:
            row = session.get(MemoryNote, note_id)
            for name, value in values.items():
                setattr(row, name, value)
            session.commit()


def build_world(factory: Callable[[], Any], bucket: FakeBucket | None = None, **sync_options: Any) -> World:
    clock = Clock()
    bucket = bucket if bucket is not None else FakeBucket(clock=clock)
    bucket._clock = clock
    repo = MemoryNoteRepository()
    locks = PathLocks()
    publisher = RecordingPublisher()
    notifier = MemoryNotifier(publisher=publisher)  # type: ignore[arg-type]
    summary = SummaryWriter(bucket, repo)
    store = NoteStore(bucket, repo, locks, notifier, summary)
    reconciler = Reconciler(bucket, repo, locks, notifier)
    sync = MemorySync(reconciler, store, summary, repo, clock=clock, **sync_options)
    return World(
        factory=factory,
        clock=clock,
        bucket=bucket,
        repo=repo,
        locks=locks,
        publisher=publisher,
        notifier=notifier,
        summary=summary,
        store=store,
        reconciler=reconciler,
        sync=sync,
        user=new_ulid(),
        other_user=new_ulid(),
    )


@pytest.fixture()
def world(memory_db) -> World:
    return build_world(memory_db)


@pytest.fixture()
def make_world(memory_db):
    """For tests that need a bucket subclass or sync options of their own."""
    def make(bucket: FakeBucket | None = None, **sync_options: Any) -> World:
        return build_world(memory_db, bucket, **sync_options)

    return make

from datetime import UTC, datetime
from typing import Any

from sqlalchemy import delete, func, select, update

from app.db.agent_engine import agent_session
from app.db.memory_note_model import MemoryEvent, MemoryNote, MemoryOutbox


def as_utc(value: datetime | None) -> datetime | None:
    """SQLite hands datetimes back naive: everything the memory code compares is UTC."""
    if value is None:
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


class MemoryNoteRepository:
    """Queries on the note index, its journal and its outbox.

    Methods take the session: the write door and the reconciler change the index, the journal and
    the outbox in one transaction, so the caller owns the commit. `session()` opens one on the agent DB.
    """

    def session(self) -> Any:
        return agent_session()

    async def all_notes(self, session: Any) -> list[MemoryNote]:
        return list((await session.execute(select(MemoryNote))).scalars().all())

    async def live_note_at(self, session: Any, user_id: str, path: str) -> MemoryNote | None:
        stmt = select(MemoryNote).where(
            MemoryNote.user_id == user_id, MemoryNote.path == path, MemoryNote.deleted_at.is_(None)
        )
        return (await session.execute(stmt)).scalars().first()

    async def note_by_id(self, session: Any, user_id: str, note_id: str) -> MemoryNote | None:
        stmt = select(MemoryNote).where(MemoryNote.user_id == user_id, MemoryNote.id == note_id)
        return (await session.execute(stmt)).scalars().first()

    async def note_by_id_any_user(self, session: Any, note_id: str) -> MemoryNote | None:
        return (await session.execute(select(MemoryNote).where(MemoryNote.id == note_id))).scalars().first()

    async def note_id_taken(self, session: Any, note_id: str) -> bool:
        return (await session.execute(select(MemoryNote.id).where(MemoryNote.id == note_id))).first() is not None

    async def add_event(
        self,
        session: Any,
        user_id: str,
        kind: str,
        note_id: str | None = None,
        path: str | None = None,
        detail: dict[str, Any] | None = None,
    ) -> None:
        session.add(MemoryEvent(user_id=user_id, kind=kind, note_id=note_id, path=path, detail=detail or {}))

    async def events(self, user_id: str, limit: int = 100) -> list[MemoryEvent]:
        async with self.session() as session:
            stmt = (
                select(MemoryEvent).where(MemoryEvent.user_id == user_id).order_by(MemoryEvent.id.desc()).limit(limit)
            )
            return list((await session.execute(stmt)).scalars().all())

    async def outbox_at(self, session: Any, user_id: str, path: str) -> MemoryOutbox | None:
        stmt = select(MemoryOutbox).where(MemoryOutbox.user_id == user_id, MemoryOutbox.path == path)
        return (await session.execute(stmt)).scalars().first()

    async def outbox_all(self, session: Any) -> list[MemoryOutbox]:
        return list((await session.execute(select(MemoryOutbox).order_by(MemoryOutbox.id))).scalars().all())

    async def outbox_size(self) -> int:
        async with self.session() as session:
            return int((await session.execute(select(func.count()).select_from(MemoryOutbox))).scalar_one())

    async def notes_for_prompt(self, user_id: str, limit: int) -> list[MemoryNote]:
        """The active, readable notes of a user: pinned first, then the most recently edited."""
        async with self.session() as session:
            stmt = (
                select(MemoryNote)
                .where(
                    MemoryNote.user_id == user_id,
                    MemoryNote.status == "active",
                    MemoryNote.deleted_at.is_(None),
                    MemoryNote.unreadable.is_(False),
                )
                .order_by(MemoryNote.pinned.desc(), MemoryNote.updated_at.desc())
                .limit(limit)
            )
            return list((await session.execute(stmt)).scalars().all())

    async def live_notes_of_user(self, user_id: str) -> list[MemoryNote]:
        async with self.session() as session:
            stmt = select(MemoryNote).where(MemoryNote.user_id == user_id, MemoryNote.deleted_at.is_(None))
            return list((await session.execute(stmt)).scalars().all())

    async def unreadable_notes(self, user_id: str) -> list[MemoryNote]:
        async with self.session() as session:
            stmt = select(MemoryNote).where(
                MemoryNote.user_id == user_id, MemoryNote.unreadable.is_(True), MemoryNote.deleted_at.is_(None)
            )
            return list((await session.execute(stmt)).scalars().all())

    async def last_indexed_at(self) -> datetime | None:
        async with self.session() as session:
            return as_utc((await session.execute(select(func.max(MemoryNote.indexed_at)))).scalar_one())

    async def mark_used(self, user_id: str, note_ids: list[str]) -> None:
        """The two fields no file holds: when a note last reached a prompt, and how often."""
        if not note_ids:
            return
        async with self.session() as session:
            await session.execute(
                update(MemoryNote)
                .where(MemoryNote.user_id == user_id, MemoryNote.id.in_(note_ids))
                .values(last_used_at=datetime.now(UTC), use_count=MemoryNote.use_count + 1)
            )
            await session.commit()

    async def usage_snapshot(self, session: Any, user_id: str | None) -> dict[str, tuple[datetime | None, int]]:
        stmt = select(MemoryNote.id, MemoryNote.last_used_at, MemoryNote.use_count)
        if user_id:
            stmt = stmt.where(MemoryNote.user_id == user_id)
        return {row.id: (as_utc(row.last_used_at), row.use_count) for row in (await session.execute(stmt)).all()}

    async def restore_usage(self, session: Any, snapshot: dict[str, tuple[datetime | None, int]]) -> None:
        for note_id, (last_used_at, use_count) in snapshot.items():
            await session.execute(
                update(MemoryNote)
                .where(MemoryNote.id == note_id)
                .values(last_used_at=last_used_at, use_count=use_count)
            )

    async def drop_index(self, session: Any, user_id: str | None) -> None:
        stmt = delete(MemoryNote)
        if user_id:
            stmt = stmt.where(MemoryNote.user_id == user_id)
        await session.execute(stmt)


memory_note_repo = MemoryNoteRepository()

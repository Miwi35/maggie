"""Reads and writes of the memory index, its usage counters and its outbox (MAG-195).

Every query filters on `user_id`: the index of one user never answers for another. The full-text
search is Postgres (`to_tsvector('french', …)`, GIN index created by `context_repo.run_migrations`);
the unit tests run on SQLite, which falls back to a plain substring match over the same column.
"""

import logging
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import delete, func, or_, select

from app.db.agent_engine import agent_session
from app.db.memory_index_model import (
    LINK_LIEN,
    LINK_SOURCE,
    OP_APPEND,
    OP_DELETE,
    OP_PUT,
    TERM_ALIAS,
    TERM_EVOQUE,
    TERM_PERSON,
    MemoryFile,
    MemoryLink,
    MemoryOutbox,
    MemoryTerm,
    MemoryUsage,
)
from app.memory import paths
from app.memory.carte import Carte, content_hash, search_text

logger = logging.getLogger(__name__)

_LIKE_ESCAPE = re.compile(r"([\\%_])")


@dataclass(frozen=True)
class IndexedFile:
    """What recall reads of a file: its carte and its text, from the index alone."""

    path: str
    kind: str
    card_id: str
    title: str
    resume: str
    moments: list[str]
    personnes: list[str]
    sources: list[dict[str, Any]]
    body: str
    content_hash: str
    unreadable: str | None


@dataclass(frozen=True)
class OutboxEntry:
    user_id: str
    path: str
    op: str
    body: str | None
    base_etag: str | None
    attempts: int
    created_at: datetime
    updated_at: datetime | None = None


def _as_indexed(row: MemoryFile) -> IndexedFile:
    return IndexedFile(
        path=row.path,
        kind=row.kind,
        card_id=row.card_id,
        title=row.title,
        resume=row.resume or "",
        moments=list(row.moments or []),
        personnes=list(row.personnes or []),
        sources=list(row.sources or []),
        body=row.body or "",
        content_hash=row.content_hash,
        unreadable=row.unreadable,
    )


def title_of(path: str) -> str:
    return path.rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)


def fallback_card_id(path: str) -> str:
    """The id of a file that has none (a conversation, or a fiche not stamped yet): stable for its path."""
    return "p" + content_hash(path)[:31]


class MemoryIndexRepository:
    """The rebuildable tables: one row per file, its terms, its links."""

    async def hashes(self, user_id: str) -> dict[str, str]:
        """path -> content hash of what the index holds for this user."""
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryFile.path, MemoryFile.content_hash).where(MemoryFile.user_id == user_id)
            )
            return {path: digest for path, digest in rows.all()}

    async def card_ids(self, user_id: str) -> dict[str, str]:
        """card id -> path, for the files that carry an id of their own."""
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryFile.card_id, MemoryFile.path).where(MemoryFile.user_id == user_id)
            )
            return {card_id: path for card_id, path in rows.all()}

    async def get(self, user_id: str, path: str) -> IndexedFile | None:
        async with agent_session() as session:
            row = (
                await session.execute(select(MemoryFile).where(MemoryFile.user_id == user_id, MemoryFile.path == path))
            ).scalar_one_or_none()
            return _as_indexed(row) if row else None

    async def list_files(self, user_id: str, kind: str | None = None) -> list[IndexedFile]:
        async with agent_session() as session:
            query = select(MemoryFile).where(MemoryFile.user_id == user_id).order_by(MemoryFile.path)
            if kind:
                query = query.where(MemoryFile.kind == kind)
            return [_as_indexed(row) for row in (await session.execute(query)).scalars().all()]

    async def upsert(
        self,
        user_id: str,
        path: str,
        *,
        kind: str,
        card_id: str,
        carte: Carte,
        body: str,
        digest: str,
        unreadable: str | None = None,
    ) -> None:
        """Replace everything the index holds for one file, in one transaction."""
        title = title_of(path)
        async with agent_session() as session:
            await self._delete_file(session, user_id, path)
            session.add(
                MemoryFile(
                    user_id=user_id,
                    path=path,
                    kind=kind,
                    card_id=card_id,
                    title=title,
                    resume=carte.resume,
                    moments=carte.moments,
                    personnes=carte.personnes,
                    sources=carte.sources,
                    body=body,
                    search_text=search_text(title, carte, body),
                    content_hash=digest,
                    unreadable=unreadable,
                    indexed_at=datetime.now(UTC),
                )
            )
            terms = (
                {(TERM_EVOQUE, t.casefold()) for t in carte.evoque}
                | {(TERM_ALIAS, t.casefold()) for t in carte.alias}
                | {(TERM_PERSON, t.casefold()) for t in carte.personnes}
            )
            for kind_of_term, term in sorted(terms):
                session.add(MemoryTerm(user_id=user_id, path=path, kind=kind_of_term, term=term[:200]))
            sources = {str(s["id"]) for s in carte.sources if s.get("id")}
            for target in carte.linked_ids:
                session.add(
                    MemoryLink(
                        user_id=user_id,
                        path=path,
                        target_id=target[:64],
                        kind=LINK_SOURCE if target in sources and target not in carte.liens else LINK_LIEN,
                    )
                )
            await session.commit()

    async def remove(self, user_id: str, path: str) -> None:
        async with agent_session() as session:
            await self._delete_file(session, user_id, path)
            await session.commit()

    async def clear_user(self, user_id: str) -> int:
        """Drop the rebuildable index of one user. The counters and the outbox stay."""
        async with agent_session() as session:
            count = (
                await session.execute(select(func.count()).select_from(MemoryFile).where(MemoryFile.user_id == user_id))
            ).scalar_one()
            for model in (MemoryLink, MemoryTerm, MemoryFile):
                await session.execute(delete(model).where(model.user_id == user_id))
            await session.commit()
            return int(count)

    async def user_ids(self) -> list[str]:
        async with agent_session() as session:
            rows = await session.execute(select(MemoryFile.user_id).distinct())
            return sorted(row[0] for row in rows.all())

    @staticmethod
    async def _delete_file(session: Any, user_id: str, path: str) -> None:
        for model in (MemoryLink, MemoryTerm, MemoryFile):
            await session.execute(delete(model).where(model.user_id == user_id, model.path == path))

    async def terms(self, user_id: str, path: str) -> list[tuple[str, str]]:
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryTerm.kind, MemoryTerm.term)
                .where(MemoryTerm.user_id == user_id, MemoryTerm.path == path)
                .order_by(MemoryTerm.kind, MemoryTerm.term)
            )
            return [(kind, term) for kind, term in rows.all()]

    async def links(self, user_id: str, path: str) -> list[tuple[str, str]]:
        """(kind, target card id) for a file's outgoing links."""
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryLink.kind, MemoryLink.target_id)
                .where(MemoryLink.user_id == user_id, MemoryLink.path == path)
                .order_by(MemoryLink.target_id)
            )
            return [(kind, target) for kind, target in rows.all()]

    async def linked_from(self, user_id: str, card_id: str) -> list[str]:
        """Paths of the files that point to this card id."""
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryLink.path)
                .where(MemoryLink.user_id == user_id, MemoryLink.target_id == card_id)
                .order_by(MemoryLink.path)
            )
            return [row[0] for row in rows.all()]

    async def search(self, user_id: str, query: str, limit: int = 10) -> list[IndexedFile]:
        """Fiches and conversations of this user matching `query`: French full-text on Postgres, terms first."""
        query = query.strip()
        if not query:
            return []
        async with agent_session() as session:
            words = [w.casefold() for w in query.split()] + [query.casefold()]
            term_paths = select(MemoryTerm.path).where(MemoryTerm.user_id == user_id, MemoryTerm.term.in_(words))
            if self._has_fts(session):
                tsquery = func.websearch_to_tsquery("french", query)
                vector = func.to_tsvector("french", MemoryFile.search_text)
                rank = func.ts_rank(vector, tsquery)
                stmt = (
                    select(MemoryFile)
                    .where(
                        MemoryFile.user_id == user_id,
                        or_(vector.op("@@")(tsquery), MemoryFile.path.in_(term_paths)),
                    )
                    .order_by(rank.desc(), MemoryFile.path)
                    .limit(limit)
                )
            else:
                needle = "%" + _LIKE_ESCAPE.sub(r"\\\1", query.casefold()) + "%"
                stmt = (
                    select(MemoryFile)
                    .where(
                        MemoryFile.user_id == user_id,
                        or_(
                            func.lower(MemoryFile.search_text).like(needle, escape="\\"),
                            MemoryFile.path.in_(term_paths),
                        ),
                    )
                    .order_by(MemoryFile.path)
                    .limit(limit)
                )
            return [_as_indexed(row) for row in (await session.execute(stmt)).scalars().all()]

    @staticmethod
    def _has_fts(session: Any) -> bool:
        bind = getattr(session, "bind", None)
        return bind is not None and bind.dialect.name == "postgresql"


class MemoryUsageRepository:
    """The counters: outside the rebuildable index, keyed on the card id."""

    async def record(self, user_id: str, card_id: str, at: datetime | None = None) -> None:
        async with agent_session() as session:
            row = (
                await session.execute(
                    select(MemoryUsage).where(MemoryUsage.user_id == user_id, MemoryUsage.card_id == card_id)
                )
            ).scalar_one_or_none()
            if row is None:
                session.add(
                    MemoryUsage(user_id=user_id, card_id=card_id, use_count=1, last_used_at=at or datetime.now(UTC))
                )
            else:
                row.use_count = (row.use_count or 0) + 1
                row.last_used_at = at or datetime.now(UTC)
            await session.commit()

    async def get(self, user_id: str, card_id: str) -> tuple[int, datetime | None]:
        async with agent_session() as session:
            row = (
                await session.execute(
                    select(MemoryUsage).where(MemoryUsage.user_id == user_id, MemoryUsage.card_id == card_id)
                )
            ).scalar_one_or_none()
            return (row.use_count, row.last_used_at) if row else (0, None)

    async def all(self, user_id: str) -> dict[str, int]:
        async with agent_session() as session:
            rows = await session.execute(
                select(MemoryUsage.card_id, MemoryUsage.use_count).where(MemoryUsage.user_id == user_id)
            )
            return {card_id: count for card_id, count in rows.all()}


class Keep:
    """Marks 'leave the base ETag as it is', as opposed to None, which is a base of its own."""


KEEP = Keep()


class MemoryOutboxRepository:
    """Writes waiting for the bucket. One row per file; a newer write replaces the older one."""

    async def enqueue(self, user_id: str, path: str, op: str, body: str | None, base_etag: str | None) -> None:
        """Queue a write. `base_etag` is kept from the first write still waiting: it is what the push compares to."""
        now = datetime.now(UTC)
        async with agent_session() as session:
            row = await self._row(session, user_id, path)
            if row is None:
                session.add(
                    MemoryOutbox(
                        user_id=user_id,
                        path=path,
                        op=op,
                        body=body,
                        base_etag=base_etag,
                        attempts=0,
                        created_at=now,
                        updated_at=now,
                    )
                )
            else:
                if op == OP_APPEND and row.op == OP_APPEND:
                    row.body = (row.body or "") + (body or "")
                else:
                    row.op = op
                    row.body = body
                row.updated_at = now
            await session.commit()

    async def pending(self, user_id: str) -> list[OutboxEntry]:
        async with agent_session() as session:
            rows = (
                (
                    await session.execute(
                        select(MemoryOutbox).where(MemoryOutbox.user_id == user_id).order_by(MemoryOutbox.created_at)
                    )
                )
                .scalars()
                .all()
            )
            return [
                OutboxEntry(
                    r.user_id, r.path, r.op, r.body, r.base_etag, int(r.attempts or 0), r.created_at, r.updated_at
                )
                for r in rows
            ]

    async def get(self, user_id: str, path: str) -> OutboxEntry | None:
        async with agent_session() as session:
            r = await self._row(session, user_id, path)
            if r is None:
                return None
            return OutboxEntry(
                r.user_id, r.path, r.op, r.body, r.base_etag, int(r.attempts or 0), r.created_at, r.updated_at
            )

    async def count(self, user_id: str | None = None) -> int:
        async with agent_session() as session:
            query = select(func.count()).select_from(MemoryOutbox)
            if user_id:
                query = query.where(MemoryOutbox.user_id == user_id)
            return int((await session.execute(query)).scalar_one())

    async def users(self) -> list[str]:
        async with agent_session() as session:
            rows = await session.execute(select(MemoryOutbox.user_id).distinct())
            return sorted(row[0] for row in rows.all())

    async def done(
        self,
        user_id: str,
        path: str,
        *,
        only_if_updated_at: datetime | None = None,
        new_base_etag: str | None | Keep = KEEP,
    ) -> None:
        """Remove an entry once pushed — unless a newer write replaced it while the push was running.

        A replaced entry stays, and when `new_base_etag` is given it becomes its base: the push just moved
        the bucket's object there, so the newer write must be compared with that one, not the old one.
        """
        async with agent_session() as session:
            row = await self._row(session, user_id, path)
            if row is None:
                return
            if only_if_updated_at is not None and _naive(row.updated_at) != _naive(only_if_updated_at):
                if not isinstance(new_base_etag, Keep):
                    row.base_etag = new_base_etag
                    await session.commit()
                return
            await session.delete(row)
            await session.commit()

    async def failed(self, user_id: str, path: str, error: str) -> None:
        async with agent_session() as session:
            row = await self._row(session, user_id, path)
            if row is None:
                return
            row.attempts = int(row.attempts or 0) + 1
            row.last_error = error[:500]
            await session.commit()

    @staticmethod
    async def _row(session: Any, user_id: str, path: str) -> MemoryOutbox | None:
        stmt = select(MemoryOutbox).where(MemoryOutbox.user_id == user_id, MemoryOutbox.path == path)
        return (await session.execute(stmt)).scalar_one_or_none()


def _naive(value: datetime | None) -> datetime | None:
    return value.replace(tzinfo=None) if value is not None else None


memory_index_repo = MemoryIndexRepository()
memory_usage_repo = MemoryUsageRepository()
memory_outbox_repo = MemoryOutboxRepository()

__all__ = [
    "KEEP",
    "OP_APPEND",
    "OP_DELETE",
    "OP_PUT",
    "IndexedFile",
    "Keep",
    "OutboxEntry",
    "fallback_card_id",
    "memory_index_repo",
    "memory_outbox_repo",
    "memory_usage_repo",
    "title_of",
]

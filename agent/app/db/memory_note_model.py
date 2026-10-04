from datetime import UTC, datetime

from sqlalchemy import JSON, Boolean, Column, DateTime, Index, Integer, String, Text

from app.db.proaction_model import AgentBase


def _now() -> datetime:
    return datetime.now(UTC)


class MemoryNote(AgentBase):
    """The index of a note file — derived from the bucket, rebuildable from it (agent-os/standards/agent/memory.md).

    Only `last_used_at` and `use_count` exist nowhere else: everything else is read back from the
    file, so a full rebuild can drop the table and lose nothing but those two. `deleted_at` is a
    soft delete (a file that vanished from the bucket); `pending_sync` marks a note whose newest
    version is still in the outbox, which the reconciler must never delete.
    """

    __tablename__ = "memory_note"
    __table_args__ = (
        Index("idx_memory_note_user_path", "user_id", "path"),
        Index("idx_memory_note_user_status", "user_id", "status", "deleted_at"),
    )

    id = Column(String(26), primary_key=True)
    user_id = Column(String(36), nullable=False)
    path = Column(String(600), nullable=False)
    status = Column(String(10), nullable=False, default="active")
    title = Column(Text, nullable=False)
    summary = Column(Text, nullable=False, default="")
    tags = Column(JSON, nullable=False, default=list)
    links = Column(JSON, nullable=False, default=list)
    sources = Column(JSON, nullable=False, default=list)
    pinned = Column(Boolean, nullable=False, default=False)
    importance = Column(Integer, nullable=False, default=0)
    confirmed_at = Column(DateTime(timezone=True), nullable=True)
    forget_after = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=True)
    archived_at = Column(DateTime(timezone=True), nullable=True)
    archive_reason = Column(Text, nullable=True)
    body = Column(Text, nullable=False, default="")
    body_hash = Column(String(64), nullable=False, default="")
    etag = Column(String(64), nullable=False, default="")
    updated_at = Column(DateTime(timezone=True), nullable=False, default=_now)
    indexed_at = Column(DateTime(timezone=True), nullable=False, default=_now)
    deleted_at = Column(DateTime(timezone=True), nullable=True)
    pending_sync = Column(Boolean, nullable=False, default=False)
    unreadable = Column(Boolean, nullable=False, default=False)
    unreadable_reason = Column(Text, nullable=True)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    use_count = Column(Integer, nullable=False, default=0)


class MemoryEvent(AgentBase):
    """What happened to the notes — a journal for the inspector (MAG-20), never read to rebuild anything."""

    __tablename__ = "memory_event"
    __table_args__ = (Index("idx_memory_event_user_at", "user_id", "at"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String(36), nullable=False)
    note_id = Column(String(26), nullable=True)
    kind = Column(String(40), nullable=False)
    path = Column(String(600), nullable=True)
    detail = Column(JSON, nullable=False, default=dict)
    at = Column(DateTime(timezone=True), nullable=False, default=_now)


class MemoryOutbox(AgentBase):
    """A write the bucket has not accepted yet. One row per path: a newer write replaces the older one.

    `op` is `put` (write `content` to `path`, then delete `move_from` if it still has `move_from_etag`)
    or `delete` (remove `path` if it still has `base_etag`). `base_etag` is the ETag the writer had
    read (None for a creation). On flush it is compared with
    the bucket's current ETag first: a match is pushed, a mismatch goes through the conflict path.
    """

    __tablename__ = "memory_outbox"
    __table_args__ = (Index("idx_memory_outbox_user_path", "user_id", "path", unique=True),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String(36), nullable=False)
    note_id = Column(String(26), nullable=True)
    path = Column(String(600), nullable=False)
    op = Column(String(10), nullable=False, default="put")
    move_from = Column(String(600), nullable=True)
    move_from_etag = Column(String(64), nullable=True)
    content = Column(Text, nullable=False)
    base_etag = Column(String(64), nullable=True)
    create_only = Column(Boolean, nullable=False, default=False)
    created_at = Column(DateTime(timezone=True), nullable=False, default=_now)
    attempts = Column(Integer, nullable=False, default=0)
    last_error = Column(Text, nullable=True)

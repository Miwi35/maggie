"""The memory index (MAG-195): a throwaway copy of the Markdown folder, plus the two tables that are not.

`memory_file`, `memory_term` and `memory_link` are rebuilt from the files at any time and hold no
truth of their own — only pointers and a copy of the text, so that recall keeps working while the
bucket does not answer. `memory_usage` (counters) and `memory_outbox` (writes waiting for the bucket)
are not rebuildable and are never touched by a rebuild.
"""

from datetime import UTC, datetime

from sqlalchemy import JSON, BigInteger, Column, DateTime, Index, Integer, String, Text

from app.db.base import AgentBase

# What a term is: a situation the fiche should surface for, or another name of its subject.
TERM_EVOQUE = "evoque"
TERM_ALIAS = "alias"
TERM_PERSON = "personne"
LINK_LIEN = "lien"
LINK_SOURCE = "source"

OP_PUT = "put"
OP_DELETE = "delete"
OP_APPEND = "append"


def _now() -> datetime:
    return datetime.now(UTC)


class MemoryFile(AgentBase):
    """One fiche or conversation of a user's folder: its carte and its text. Rebuildable."""

    __tablename__ = "memory_file"
    __table_args__ = (
        Index("idx_memory_file_card", "user_id", "card_id"),
        Index("idx_memory_file_kind", "user_id", "kind"),
    )

    user_id = Column(String(36), primary_key=True)
    path = Column(String(600), primary_key=True)
    kind = Column(String(16), nullable=False)  # fiche | conversation
    card_id = Column(String(64), nullable=False)
    title = Column(String(300), nullable=False)
    resume = Column(Text, nullable=False, default="")
    moments = Column(JSON, nullable=False, default=list)
    personnes = Column(JSON, nullable=False, default=list)
    sources = Column(JSON, nullable=False, default=list)
    body = Column(Text, nullable=False, default="")
    # Title, carte and body in one column: what the French full-text index (GIN, see
    # `context_repo.run_migrations`) is built on.
    search_text = Column(Text, nullable=False, default="")
    content_hash = Column(String(64), nullable=False)
    unreadable = Column(String(300), nullable=True)
    indexed_at = Column(DateTime(timezone=True), nullable=False, default=_now)


class MemoryTerm(AgentBase):
    __tablename__ = "memory_term"
    __table_args__ = (Index("idx_memory_term_lookup", "user_id", "term"),)

    user_id = Column(String(36), primary_key=True)
    path = Column(String(600), primary_key=True)
    kind = Column(String(16), primary_key=True)
    term = Column(String(200), primary_key=True)


class MemoryLink(AgentBase):
    """A pointer from one fiche to another, by id: the id survives a rename, the path does not."""

    __tablename__ = "memory_link"
    __table_args__ = (Index("idx_memory_link_target", "user_id", "target_id"),)

    user_id = Column(String(36), primary_key=True)
    path = Column(String(600), primary_key=True)
    target_id = Column(String(64), primary_key=True)
    kind = Column(String(16), nullable=False)


class MemoryUsage(AgentBase):
    """How often and how lately a carte was used. Keyed on the carte id, so a rename keeps it.

    Never written into the files (it changes at every read and a versioned bucket would keep a
    version each time) and never dropped by a rebuild: it is the one thing the files cannot restore.
    """

    __tablename__ = "memory_usage"

    user_id = Column(String(36), primary_key=True)
    card_id = Column(String(64), primary_key=True)
    use_count = Column(Integer, nullable=False, default=0)
    last_used_at = Column(DateTime(timezone=True), nullable=True)


class MemoryOutbox(AgentBase):
    """A write that has not reached the bucket yet — durable, because the pod keeps nothing on disk.

    One row per file: a newer write replaces the body, and `base_etag` stays the ETag the first
    write started from, which is what the compare-before-push is made against.
    """

    __tablename__ = "memory_outbox"
    __table_args__ = (Index("idx_memory_outbox_user", "user_id"),)

    user_id = Column(String(36), primary_key=True)
    path = Column(String(600), primary_key=True)
    op = Column(String(8), nullable=False)
    body = Column(Text, nullable=True)
    base_etag = Column(String(128), nullable=True)
    attempts = Column(BigInteger, nullable=False, default=0)
    last_error = Column(String(500), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=_now)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=_now)

"""Turning a parsed note file into its index row — the one place that decides what a row holds.

Shared by the reconciler (a file changed in the bucket) and the write door (Maggie wrote it): both
must leave the same row, or a rebuild would not give back the index the agent was running on.
"""

from dataclasses import dataclass
from datetime import UTC, datetime

from app.db.memory_note_model import MemoryNote
from app.memory.frontmatter import NoteDoc, body_hash
from app.memory.paths import OwnedKey


@dataclass(frozen=True)
class Change:
    """What the `memory` Mercure stream tells a client: the note and what happened to it."""

    kind: str  # created | updated | renamed | archived | restored | deleted | conflict | unreadable
    user_id: str
    note_id: str | None
    path: str
    title: str = ""
    status: str = ""


_CONTENT_FIELDS = (
    "title",
    "summary",
    "tags",
    "links",
    "sources",
    "pinned",
    "importance",
    "confirmed_at",
    "forget_after",
    "created_at",
    "archived_at",
    "archive_reason",
    "body",
    "status",
    "path",
)


def _values(doc: NoteDoc, owned: OwnedKey, updated_at: datetime) -> dict:
    archived_at = doc.archived_at
    if owned.status == "archived" and archived_at is None:
        archived_at = updated_at
    return {
        "path": owned.key,
        "status": owned.status,
        "title": doc.title,
        "summary": doc.summary,
        "tags": list(doc.tags),
        "links": list(doc.links),
        "sources": list(doc.sources),
        "pinned": doc.pinned,
        "importance": doc.importance,
        "confirmed_at": doc.confirmed_at,
        "forget_after": doc.forget_after,
        "created_at": doc.created_at,
        "archived_at": archived_at if owned.status == "archived" else None,
        "archive_reason": doc.archive_reason if owned.status == "archived" else None,
        "body": doc.body,
    }


def _same(old: object, new: object) -> bool:
    if isinstance(old, datetime) and isinstance(new, datetime):
        old = old if old.tzinfo else old.replace(tzinfo=UTC)
        new = new if new.tzinfo else new.replace(tzinfo=UTC)
    return old == new


def apply_doc(
    row: MemoryNote | None,
    *,
    note_id: str,
    user_id: str,
    owned: OwnedKey,
    doc: NoteDoc,
    etag: str,
    last_modified: datetime,
) -> tuple[MemoryNote, str | None]:
    """The row for this file, and what changed in it (None: only the ETag moved, nothing a reader sees).

    `updated_at` is the object's LastModified: the file is the only clock the owner's edits have.
    The two usage fields are never written here.
    """
    values = _values(doc, owned, last_modified)
    if row is None:
        row = MemoryNote(id=note_id, user_id=user_id, use_count=0)
        kind: str | None = "created"
    else:
        kind = None
        if row.deleted_at is not None:
            kind = "restored"
        elif row.path != owned.key and row.status == owned.status:
            kind = "renamed"
        elif row.status != owned.status:
            kind = "archived" if owned.status == "archived" else "restored"
        elif any(not _same(getattr(row, name), values[name]) for name in _CONTENT_FIELDS):
            kind = "updated"
    for name, value in values.items():
        setattr(row, name, value)
    row.body_hash = body_hash(doc.body)
    row.etag = etag
    row.updated_at = last_modified
    row.indexed_at = datetime.now(UTC)
    row.deleted_at = None
    row.pending_sync = False
    row.unreadable = False
    row.unreadable_reason = None
    return row, kind

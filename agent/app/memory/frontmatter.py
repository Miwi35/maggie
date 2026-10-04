"""A note file: YAML frontmatter, then the Markdown the owner reads and edits.

The file is the truth, so parsing never guesses silently: a file with broken frontmatter raises
`UnreadableNote` and the reconciler signals it rather than indexing a half-read note or dropping it.
A file with no frontmatter at all is a hand-made note: it parses with `id=None` and the reconciler
stamps one. Keys the owner added are kept in `extra` so a write-back never loses them.
"""

import hashlib
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

import yaml

from app.memory.ulid import is_ulid

_FENCE = "---"
_KNOWN = (
    "id",
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
)


class UnreadableNote(ValueError):
    """The file exists but its frontmatter cannot be read: say so, never index or drop it."""


@dataclass
class NoteDoc:
    title: str
    body: str = ""
    id: str | None = None
    summary: str = ""
    tags: list[str] = field(default_factory=list)
    links: list[str] = field(default_factory=list)
    sources: list[str] = field(default_factory=list)
    pinned: bool = False
    importance: int = 0
    confirmed_at: datetime | None = None
    forget_after: datetime | None = None
    created_at: datetime | None = None
    archived_at: datetime | None = None
    archive_reason: str | None = None
    extra: dict[str, Any] = field(default_factory=dict)


def body_hash(body: str) -> str:
    """Hash of the body alone: what retrieval reads, so a frontmatter-only edit does not look like new content."""
    return hashlib.sha256(body.strip().encode("utf-8")).hexdigest()


def _as_datetime(value: Any, name: str) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, date):
        parsed = datetime(value.year, value.month, value.day)
    elif isinstance(value, str):
        try:
            parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        except ValueError as e:
            raise UnreadableNote(f"`{name}` is not a date: {value!r}") from e
    else:
        raise UnreadableNote(f"`{name}` is not a date: {value!r}")
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _as_text_list(value: Any, name: str) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, list) and all(isinstance(item, str | int | float) for item in value):
        return [str(item) for item in value]
    raise UnreadableNote(f"`{name}` must be a list of text")


def _as_int(value: Any, name: str) -> int:
    if value is None or value == "":
        return 0
    if isinstance(value, bool) or not isinstance(value, int | float | str):
        raise UnreadableNote(f"`{name}` is not a number: {value!r}")
    try:
        return int(value)
    except ValueError as e:
        raise UnreadableNote(f"`{name}` is not a number: {value!r}") from e


def _as_bool(value: Any, name: str) -> bool:
    if value is None:
        return False
    if isinstance(value, bool):
        return value
    raise UnreadableNote(f"`{name}` must be true or false")


def _split(text: str) -> tuple[str | None, str]:
    """(frontmatter YAML or None when the file has none, body)."""
    text = text.removeprefix("﻿").replace("\r\n", "\n")
    if not text.startswith(f"{_FENCE}\n"):
        return None, text
    end = text.find(f"\n{_FENCE}", len(_FENCE))
    while end != -1 and text[end + 1 + len(_FENCE) : end + 2 + len(_FENCE)] not in ("", "\n"):
        end = text.find(f"\n{_FENCE}", end + 1)
    if end == -1:
        raise UnreadableNote("frontmatter is opened with --- but never closed")
    return text[len(_FENCE) + 1 : end + 1], text[end + 1 + len(_FENCE) :]


def parse_note(text: str, fallback_title: str) -> NoteDoc:
    """Parse a note file. `fallback_title` (the file name) names a note whose frontmatter has no title."""
    raw, body = _split(text)
    body = body.lstrip("\n").rstrip()
    if raw is None:
        return NoteDoc(title=fallback_title, body=body)
    try:
        data = yaml.safe_load(raw) if raw.strip() else {}
    except yaml.YAMLError as e:
        raise UnreadableNote(f"frontmatter is not valid YAML: {e}") from e
    if not isinstance(data, dict):
        raise UnreadableNote("frontmatter must be a YAML mapping")

    note_id = data.get("id")
    if note_id is not None:
        note_id = str(note_id).strip().upper()
        if not is_ulid(note_id):
            raise UnreadableNote(f"`id` is not a ULID: {data.get('id')!r}")
    title = data.get("title")
    return NoteDoc(
        id=note_id,
        title=str(title).strip() if title is not None and str(title).strip() else fallback_title,
        body=body,
        summary=str(data.get("summary") or "").strip(),
        tags=_as_text_list(data.get("tags"), "tags"),
        links=_as_text_list(data.get("links"), "links"),
        sources=_as_text_list(data.get("sources"), "sources"),
        pinned=_as_bool(data.get("pinned"), "pinned"),
        importance=_as_int(data.get("importance"), "importance"),
        confirmed_at=_as_datetime(data.get("confirmed_at"), "confirmed_at"),
        forget_after=_as_datetime(data.get("forget_after"), "forget_after"),
        created_at=_as_datetime(data.get("created_at"), "created_at"),
        archived_at=_as_datetime(data.get("archived_at"), "archived_at"),
        archive_reason=(str(data["archive_reason"]).strip() or None) if data.get("archive_reason") else None,
        extra={key: value for key, value in data.items() if key not in _KNOWN},
    )


def render_note(doc: NoteDoc) -> str:
    """The file as written to the bucket. Empty optional fields are left out so a hand-edited file stays readable."""
    data: dict[str, Any] = {"id": doc.id, "title": doc.title}
    if doc.summary:
        data["summary"] = doc.summary
    for name in ("tags", "links", "sources"):
        if getattr(doc, name):
            data[name] = list(getattr(doc, name))
    if doc.pinned:
        data["pinned"] = True
    if doc.importance:
        data["importance"] = doc.importance
    for name in ("confirmed_at", "forget_after", "created_at", "archived_at"):
        value: datetime | None = getattr(doc, name)
        if value is not None:
            data[name] = (value if value.tzinfo else value.replace(tzinfo=UTC)).isoformat()
    if doc.archive_reason:
        data["archive_reason"] = doc.archive_reason
    data.update(doc.extra)
    header = yaml.safe_dump(data, allow_unicode=True, default_flow_style=False, sort_keys=False)
    return f"{_FENCE}\n{header}{_FENCE}\n\n{doc.body.strip()}\n"

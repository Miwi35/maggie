"""Where a note lives in the bucket, and what may never leave its prefix.

    <user>/<slug>.md           one note
    <user>/archive/<slug>.md   archived
    <user>/conflits/<slug>-<timestamp>.md   Maggie's version of a note the owner edited first
    <user>/SOMMAIRE.md         generated
    competences/…              skills (MAG-218): global, outside any user prefix

`<user>` is the API user's ULID. A first path segment that is not one is not a user prefix, so
`competences/` and anything unknown fall out of the note reconciler without being named.
"""

import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime

from app.memory.ulid import is_ulid

ARCHIVE_DIR = "archive"
CONFLICT_DIR = "conflits"
SUMMARY_NAME = "SOMMAIRE.md"
SKILLS_PREFIX = "competences"
MAX_SLUG_LENGTH = 80

_CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f]")
_PATH_SEPARATORS = re.compile(r"[/\\]")
_SPACES = re.compile(r"\s+")


class UnsafePath(ValueError):
    """A user id or a key that would step outside its prefix."""


def check_user_id(user_id: str) -> str:
    """A user id is a ULID: the one shape that cannot hold a separator, a dot or a space."""
    if not is_ulid(user_id):
        raise UnsafePath(f"{user_id!r} is not a user id the bucket can use as a prefix")
    return user_id


def slugify(title: str) -> str:
    """The file name of a title: accents folded, separators and control characters gone, bounded.

    Spaces and case stay, because the owner has to recognise his files. A leading dot goes too,
    so `../x` can only become `x` and no name is hidden. SOMMAIRE is reserved: a note titled that
    way is `SOMMAIRE-2`, whatever its case, so the generated listing is never one of the notes.
    """
    folded = unicodedata.normalize("NFKD", title)
    folded = "".join(c for c in folded if not unicodedata.combining(c))
    folded = _CONTROL.sub("", _SPACES.sub(" ", _PATH_SEPARATORS.sub("", folded)))
    slug = folded.strip().lstrip(".").strip()
    slug = slug[:MAX_SLUG_LENGTH].rstrip(" .")
    if not slug:
        slug = "note"
    if slug.casefold() == SUMMARY_NAME.removesuffix(".md").casefold():
        slug = f"{slug}-2"
    return slug


def with_suffix(slug: str, attempt: int) -> str:
    """`slug`, `slug-2`, `slug-3`… — the way a collision is settled."""
    return slug if attempt <= 1 else f"{slug}-{attempt}"


def note_key(user_id: str, slug: str) -> str:
    return f"{check_user_id(user_id)}/{slug}.md"


def archive_key(user_id: str, slug: str) -> str:
    return f"{check_user_id(user_id)}/{ARCHIVE_DIR}/{slug}.md"


def conflict_key(user_id: str, slug: str, at: datetime) -> str:
    return f"{check_user_id(user_id)}/{CONFLICT_DIR}/{slug}-{at.strftime('%Y%m%dT%H%M%S%fZ')}.md"


def summary_key(user_id: str) -> str:
    return f"{check_user_id(user_id)}/{SUMMARY_NAME}"


def stem(key: str) -> str:
    """`u/archive/Allergies.md` -> `Allergies`."""
    return key.rsplit("/", 1)[-1].removesuffix(".md")


@dataclass(frozen=True)
class OwnedKey:
    user_id: str
    status: str  # "active" or "archived"
    key: str


def classify_key(key: str) -> OwnedKey | None:
    """The owner of a key if the note reconciler reads it, None for everything it must leave alone.

    Only `<user>/*.md` and `<user>/archive/*.md` count. `conflits/`, `SOMMAIRE.md`, `competences/`,
    a deeper path and any unknown prefix are skipped, or Maggie's own conflict copies and the
    generated listing would come back as notes and reach the prompt.
    """
    parts = key.split("/")
    if not parts[0] or not is_ulid(parts[0]) or any(part in ("", ".", "..") for part in parts):
        return None
    name = parts[-1]
    if not name.endswith(".md") or len(name) <= 3:
        return None
    if len(parts) == 2:
        if name.casefold() == SUMMARY_NAME.casefold():
            return None
        return OwnedKey(parts[0], "active", key)
    if len(parts) == 3 and parts[1] == ARCHIVE_DIR:
        return OwnedKey(parts[0], "archived", key)
    return None


def key_belongs_to(user_id: str, key: str) -> bool:
    """A last line of defence before any write or delete: the key sits under this user's own prefix."""
    return key.startswith(f"{check_user_id(user_id)}/") and ".." not in key.split("/")

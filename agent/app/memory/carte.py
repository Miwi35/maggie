"""A file of the memory folder: its carte (YAML frontmatter) and its Markdown body.

The file is the truth, so reading never guesses: a frontmatter that is not a YAML mapping raises
`UnreadableFile` and the indexer reports it instead of indexing half a file or dropping it. A file
with no frontmatter at all is one the owner wrote by hand: it parses with `id=None`. Keys nobody here
knows are kept in `extra`, and `with_id` never re-serialises the frontmatter, so a stamp cannot lose
what the owner wrote.

The carte is the unit recall works on (« Spec : Mémoire vivante »):

    id         identity across renames and moves (`uuid4().hex`)
    resume     a few lines, what this is about
    moments    the key moments, timestamped
    personnes  who is involved
    evoque     the situations and themes it should surface for
    alias      other names the subject goes by
    liens      ids of the fiches this one points to
    sources    conversations and app data behind a fiche: {id, titre, provenance: dit|observé|déduit}
"""

import hashlib
import re
import uuid
from dataclasses import dataclass, field
from typing import Any

import yaml

FENCE = "---"
PROVENANCES = ("dit", "observé", "déduit")
_KNOWN = ("id", "resume", "moments", "personnes", "evoque", "alias", "liens", "sources")
_ID = re.compile(r"^[0-9a-zA-Z_-]{8,64}$")
_FRONT = re.compile(r"\A---[ \t]*\r?\n(.*?)(?:\r?\n)?^---[ \t]*(?:\r?\n|\Z)", re.DOTALL | re.MULTILINE)


class UnreadableFile(ValueError):
    """The file exists but its carte cannot be read: say so, never index or drop it."""


@dataclass
class Carte:
    id: str | None = None
    resume: str = ""
    moments: list[str] = field(default_factory=list)
    personnes: list[str] = field(default_factory=list)
    evoque: list[str] = field(default_factory=list)
    alias: list[str] = field(default_factory=list)
    liens: list[str] = field(default_factory=list)
    sources: list[dict[str, Any]] = field(default_factory=list)
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def linked_ids(self) -> list[str]:
        """Every id this carte points to, links first, then sources, once each."""
        ids = list(self.liens)
        ids.extend(str(s["id"]) for s in self.sources if s.get("id"))
        return list(dict.fromkeys(ids))


@dataclass
class ParsedFile:
    carte: Carte
    body: str
    has_frontmatter: bool = False


def new_id() -> str:
    return uuid.uuid4().hex


def is_valid_id(value: object) -> bool:
    return isinstance(value, str) and _ID.fullmatch(value) is not None


def content_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _strings(value: Any, name: str) -> list[str]:
    if value is None or value == "":
        return []
    if isinstance(value, str):
        return [part.strip() for part in value.split(",") if part.strip()]
    if isinstance(value, list):
        return [str(item).strip() for item in value if item is not None and str(item).strip()]
    raise UnreadableFile(f"`{name}` should be a list, not {type(value).__name__}")


def _moments(value: Any) -> list[str]:
    if value is None or value == "":
        return []
    if isinstance(value, str):
        return [value.strip()]
    if isinstance(value, list):
        out: list[str] = []
        for item in value:
            if isinstance(item, dict):
                out.append(" — ".join(str(v) for v in item.values() if v is not None))
            elif item is not None:
                out.append(str(item).strip())
        return [m for m in out if m]
    raise UnreadableFile(f"`moments` should be a list, not {type(value).__name__}")


def _sources(value: Any) -> list[dict[str, Any]]:
    if value is None or value == "":
        return []
    if not isinstance(value, list):
        raise UnreadableFile(f"`sources` should be a list, not {type(value).__name__}")
    out: list[dict[str, Any]] = []
    for item in value:
        if isinstance(item, dict):
            out.append({str(k): v for k, v in item.items()})
        elif item is not None:
            out.append({"titre": str(item)})
    return out


def split(text: str) -> tuple[str | None, str]:
    """(raw frontmatter, body) — the frontmatter is None when the file does not open on a fence."""
    match = _FRONT.match(text.lstrip("﻿"))
    if match is None:
        return None, text
    return match.group(1), text.lstrip("﻿")[match.end() :]


def parse(text: str) -> ParsedFile:
    raw, body = split(text)
    if raw is None:
        if text.lstrip("﻿").startswith(FENCE):
            raise UnreadableFile("the frontmatter is never closed")
        return ParsedFile(Carte(), body.strip(), has_frontmatter=False)
    try:
        data = yaml.safe_load(raw) if raw.strip() else {}
    except yaml.YAMLError as e:
        raise UnreadableFile(f"the frontmatter is not valid YAML: {e}") from e
    if data is None:
        data = {}
    if not isinstance(data, dict):
        raise UnreadableFile("the frontmatter is not a mapping")
    identifier = data.get("id")
    if identifier is not None and not is_valid_id(str(identifier)):
        raise UnreadableFile(f"`id` is not usable: {identifier!r}")
    carte = Carte(
        id=str(identifier) if identifier is not None else None,
        resume=str(data.get("resume") or "").strip(),
        moments=_moments(data.get("moments")),
        personnes=_strings(data.get("personnes"), "personnes"),
        evoque=_strings(data.get("evoque"), "evoque"),
        alias=_strings(data.get("alias"), "alias"),
        liens=_strings(data.get("liens"), "liens"),
        sources=_sources(data.get("sources")),
        extra={k: v for k, v in data.items() if k not in _KNOWN},
    )
    return ParsedFile(carte, body.strip(), has_frontmatter=True)


def with_id(text: str, identifier: str) -> str:
    """The same file with `id` written first in its carte, nothing else touched.

    An existing `id:` line is replaced in place; a file with a carte gets the line right after the
    opening fence; a file without one gets a carte of its own. The owner's frontmatter is never
    re-serialised, so his comments and key order survive.
    """
    text = text.lstrip("﻿")
    raw, body = split(text)
    if raw is None:
        return f"{FENCE}\nid: {identifier}\n{FENCE}\n\n{text.lstrip(chr(10))}"
    lines = raw.split("\n")
    for index, line in enumerate(lines):
        if re.match(r"^id\s*:", line):
            lines[index] = f"id: {identifier}"
            break
    else:
        lines.insert(0, f"id: {identifier}")
    return f"{FENCE}\n" + "\n".join(lines) + f"\n{FENCE}\n" + body


def render(carte: Carte, body: str) -> str:
    """A new file from a carte and a body — for what Maggie writes, never to rewrite what the owner wrote."""
    data: dict[str, Any] = {}
    if carte.id:
        data["id"] = carte.id
    if carte.resume:
        data["resume"] = carte.resume
    for name in ("moments", "personnes", "evoque", "alias", "liens", "sources"):
        value = getattr(carte, name)
        if value:
            data[name] = value
    data.update(carte.extra)
    head = yaml.safe_dump(data, allow_unicode=True, sort_keys=False, default_flow_style=False).rstrip("\n")
    return f"{FENCE}\n{head}\n{FENCE}\n\n{body.strip()}\n"


def search_text(path_title: str, carte: Carte, body: str) -> str:
    """What the full-text search reads: the title first, then the carte, then the body."""
    parts = [path_title, carte.resume, *carte.personnes, *carte.evoque, *carte.alias, *carte.moments, body]
    return "\n".join(part for part in parts if part)

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from app.common.frontmatter import parse_frontmatter


@dataclass
class SkillEntry:
    name: str
    description: str
    tags: list[str]
    content: str = field(default="", repr=False, compare=False)


def render_markdown(name: str, description: str, tags: list[str], content: str) -> str:
    """A skill as a Markdown file with YAML frontmatter — what get_skill returns and the skill folder holds."""
    frontmatter = yaml.dump(
        {"name": name, "description": description, "tags": tags}, allow_unicode=True, default_flow_style=False
    )
    return f"---\n{frontmatter}---\n\n{content}\n"


def parse_markdown(source: str | bytes | Path, default_name: str = "") -> tuple[SkillEntry, str] | None:
    """Parse a skill document (YAML frontmatter + body); None if it has no valid frontmatter.

    `source` is the text or the bytes of an object of the bucket, or a file on disk. A frontmatter with no
    `name` takes `default_name` (a file's stem, for a path).
    """
    if isinstance(source, Path):
        default_name = default_name or source.stem
        source = source.read_text(encoding="utf-8")
    elif isinstance(source, bytes):
        source = source.decode("utf-8", errors="replace")
    parsed = parse_frontmatter(source)
    if parsed is None:
        return None
    frontmatter, body = parsed
    name = str(frontmatter.get("name") or default_name).strip()
    if not name:
        return None
    tags = frontmatter.get("tags") or []
    entry = SkillEntry(
        name=name,
        description=str(frontmatter.get("description") or ""),
        tags=[str(tag) for tag in tags] if isinstance(tags, list) else [str(tags)],
    )
    return entry, body

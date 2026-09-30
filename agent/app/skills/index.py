import logging
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


@dataclass
class SkillEntry:
    name: str
    description: str
    tags: list[str]
    file_path: Path


@dataclass
class SkillIndex:
    """In-memory index of skill files with YAML frontmatter."""

    directory: Path
    entries: list[SkillEntry] = field(default_factory=list)
    _publisher: MercurePublisher = field(default_factory=MercurePublisher)

    def rebuild(self) -> None:
        """Scan skill directory, parse YAML frontmatter, build index."""
        self.entries.clear()
        if not self.directory.exists():
            self.directory.mkdir(parents=True, exist_ok=True)
            return

        for path in sorted(self.directory.glob("*.md")):
            try:
                entry = self._parse_file(path)
                if entry:
                    self.entries.append(entry)
            except Exception as e:
                logger.warning(f"Failed to parse skill file {path}: {e}")

    def _parse_file(self, path: Path) -> SkillEntry | None:
        """Parse a skill markdown file with YAML frontmatter."""
        text = path.read_text(encoding="utf-8")
        if not text.startswith("---"):
            return None

        parts = text.split("---", 2)
        if len(parts) < 3:
            return None

        frontmatter = yaml.safe_load(parts[1])
        if not isinstance(frontmatter, dict):
            return None

        return SkillEntry(
            name=frontmatter.get("name", path.stem),
            description=frontmatter.get("description", ""),
            tags=frontmatter.get("tags", []),
            file_path=path,
        )

    def get(self, name: str) -> str | None:
        """Load full skill file content by name."""
        for entry in self.entries:
            if entry.name == name:
                try:
                    return entry.file_path.read_text(encoding="utf-8")
                except Exception:
                    return None
        return None

    def list_all(self) -> list[SkillEntry]:
        """Return all skill entries."""
        return list(self.entries)

    async def create(self, name: str, description: str, tags: list[str], content: str, user_id: str) -> SkillEntry:
        """Write a new skill .md file, update index, publish to Mercure."""
        filename = re.sub(r"[^a-z0-9-]", "-", name.lower().strip())
        filename = re.sub(r"-+", "-", filename).strip("-")
        file_path = self.directory / f"{filename}.md"

        frontmatter = {"name": name, "description": description, "tags": tags}
        file_content = f"---\n{yaml.dump(frontmatter, allow_unicode=True, default_flow_style=False)}---\n\n{content}\n"
        file_path.write_text(file_content, encoding="utf-8")

        entry = SkillEntry(name=name, description=description, tags=tags, file_path=file_path)
        self.entries.append(entry)

        try:
            await self._publisher.publish(
                f"/skills/{user_id}",
                {"name": name, "description": description, "tags": tags},
            )
        except Exception as e:
            logger.warning(f"Failed to publish skill creation: {e}")

        return entry

    async def update(
        self,
        name: str,
        description: str | None = None,
        tags: list[str] | None = None,
        content: str | None = None,
        user_id: str = "default",
    ) -> SkillEntry | None:
        """Update an existing skill file, rebuild entry, publish to Mercure."""
        entry = next((e for e in self.entries if e.name == name), None)
        if not entry:
            return None

        # Read existing content
        text = entry.file_path.read_text(encoding="utf-8")
        parts = text.split("---", 2)
        existing_body = parts[2].strip() if len(parts) >= 3 else ""
        existing_fm = yaml.safe_load(parts[1]) if len(parts) >= 3 else {}

        new_desc = description if description is not None else existing_fm.get("description", entry.description)
        new_tags = tags if tags is not None else existing_fm.get("tags", entry.tags)
        new_body = content if content is not None else existing_body

        frontmatter = {"name": name, "description": new_desc, "tags": new_tags}
        file_content = f"---\n{yaml.dump(frontmatter, allow_unicode=True, default_flow_style=False)}---\n\n{new_body}\n"
        entry.file_path.write_text(file_content, encoding="utf-8")

        entry.description = new_desc
        entry.tags = new_tags

        try:
            await self._publisher.publish(
                f"/skills/{user_id}",
                {"name": name, "description": new_desc, "tags": new_tags},
            )
        except Exception as e:
            logger.warning(f"Failed to publish skill update: {e}")

        return entry

    async def delete(self, name: str, user_id: str = "default") -> bool:
        """Delete skill file, remove from index, publish deletion to Mercure."""
        entry = next((e for e in self.entries if e.name == name), None)
        if not entry:
            return False

        try:
            entry.file_path.unlink(missing_ok=True)
        except Exception as e:
            logger.warning(f"Failed to delete skill file: {e}")
            return False

        self.entries.remove(entry)

        try:
            await self._publisher.publish(f"/skills/{user_id}", {"name": name, "deleted": True})
        except Exception as e:
            logger.warning(f"Failed to publish skill deletion: {e}")

        return True

    def get_skills_index(self) -> str:
        """Build the system prompt section listing every skill (name and description only), sorted by name."""
        if not self.entries:
            return ""
        lines = ["\n\nCompétences disponibles (charge-les avec get_skill avant d'agir) :"]
        for entry in sorted(self.entries, key=lambda e: e.name):
            lines.append(f"- {entry.name}: {entry.description}")
        return "\n".join(lines)


skill_index = SkillIndex(Path("/app/data/skills"))

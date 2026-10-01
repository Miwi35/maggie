import logging
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from app.db.skill_model import Skill
from app.db.skill_repository import SkillRepository, skill_repo
from app.mercure import topics
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)

LEGACY_SKILLS_DIR = Path("/app/data/skills")


@dataclass
class SkillEntry:
    name: str
    description: str
    tags: list[str]


def render_markdown(name: str, description: str, tags: list[str], content: str) -> str:
    """A skill as a Markdown file with YAML frontmatter — what get_skill returns and the Markdown mirror carries."""
    frontmatter = yaml.dump(
        {"name": name, "description": description, "tags": tags}, allow_unicode=True, default_flow_style=False
    )
    return f"---\n{frontmatter}---\n\n{content}\n"


def parse_markdown(path: Path) -> tuple[SkillEntry, str] | None:
    """Parse a legacy skill file (YAML frontmatter + body); None if it has no valid frontmatter."""
    text = path.read_text(encoding="utf-8")
    parts = text.split("---", 2)
    if not text.startswith("---") or len(parts) < 3:
        return None
    frontmatter = yaml.safe_load(parts[1])
    if not isinstance(frontmatter, dict):
        return None
    entry = SkillEntry(
        name=str(frontmatter.get("name", path.stem)),
        description=frontmatter.get("description") or "",
        tags=frontmatter.get("tags") or [],
    )
    return entry, parts[2].strip()


@dataclass
class SkillIndex:
    """In-memory index (name, description, tags) of the skills stored in `maggie_agent`; bodies stay in the database."""

    repo: SkillRepository = field(default_factory=lambda: skill_repo)
    entries: list[SkillEntry] = field(default_factory=list)
    _publisher: MercurePublisher = field(default_factory=MercurePublisher)

    async def rebuild(self) -> None:
        """Reload the index from the database. Errors propagate: an unreadable store must not look like no skills."""
        skills = await self.repo.list_all()
        self.entries = [SkillEntry(name=s.name, description=s.description, tags=list(s.tags or [])) for s in skills]

    async def import_legacy_files(self, directory: Path = LEGACY_SKILLS_DIR) -> int:
        """Move skill files left in the old container directory into the database, never overwriting a stored skill."""
        if not directory.is_dir():
            return 0
        imported = 0
        for path in sorted(directory.glob("*.md")):
            try:
                parsed = parse_markdown(path)
                if parsed is None:
                    continue
                entry, body = parsed
                if await self.repo.get(entry.name) is None:
                    await self.repo.upsert(entry.name, entry.description, entry.tags, body)
                    imported += 1
            except Exception as e:
                logger.warning(f"Failed to import legacy skill file {path}: {e}")
        return imported

    async def get_skill(self, name: str) -> Skill | None:
        return await self.repo.get(name)

    async def get(self, name: str) -> str | None:
        """Full skill as Markdown (frontmatter + body), or None if unknown."""
        skill = await self.repo.get(name)
        if skill is None:
            return None
        return render_markdown(skill.name, skill.description, list(skill.tags or []), skill.content)

    def list_all(self) -> list[SkillEntry]:
        return list(self.entries)

    def _remember(self, entry: SkillEntry) -> None:
        self.entries = [e for e in self.entries if e.name != entry.name] + [entry]

    async def _publish(self, user_id: str, payload: dict) -> None:
        try:
            await self._publisher.publish(topics.for_user(topics.SKILLS, user_id), payload)
        except Exception as e:
            logger.warning(f"Failed to publish skill event: {e}")

    async def create(self, name: str, description: str, tags: list[str], content: str, user_id: str) -> SkillEntry:
        """Store a skill (replacing one of the same name), update the index, publish to Mercure."""
        skill = await self.repo.upsert(name, description, tags, content)
        entry = SkillEntry(name=skill.name, description=skill.description, tags=list(skill.tags or []))
        self._remember(entry)
        await self._publish(user_id, {"name": entry.name, "description": entry.description, "tags": entry.tags})
        return entry

    async def update(
        self,
        name: str,
        description: str | None = None,
        tags: list[str] | None = None,
        content: str | None = None,
        user_id: str = "default",
    ) -> SkillEntry | None:
        skill = await self.repo.update(name, description=description, tags=tags, content=content)
        if skill is None:
            return None
        entry = SkillEntry(name=skill.name, description=skill.description, tags=list(skill.tags or []))
        self._remember(entry)
        await self._publish(user_id, {"name": entry.name, "description": entry.description, "tags": entry.tags})
        return entry

    async def delete(self, name: str, user_id: str = "default") -> bool:
        if not await self.repo.delete(name):
            return False
        self.entries = [e for e in self.entries if e.name != name]
        await self._publish(user_id, {"name": name, "deleted": True})
        return True

    def get_skills_index(self) -> str:
        """Build the system prompt section listing every skill (name and description only), sorted by name."""
        if not self.entries:
            return ""
        lines = ["\n\nCompétences disponibles (charge-les avec get_skill avant d'agir) :"]
        for entry in sorted(self.entries, key=lambda e: e.name):
            lines.append(f"- {entry.name}: {entry.description}")
        return "\n".join(lines)


skill_index = SkillIndex()

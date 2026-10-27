import logging
from dataclasses import dataclass, field
from pathlib import Path

from app.db.skill_model import Skill
from app.db.skill_repository import SkillRepository, skill_repo
from app.mercure import topics
from app.mercure.publisher import MercurePublisher
from app.skills.document import SkillEntry, parse_markdown, render_markdown
from app.skills.store import SkillFiles, skill_files

logger = logging.getLogger(__name__)

LEGACY_SKILLS_DIR = Path("/app/data/skills")

# When a skill applies, as its tags say (MAG-345): `moment:chat`, `moment:proaction`, `moment:planification`.
# The skills of the moment a prompt is built for are given in full; the others stay in the index.
MOMENT_TAG_PREFIX = "moment:"
MOMENT_CHAT = "chat"
MOMENT_PROACTION = "proaction"
MOMENT_PLANNING = "planification"
# Room the full skills of one moment may take in a prompt (~2k tokens). A skill that does
# not fit any more stays in the index, to be loaded with get_skill.
MOMENT_SKILLS_MAX_CHARS = 8000


@dataclass
class SkillIndex:
    """In-memory index of the skills. They are Markdown files (`SkillFiles`); the `skill` table in `maggie_agent`
    is the index derived from them, which this one is loaded from."""

    repo: SkillRepository = field(default_factory=lambda: skill_repo)
    store: SkillFiles = field(default_factory=lambda: skill_files)
    entries: list[SkillEntry] = field(default_factory=list)
    _publisher: MercurePublisher = field(default_factory=MercurePublisher)

    async def rebuild(self) -> None:
        """Reload the index from the table. Errors propagate: an unreadable store must not look like no skills."""
        skills = await self.repo.list_all()
        self.entries = [_entry(s) for s in skills]

    async def refresh(self) -> None:
        """Reload before building a prompt: first take what the owner changed in the skill files (a bounded
        wait, like the memory's), then read the table they feed. Each worker process holds its own copy, so a
        skill created through another one is only seen after a reload. A failed reload keeps the last index:
        a passing hiccup must not take the chat down."""
        try:
            await self.store.catch_up()
            await self.rebuild()
        except Exception as e:
            logger.warning(f"Could not refresh the skill index, keeping the last one: {e}")

    async def import_legacy_files(self, directory: Path = LEGACY_SKILLS_DIR) -> int:
        """Move skill files left in the old container directory into the table, never overwriting a stored skill.
        The skills folder takes them from there when it is first filled."""
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
        """Write a skill's file (replacing one of the same name), update the table and the index, publish to Mercure."""
        await self.store.save(name, description, tags, content)
        skill = await self.repo.upsert(name, description, tags, content)
        entry = _entry(skill)
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
        current = self.store.load(name)
        if current is None:
            stored = await self.repo.get(name)
            if stored is None:
                return None
            current = (_entry(stored), stored.content or "")
        entry, body = current
        new_description = entry.description if description is None else description
        new_tags = entry.tags if tags is None else tags
        new_content = body if content is None else content
        await self.store.save(name, new_description, new_tags, new_content)
        skill = await self.repo.upsert(name, new_description, new_tags, new_content)
        entry = _entry(skill)
        self._remember(entry)
        await self._publish(user_id, {"name": entry.name, "description": entry.description, "tags": entry.tags})
        return entry

    async def delete(self, name: str, user_id: str = "default") -> bool:
        removed = await self.store.remove(name)
        if not await self.repo.delete(name) and not removed:
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

    def skills_for_moment(self, moment: str) -> str:
        """The full content of the skills tagged `moment:<moment>`, sorted by name, as a prompt section.

        Read from the entries `refresh()` just loaded. A skill that would take the section past
        MOMENT_SKILLS_MAX_CHARS is left out: it is still in the index, to be loaded with get_skill.
        Empty when no skill is tagged for the moment.
        """
        tag = f"{MOMENT_TAG_PREFIX}{moment}"
        header = "Compétences à appliquer maintenant (déjà chargées, inutile d'appeler get_skill) :"
        sections: list[str] = []
        size = len(header)
        for entry in sorted(self.entries, key=lambda e: e.name):
            if tag not in entry.tags:
                continue
            section = f"\n\n## {entry.name}\n{entry.content.strip()}"
            if size + len(section) > MOMENT_SKILLS_MAX_CHARS:
                logger.warning(f"Skill {entry.name} left out of the {moment} prompt: over the size cap")
                continue
            sections.append(section)
            size += len(section)
        if not sections:
            return ""
        return header + "".join(sections)


def _entry(skill: Skill) -> SkillEntry:
    return SkillEntry(
        name=skill.name, description=skill.description, tags=list(skill.tags or []), content=skill.content or ""
    )


skill_index = SkillIndex()

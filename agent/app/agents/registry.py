import logging
from dataclasses import dataclass, field
from pathlib import Path

from app.common.frontmatter import parse_frontmatter
from app.config import settings

logger = logging.getLogger(__name__)

AGENTS_DIR = Path("/app/data/agents")
DEFAULT_MAX_ITERATIONS = 8


@dataclass(frozen=True)
class SubagentDefinition:
    name: str
    description: str
    model: str
    tools: list[str]
    prompt: str
    max_iterations: int = DEFAULT_MAX_ITERATIONS

    @property
    def model_id(self) -> str:
        """The model the alias stands for."""
        return settings.model_aliases[self.model]


def parse_subagent(path: Path) -> SubagentDefinition:
    """Read one agent file; ValueError says why it is unusable."""
    parsed = parse_frontmatter(path.read_text(encoding="utf-8"))
    if parsed is None:
        raise ValueError("no valid YAML frontmatter")
    meta, prompt = parsed

    name = str(meta.get("name") or path.stem)
    description = meta.get("description")
    if not isinstance(description, str) or not description.strip():
        raise ValueError("'description' is required")
    model = meta.get("model")
    if model not in settings.model_aliases:
        raise ValueError(f"unknown model {model!r}, expected one of {sorted(settings.model_aliases)}")
    tools = meta.get("tools")
    if not isinstance(tools, list) or not all(isinstance(t, str) for t in tools):
        raise ValueError("'tools' must be a list of tool names or patterns")
    max_iterations = meta.get("max_iterations", DEFAULT_MAX_ITERATIONS)
    if not isinstance(max_iterations, int) or isinstance(max_iterations, bool) or max_iterations < 1:
        raise ValueError("'max_iterations' must be a positive integer")
    if not prompt:
        raise ValueError("the file has no system prompt after the frontmatter")

    return SubagentDefinition(
        name=name,
        description=description.strip(),
        model=model,
        tools=tools,
        prompt=prompt,
        max_iterations=max_iterations,
    )


@dataclass
class SubagentRegistry:
    """The sub-agents Maggie may delegate to, loaded once from Markdown files at startup."""

    agents: dict[str, SubagentDefinition] = field(default_factory=dict)

    def load(self, directory: Path = AGENTS_DIR) -> int:
        """Replace the registry with the valid agent files of `directory`; an invalid file is skipped with a warning."""
        agents: dict[str, SubagentDefinition] = {}
        if directory.is_dir():
            for path in sorted(directory.glob("*.md")):
                try:
                    definition = parse_subagent(path)
                except Exception as e:
                    logger.warning(f"Ignoring sub-agent file {path}: {e}")
                    continue
                if definition.name in agents:
                    logger.warning(f"Ignoring sub-agent file {path}: name '{definition.name}' is already taken")
                    continue
                agents[definition.name] = definition
        self.agents = agents
        return len(agents)

    def get(self, name: str) -> SubagentDefinition | None:
        return self.agents.get(name)

    def list_all(self) -> list[SubagentDefinition]:
        return sorted(self.agents.values(), key=lambda a: a.name)


subagent_registry = SubagentRegistry()

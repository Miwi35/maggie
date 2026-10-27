import re

import yaml

# The fences are lines of their own: a `---` inside a value (a description saying « a --- b ») is not one.
_FRONTMATTER = re.compile(r"\A---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n(.*))?\Z", re.DOTALL)


def parse_frontmatter(text: str) -> tuple[dict, str] | None:
    """Split a Markdown file into its YAML frontmatter and its stripped body; None if there is no valid mapping."""
    found = _FRONTMATTER.match(text)
    if found is None:
        return None
    try:
        frontmatter = yaml.safe_load(found.group(1))
    except yaml.YAMLError:
        return None
    if not isinstance(frontmatter, dict):
        return None
    return frontmatter, (found.group(2) or "").strip()

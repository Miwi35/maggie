import yaml


def parse_frontmatter(text: str) -> tuple[dict, str] | None:
    """Split a Markdown file into its YAML frontmatter and its stripped body; None if there is no valid mapping."""
    parts = text.split("---", 2)
    if not text.startswith("---") or len(parts) < 3:
        return None
    try:
        frontmatter = yaml.safe_load(parts[1])
    except yaml.YAMLError:
        return None
    if not isinstance(frontmatter, dict):
        return None
    return frontmatter, parts[2].strip()

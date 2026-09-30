"""Prompt caching helpers: keep the stable prefix (tools, then system) cacheable, volatile text after it."""

EPHEMERAL = {"type": "ephemeral"}


def build_system(stable: str, volatile: str = "") -> list[dict]:
    """System blocks: the cached stable prefix, then the volatile part (memory, contexts, date, preamble)."""
    blocks: list[dict] = [{"type": "text", "text": stable, "cache_control": EPHEMERAL}]
    if volatile.strip():
        blocks.append({"type": "text", "text": volatile.strip()})
    return blocks


def cache_tools(tools: list[dict] | None) -> list[dict] | None:
    """Return the tools with a cache breakpoint on the last one, so the tool list is cached on its own."""
    if not tools:
        return tools
    return [*tools[:-1], {**tools[-1], "cache_control": EPHEMERAL}]

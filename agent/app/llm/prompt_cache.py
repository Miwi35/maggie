"""Prompt caching helpers: keep the stable prefix (tools, then system) cacheable, volatile text after it."""

EPHEMERAL = {"type": "ephemeral"}


def build_system(stable: str, volatile: str = "", moment: str = "") -> list[dict]:
    """System blocks: the cached stable prefix, then the volatile part (memory, contexts, date, preamble).

    `moment` is the full skills of the moment the prompt is for (MAG-345). It differs between chat,
    proaction and planning, so it gets a breakpoint of its own after the prefix: the three share the
    cached prefix, and each caches its own skills, which change only when a skill does.
    """
    blocks: list[dict] = [{"type": "text", "text": stable, "cache_control": EPHEMERAL}]
    if moment.strip():
        blocks.append({"type": "text", "text": moment.strip(), "cache_control": EPHEMERAL})
    if volatile.strip():
        blocks.append({"type": "text", "text": volatile.strip()})
    return blocks


def cache_tools(tools: list[dict] | None) -> list[dict] | None:
    """Return the tools with a cache breakpoint on the last one, so the tool list is cached on its own."""
    if not tools:
        return tools
    return [*tools[:-1], {**tools[-1], "cache_control": EPHEMERAL}]

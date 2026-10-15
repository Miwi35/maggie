"""Prompt caching helpers: keep the stable prefix (tools, then system) cacheable, volatile text after it."""

EPHEMERAL = {"type": "ephemeral"}


def build_system(
    stable: str, volatile: str = "", moment: str = "", profile: str = "", recalled: str = ""
) -> list[dict]:
    """System blocks: the cached stable prefix, the memory, then the volatile part (contexts, date, preamble).

    `moment` is the full skills of the moment the prompt is for (MAG-345). It differs between chat,
    proaction and planning, so it gets a breakpoint of its own after the prefix: the three share the
    cached prefix, and each caches its own skills, which change only when a skill does.

    The memory (MAG-16) has a place of its own between those and the volatile text, so that a change of
    memory rewrites only it: `profile`, the permanent facts, is cached (it changes when a fiche does);
    `recalled`, the cartes that came back for this turn, is not (it changes with every message).
    Four breakpoints at most, the tools' included: tools, stable, moment, profile.
    """
    blocks: list[dict] = [{"type": "text", "text": stable, "cache_control": EPHEMERAL}]
    if moment.strip():
        blocks.append({"type": "text", "text": moment.strip(), "cache_control": EPHEMERAL})
    if profile.strip():
        blocks.append({"type": "text", "text": profile.strip(), "cache_control": EPHEMERAL})
    if recalled.strip():
        blocks.append({"type": "text", "text": recalled.strip()})
    if volatile.strip():
        blocks.append({"type": "text", "text": volatile.strip()})
    return blocks


def cache_tools(tools: list[dict] | None) -> list[dict] | None:
    """Return the tools with a cache breakpoint on the last one, so the tool list is cached on its own."""
    if not tools:
        return tools
    return [*tools[:-1], {**tools[-1], "cache_control": EPHEMERAL}]

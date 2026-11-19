"""Prompt caching helpers: keep the stable prefix (tools, then system) cacheable, volatile text after it."""

from app.llm.web_search import WEB_SEARCH_TOOL

EPHEMERAL = {"type": "ephemeral"}
EPHEMERAL_1H = {"type": "ephemeral", "ttl": "1h"}


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
    Four breakpoints at most, the tools' included: tools (1 h), stable (1 h), moment (5 min), profile (5 min).
    The API wants every 1 h entry before any 5 min one. What chat, proaction and planning share is the tool
    list; the stable block differs by its capabilities line.
    """
    blocks: list[dict] = [{"type": "text", "text": stable, "cache_control": EPHEMERAL_1H}]
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
    """The tools with a 1 h breakpoint on the last shared one; `web_search`, always last, gets none."""
    if not tools:
        return tools
    index = next((i for i in range(len(tools) - 1, -1, -1) if tools[i].get("name") != WEB_SEARCH_TOOL), None)
    if index is None:
        return list(tools)
    return [{**tool, "cache_control": EPHEMERAL_1H} if i == index else tool for i, tool in enumerate(tools)]

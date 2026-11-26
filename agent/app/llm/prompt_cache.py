"""Prompt caching helpers: keep the stable prefix (tools, then system) cacheable, volatile text after it."""

from app.llm.web_search import WEB_SEARCH_TOOL

EPHEMERAL = {"type": "ephemeral"}
EPHEMERAL_1H = {"type": "ephemeral", "ttl": "1h"}
MAX_BREAKPOINTS = 4


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
    From the second step of a turn, `cache_history` puts a fifth on the history and takes the last 5 min one back.
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


def _breakpoints(blocks: object) -> int:
    if not isinstance(blocks, list):
        return 0
    return sum(1 for block in blocks if isinstance(block, dict) and "cache_control" in block)


def cache_history(system, tools: list[dict] | None, messages: list[dict], iteration: int):
    """The system and messages to send at `iteration`, with a 5 min breakpoint on the last block of the history.

    From the second step of a turn (`iteration >= 1`) the previous rounds are read back from the cache. When the
    four breakpoints are taken, the history's replaces the last 5 min one of the system (the profile, else the
    moment). Pure: the inputs, which the turn keeps and stores, are never changed.
    """
    if iteration == 0 or not messages:
        return system, messages
    last = messages[-1]
    content = last.get("content")
    if isinstance(content, str):
        blocks = [{"type": "text", "text": content, "cache_control": EPHEMERAL}]
    elif isinstance(content, list) and content and isinstance(content[-1], dict):
        blocks = [*content[:-1], {**content[-1], "cache_control": EPHEMERAL}]
    else:
        return system, messages
    sent = [*messages[:-1], {**last, "content": blocks}]
    if isinstance(system, list) and _breakpoints(tools) + _breakpoints(system) + 1 > MAX_BREAKPOINTS:
        index = next((i for i in range(len(system) - 1, -1, -1) if system[i].get("cache_control") == EPHEMERAL), None)
        if index is not None:
            system = [
                {k: v for k, v in block.items() if k != "cache_control"} if i == index else block
                for i, block in enumerate(system)
            ]
    return system, sent

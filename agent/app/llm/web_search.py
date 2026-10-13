"""Anthropic's server-side web search, and the sources it cites (MAG-384).

Maggie had no access to the web: `search` only reads her own data. The model can search
the web by itself when it is offered Anthropic's `web_search` server tool — no third-party
service, no extra key. The search runs on Anthropic's side, inside the same response:

- the response holds a `server_tool_use` block (the query), a `web_search_tool_result`
  block (the pages), then `text` blocks whose `citations` point at those pages;
- `stop_reason` stays `end_turn`, or is `pause_turn` when a long search hit the server's own
  iteration limit — the caller sends the response back as it is to let it go on.

Nothing here is a tool the router runs. What this module owns is the request (`with_web_search`)
and the two things the loops read off a response: the answer text (`answer_text`) and the
sources it cites (`with_sources`).

The sources travel as text. A message is a `role` and a TEXT `content` everywhere downstream —
the stored message, the history of the next turn, the AG-UI stream, both clients — so the
citations become one final line, `Sources : domaine — lien · domaine — lien`, written into the
answer itself. That is what the next turn reads back, and what the clients turn into links.
"""

from typing import Any
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

from app.config import settings

WEB_SEARCH_TOOL = "web_search"
WEB_SEARCH_TYPE = "web_search_20250305"
SERVER_TOOL_USE = "server_tool_use"
WEB_SEARCH_RESULT = "web_search_tool_result"
PAUSE_TURN = "pause_turn"

# A line the user reads: past this, it stops being one.
MAX_SOURCES = 4
SOURCE_SEPARATOR = " · "


def web_search_tool(timezone: ZoneInfo | None = None) -> dict | None:
    """The tool definition the API takes, or `None` when `WEB_SEARCH_MAX_USES` is 0 (search switched off).

    `max_uses` bounds the searches of one request: the API refuses to go past it, so a turn
    cannot loop on the web. The location is a hint for local questions (« les horaires de la
    mairie »), approximate on purpose.
    """
    if settings.web_search_max_uses <= 0:
        return None
    location: dict[str, str] = {"type": "approximate"}
    if settings.web_search_country:
        location["country"] = settings.web_search_country
    if timezone:
        location["timezone"] = timezone.key
    tool: dict[str, Any] = {"type": WEB_SEARCH_TYPE, "name": WEB_SEARCH_TOOL, "max_uses": settings.web_search_max_uses}
    if len(location) > 1:
        tool["user_location"] = location
    return tool


def with_web_search(tools: list[dict], timezone: ZoneInfo | None = None) -> list[dict]:
    """The tools plus the web search, put first: the last tool carries the prompt-cache breakpoint."""
    tool = web_search_tool(timezone)
    return [tool, *tools] if tool else list(tools)


def _get(block: Any, key: str) -> Any:
    return block.get(key) if isinstance(block, dict) else getattr(block, key, None)


def answer_text(content: list) -> str:
    """The text a response says *after* its last search.

    What comes before a search is the model announcing it (« je cherche… »), and glued to
    the answer it reads as one broken sentence — the same rule as the steps before a tool
    call (MAG-229). With no search in the response, this is all of its text.
    """
    parts: list[str] = []
    for block in content:
        kind = _get(block, "type")
        if kind == SERVER_TOOL_USE:
            parts.clear()
        elif kind == "text":
            parts.append(_get(block, "text") or "")
    return "".join(parts)


def searched(content: list) -> bool:
    return any(_get(block, "type") == SERVER_TOOL_USE for block in content)


def search_failed(block: Any) -> bool:
    """Whether a `web_search_tool_result` block carries an error (too many requests, unavailable…)."""
    result = _get(block, "content")
    return _get(result, "type") == "web_search_tool_result_error" if result is not None else False


def domain(url: str) -> str:
    host = urlparse(url).hostname or ""
    return host.removeprefix("www.")


def cited_sources(content: list) -> list[tuple[str, str]]:
    """The `(domain, url)` of every page the answer cites, once each, in the order it cites them."""
    found: dict[str, str] = {}
    for block in content:
        if _get(block, "type") != "text":
            continue
        for citation in _get(block, "citations") or []:
            url = _get(citation, "url")
            if _get(citation, "type") == "web_search_result_location" and isinstance(url, str) and url:
                found.setdefault(url, domain(url) or url)
    return [(host, url) for url, host in found.items()][:MAX_SOURCES]


def sources_line(content: list) -> str:
    """`Sources : domaine — lien · domaine — lien`, or an empty string when nothing is cited."""
    sources = cited_sources(content)
    if not sources:
        return ""
    label = "Source" if len(sources) == 1 else "Sources"
    return f"{label} : " + SOURCE_SEPARATOR.join(f"{host} — {url}" for host, url in sources)


def with_sources(text: str, content: list) -> str:
    """The answer, closed by the line of the sources it cites — untouched when it cites none."""
    line = sources_line(content)
    if not line or not text.strip():
        return text
    return f"{text.rstrip()}\n\n{line}"

"""A scripted stand-in for the Anthropic client, so e2e journeys can talk to Maggie (MAG-95).

Calling the real model from an e2e run would be slow, billed and different every
time. This module answers instead — but only the model is replaced. The tool
loop, the AG-UI streaming gateway, the MCP client and the metrics all run
exactly as in production, because what they hold is an object with the same
surface as `anthropic.AsyncAnthropic`: `messages.create()` and
`messages.stream()`, `text` and `tool_use` blocks, a `stop_reason` and a
`usage`. We test the plumbing, not the model.

What it answers comes from the scenario files in `agent/fixtures/fake-llm/`,
one per scenario, matched on what the caller asked. See that directory's
README for the format.

Two failures are deliberately loud rather than silent, because a fake that
improvises is worse than no fake at all:

  - no scenario matches: the answer is the `[fake-llm] …` sentence below, so
    a journey's assertion fails on a string that names its own cause instead
    of on a plausible-looking wrong answer;
  - a scenario scripts a tool the caller never offered: logged as an error.
    That is how a module dropping out of `discovery.scan_dirs` surfaces here.
"""

import asyncio
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import yaml

from app.config import settings

logger = logging.getLogger(__name__)

DEFAULT_FIXTURES_DIR = Path(__file__).resolve().parent.parent.parent / "fixtures" / "fake-llm"

# Deltas are plain slices of the answer rather than words: `"".join(deltas)`
# then provably reconstitutes the text, which is the property a streaming
# journey asserts. The real API does not align on words either.
DELTA_SIZE = 24
# How long a stalled stream waits: far more than a journey needs to act, bounded so a client that
# never hangs up cannot leave the request open for good.
STALL_SECONDS = 90


def no_scenario_message(user_text: str) -> str:
    return f"[fake-llm] aucun scénario ne correspond à : {user_text!r}"


def no_turn_message(scenario: str, turn: int) -> str:
    return f"[fake-llm] le scénario {scenario!r} n'a pas de tour {turn}"


# ---------------------------------------------------------------------------
# The shapes the callers read off a response
# ---------------------------------------------------------------------------


@dataclass
class FakeTextBlock:
    text: str
    type: str = "text"


@dataclass
class FakeCitation:
    url: str
    title: str
    type: str = "web_search_result_location"


@dataclass
class FakeCitedTextBlock:
    """A text block of an answer that rests on a web search: the pages it cites ride on the block (MAG-384)."""

    text: str
    citations: list[FakeCitation]
    type: str = "text"


@dataclass
class FakeToolUseBlock:
    id: str
    name: str
    input: dict
    type: str = "tool_use"


@dataclass
class FakeServerToolUseBlock:
    """The web search the model ran on Anthropic's side — never handed to the tool router."""

    id: str
    input: dict
    name: str = "web_search"
    type: str = "server_tool_use"


@dataclass
class FakeWebSearchResultBlock:
    tool_use_id: str
    content: list[dict]
    type: str = "web_search_tool_result"


@dataclass
class FakeUsage:
    input_tokens: int
    output_tokens: int
    cache_creation_input_tokens: int = 0
    cache_read_input_tokens: int = 0


@dataclass
class FakeMessage:
    content: list[
        FakeTextBlock | FakeCitedTextBlock | FakeToolUseBlock | FakeServerToolUseBlock | FakeWebSearchResultBlock
    ]
    stop_reason: str
    usage: FakeUsage
    model: str = "fake"
    role: str = "assistant"
    type: str = "message"
    # Seconds the stream waits between two text deltas: what lets a journey act on an answer
    # that is still being written (MAG-223). Not part of the real API's message, only of this fake.
    delta_delay: float = 0.0
    # Deltas after which the stream stalls for STALL_SECONDS, 0 for never: an answer that cannot
    # finish by itself, however slow the journey acting on it is (MAG-223).
    stall_after_deltas: int = 0


# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ScriptedTool:
    name: str
    input: dict


@dataclass(frozen=True)
class ScriptedWebSearch:
    """A web search the model runs before answering (MAG-384): its query, the pages found, the pages cited."""

    query: str
    results: tuple[tuple[str, str], ...] = ()  # (title, url)
    cited: tuple[tuple[str, str], ...] = ()  # (title, url), the ones the answer cites


@dataclass(frozen=True)
class Turn:
    """One model turn: some text, the tools it asks for, and the web search it ran first."""

    text: str = ""
    tools: tuple[ScriptedTool, ...] = ()
    web_search: ScriptedWebSearch | None = None

    @property
    def stop_reason(self) -> str:
        return "tool_use" if self.tools else "end_turn"


@dataclass(frozen=True)
class Scenario:
    name: str
    turns: tuple[Turn, ...]
    user_contains: tuple[str, ...] = ()
    user_matches: str | None = None
    system_contains: tuple[str, ...] = ()
    history_contains: tuple[str, ...] = ()
    history_matches: str | None = None
    # Not a condition: a regex on what the tools returned *this run*, whose groups feed `\1`
    # first (MAG-513) — « list the routines, then tick the one named ».
    results_matches: str | None = None
    stream_delay_ms: int = 0
    stall_after_deltas: int = 0
    # The question came with a picture (MAG-214) — the model sees it, a journey cannot.
    user_has_image: bool = False
    is_default: bool = False
    source: str = ""

    def matches(self, user_text: str, system_text: str, history: str = "", *, has_image: bool = False) -> bool:
        """Every declared condition has to hold. A scenario declaring none matches nothing."""
        if self.is_default:
            return True
        conditions = (
            bool(
                self.user_contains
                or self.user_matches
                or self.system_contains
                or self.history_contains
                or self.history_matches
            )
            or self.user_has_image
        )
        if not conditions:
            return False
        if self.user_has_image and not has_image:
            return False
        lowered_user = user_text.lower()
        lowered_system = system_text.lower()
        lowered_history = history.lower()
        if any(needle.lower() not in lowered_user for needle in self.user_contains):
            return False
        if self.user_matches and not re.search(self.user_matches, user_text, re.IGNORECASE):
            return False
        if any(needle.lower() not in lowered_history for needle in self.history_contains):
            return False
        if self.history_matches and not re.search(self.history_matches, history, re.IGNORECASE):
            return False
        return all(needle.lower() in lowered_system for needle in self.system_contains)

    def turn(self, index: int) -> Turn | None:
        return self.turns[index] if 0 <= index < len(self.turns) else None

    def render(self, text: str, user_text: str, history: str = "", results: str = "") -> str:
        r"""Put the capture groups of `user_matches` back into a turn's text, as `\1`, `\2`…

        The one thing a scripted answer cannot hardcode is an id, since it
        differs between runs. This is how the context router answers with the id
        of a context the stack made a moment ago.

        A scenario declaring `history_matches` takes its groups from the history instead
        (MAG-211): « supprime-le » names nothing, and the id it means is in the
        `tool_result` of the call that created it — replayed, or not there at all.

        A scenario declaring `results_matches` takes them first from what the tools of
        the current run returned (MAG-513): « j'ai bu mon verre d'eau » names no id
        either, and the one she needs is in the `list_routines` she has just called —
        which `history` leaves out, being the batch that answers the message.
        """
        if not text:
            return text
        text = expand_days(text)
        found = re.search(self.results_matches, results, re.IGNORECASE) if self.results_matches else None
        if found is None and self.history_matches:
            found = re.search(self.history_matches, history, re.IGNORECASE)
        elif found is None and self.user_matches:
            found = re.search(self.user_matches, user_text, re.IGNORECASE)
        if found is None:
            return text
        try:
            return found.expand(text)
        except (re.error, IndexError) as exc:
            logger.error(f"[fake-llm] scenario {self.name!r}: cannot expand its text ({exc})")
            return text

    def render_input(self, value: Any, user_text: str, history: str = "", results: str = "") -> Any:
        r"""The same substitution, through a scripted tool's arguments.

        A tool that takes an id was simply unreachable from a journey before
        this: `move_to_fallback` wants the ULID of the shop that closed, and a
        ULID differs on every seed, so no fixture could name one. Now the
        journey puts it in what it says — `« le magasin 01J… est fermé »` — the
        scenario captures it, and `storeId: '\1'` carries it into the real MCP
        call (MAG-101).

        Walks lists and nested mappings, so a scenario can script a tool whose
        argument is an array of ids. Non-strings are left exactly as they are:
        quantities and booleans must not be turned into text.
        """
        if isinstance(value, str):
            return self.render(value, user_text, history, results)
        if isinstance(value, dict):
            return {key: self.render_input(item, user_text, history, results) for key, item in value.items()}
        if isinstance(value, list):
            return [self.render_input(item, user_text, history, results) for item in value]
        return value


def expand_days(text: str) -> str:
    """`${today}` and `${tomorrow}` become YYYY-MM-DD, `${month}` YYYY-MM, in Paris, the stack clock (MAG-409).

    A scenario cannot hardcode « demain »: the fake does not read tool results, so it cannot
    ask `date_time` either. The agent runs under the e2e clock, so this is the day the API sees.
    `${in_2_minutes}` is the local instant two minutes from now, YYYY-MM-DDTHH:MM:00 (MAG-709): a
    proaction a planning poses there comes due while the journey waits, as it would in the morning.
    """
    if "${" not in text:
        return text
    now = datetime.now(ZoneInfo("Europe/Paris"))
    today = now.date()
    soon = (now + timedelta(minutes=2)).replace(second=0, microsecond=0)
    return (
        text.replace("${today}", today.isoformat())
        .replace("${tomorrow}", (today + timedelta(days=1)).isoformat())
        .replace("${month}", today.isoformat()[:7])
        .replace("${in_2_minutes}", soon.strftime("%Y-%m-%dT%H:%M:00"))
    )


def _as_tuple(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        return (value,)
    return tuple(str(item) for item in value)


def _pages(raw: Any, source: str, position: int, key: str) -> tuple[tuple[str, str], ...]:
    if raw is None:
        return ()
    if not isinstance(raw, list) or not all(isinstance(page, dict) and page.get("url") for page in raw):
        raise ValueError(f"{source}: turn {position} 'web_search.{key}' must be a list of {{title, url}}")
    return tuple((str(page.get("title") or page["url"]), str(page["url"])) for page in raw)


def _parse_web_search(raw: Any, source: str, position: int) -> ScriptedWebSearch | None:
    if raw is None:
        return None
    if not isinstance(raw, dict) or not raw.get("query"):
        raise ValueError(f"{source}: turn {position} 'web_search' must be a mapping with a 'query'")
    results = _pages(raw.get("results"), source, position, "results")
    cited = _pages(raw.get("cited"), source, position, "cited") or results
    return ScriptedWebSearch(query=str(raw["query"]), results=results, cited=cited)


def parse_scenario(raw: Any, source: str) -> Scenario:
    """Build a scenario from a fixture file's parsed YAML."""
    if not isinstance(raw, dict):
        raise ValueError(f"{source}: a scenario file must hold a mapping")

    name = raw.get("name") or Path(source).stem
    match = raw.get("match") or {}
    if not isinstance(match, dict):
        raise ValueError(f"{source}: 'match' must be a mapping")

    turns: list[Turn] = []
    for position, raw_turn in enumerate(raw.get("turns") or [], start=1):
        if not isinstance(raw_turn, dict):
            raise ValueError(f"{source}: turn {position} must be a mapping")
        tools = tuple(
            ScriptedTool(name=str(tool["name"]), input=dict(tool.get("input") or {}))
            for tool in raw_turn.get("tools") or []
        )
        text = str(raw_turn.get("text") or "")
        web_search = _parse_web_search(raw_turn.get("web_search"), source, position)
        if not text and not tools:
            raise ValueError(f"{source}: turn {position} has neither 'text' nor 'tools'")
        turns.append(Turn(text=text, tools=tools, web_search=web_search))

    if not turns:
        raise ValueError(f"{source}: a scenario needs at least one turn")

    # Compiled here rather than at match time: a typo'd pattern must be a file
    # this loader refuses, not a `re.error` escaping `resolve()` — which would
    # come out as a 500 on /chat and "Désolé, une erreur est survenue." on the
    # stream, with the `[fake-llm]` sentence never printed.
    patterns = {key: match.get(key) for key in ("user_matches", "history_matches")}
    results_matches = raw.get("results_matches")
    if results_matches is not None:
        patterns["results_matches"] = results_matches
    for key, pattern in patterns.items():
        if pattern is None:
            continue
        try:
            re.compile(str(pattern))
        except re.error as exc:
            raise ValueError(f"{source}: '{key}' is not a valid regex: {exc}") from exc
    user_matches, history_matches = patterns["user_matches"], patterns["history_matches"]

    stream_delay_ms = raw.get("stream_delay_ms", 0)
    if isinstance(stream_delay_ms, bool) or not isinstance(stream_delay_ms, int) or stream_delay_ms < 0:
        raise ValueError(f"{source}: 'stream_delay_ms' must be a non-negative integer")

    stall_after_deltas = raw.get("stall_after_deltas", 0)
    if isinstance(stall_after_deltas, bool) or not isinstance(stall_after_deltas, int) or stall_after_deltas < 0:
        raise ValueError(f"{source}: 'stall_after_deltas' must be a non-negative integer")

    return Scenario(
        name=str(name),
        turns=tuple(turns),
        user_contains=_as_tuple(match.get("user_contains")),
        user_matches=str(user_matches) if user_matches is not None else None,
        system_contains=_as_tuple(match.get("system_contains")),
        history_contains=_as_tuple(match.get("history_contains")),
        history_matches=str(history_matches) if history_matches is not None else None,
        results_matches=str(results_matches) if results_matches is not None else None,
        stream_delay_ms=stream_delay_ms,
        stall_after_deltas=stall_after_deltas,
        user_has_image=bool(match.get("user_has_image")),
        is_default=bool(raw.get("default")),
        source=source,
    )


@dataclass
class ScenarioLibrary:
    """The scenario files, reloaded whenever one of them changes on disk.

    The e2e stack mounts the worktree, so a fixture edited mid-session has to
    take effect without restarting the agent — otherwise the first symptom is a
    journey that keeps failing on the answer you just fixed.
    """

    directory: Path
    scenarios: list[Scenario] = field(default_factory=list)
    _contents: dict[str, bytes] = field(default_factory=dict)

    def _read(self) -> dict[str, bytes]:
        """The files as they are on disk, in file name order.

        Compared byte for byte rather than by mtime: a filesystem's timestamp
        granularity is coarser than an edit, so two writes in the same tick look
        identical — and the symptom would be a fixture that seems not to have
        been saved. This runs once per model call, over a handful of small files
        in a provider that only exists under e2e, so the cost is not worth
        trading the promise for.
        """
        try:
            files = sorted(self.directory.glob("*.yaml"))
        except OSError:
            return {}

        contents: dict[str, bytes] = {}
        for path in files:
            try:
                contents[path.name] = path.read_bytes()
            except OSError as exc:
                logger.error(f"[fake-llm] cannot read scenario file {path}: {exc}")
        return contents

    def load(self) -> None:
        self._load_from(self._read())

    def _load_from(self, contents: dict[str, bytes]) -> None:
        scenarios: list[Scenario] = []
        for name, raw_bytes in contents.items():
            try:
                raw = yaml.safe_load(raw_bytes.decode()) or {}
                scenarios.append(parse_scenario(raw, source=name))
            except (UnicodeDecodeError, ValueError, yaml.YAMLError) as exc:
                logger.error(f"[fake-llm] ignoring scenario file {name}: {exc}")

        # File name order decides which of two matching scenarios wins, so a
        # numeric prefix is how a fixture author expresses precedence. The
        # catch-all goes last whatever it is called.
        self.scenarios = [s for s in scenarios if not s.is_default] + [s for s in scenarios if s.is_default]
        self._contents = contents
        logger.info(f"[fake-llm] loaded {len(self.scenarios)} scenarios from {self.directory}")

    def ensure_loaded(self) -> None:
        contents = self._read()
        if contents != self._contents:
            self._load_from(contents)

    def resolve(
        self, user_text: str, system_text: str, history: str = "", *, has_image: bool = False
    ) -> Scenario | None:
        self.ensure_loaded()
        for scenario in self.scenarios:
            if scenario.matches(user_text, system_text, history, has_image=has_image):
                return scenario
        return None


# ---------------------------------------------------------------------------
# Reading the request
# ---------------------------------------------------------------------------


def system_text(system: Any) -> str:
    """The system prompt as one string, whether it came as text or as cache blocks."""
    if not system:
        return ""
    if isinstance(system, str):
        return system
    parts = []
    for block in system:
        if isinstance(block, dict):
            parts.append(str(block.get("text", "")))
        else:
            parts.append(str(getattr(block, "text", "")))
    return "\n".join(parts)


def _is_loop_round(content: Any) -> bool:
    """A list is a round of the loop (tool results, a guard's relaunch) unless it carries a picture.

    The user's question is a string, or a list when it came with an image (MAG-214).
    """
    return isinstance(content, list) and not any(_block_type(block) == "image" for block in content)


def _block_type(block: Any) -> str | None:
    return block.get("type") if isinstance(block, dict) else getattr(block, "type", None)


def _last_question(messages: list[dict] | None) -> Any:
    """The content of the last user turn that is not a batch of tool results."""
    for message in reversed(messages or []):
        if message.get("role") == "user" and not _is_loop_round(message.get("content")):
            return message.get("content")
    return None


def last_user_text(messages: list[dict] | None) -> str:
    """The last thing the user actually said — tool result batches are not it.

    A turn that came with a picture is a list, the image then the text (MAG-214).
    """
    content = _last_question(messages)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(block["text"] for block in content if _block_type(block) == "text")
    return ""


def last_user_has_image(messages: list[dict] | None) -> bool:
    content = _last_question(messages)
    return isinstance(content, list) and any(_block_type(block) == "image" for block in content)


_images_received = 0


def images_received() -> int:
    """How many questions came to the fake model with a picture, since the last reset.

    The voice journey's proof that the screenshot really crossed the agent (MAG-214): the
    model is the one reader of it, and no screen shows what the model was sent.
    """
    return _images_received


def reset_images_received() -> None:
    global _images_received
    _images_received = 0


def history_text(messages: list[dict] | None) -> str:
    """The conversation sent, *minus* the message being answered, as one string.

    What a journey cannot see from a browser is what the history held — exactly like the
    system prompt, which is why `system_contains` exists. This is the same handle for the
    messages array (MAG-13): a scenario declaring `history_contains` is unreachable unless
    the thread's own messages really were loaded.

    The last entry is left out on purpose. It is the message being answered, which
    `user_contains` already covers, and including it would let a scenario "prove" that the
    history carried a sentence the user had just typed.
    """
    parts = []
    for message in (messages or [])[:-1]:
        content = message.get("content")
        parts.append(content if isinstance(content, str) else str(_without_image_data(content)))
    return "\n".join(parts)


def round_results(messages: list[dict] | None) -> str:
    """What the tools returned since the message being answered, as one string.

    The counterpart of `history_text`, which stops short of it: the batches of the current
    run are the tail of the array, after the last real question.
    """
    parts: list[str] = []
    for message in reversed(messages or []):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if not _is_loop_round(content):
            break
        parts.append(str(content))
    return "\n".join(reversed(parts))


def _without_image_data(content: Any) -> Any:
    """A list content with its pictures reduced to their type: the bytes are not text to match or count."""
    if not isinstance(content, list):
        return content
    return [{"type": "image"} if _block_type(block) == "image" else block for block in content]


def turn_index(messages: list[dict] | None) -> int:
    """How many tool rounds *this* run has already been through.

    The tool loop appends one assistant message and one batch of tool results per round,
    so counting the batches counts the rounds — without the fake keeping any state of its
    own between requests.

    Counted from the end, and stopped at the message being answered: since MAG-211 the
    history replays the rounds of the thread's last turns, which are batches too. Counting
    those as well would have the fake answering with turn 3 of a scenario on the first
    call of a run — `[fake-llm] le scénario … n'a pas de tour 3`, on a scenario that is
    perfectly fine. A question with a picture is a list too (MAG-214), and is not a batch.
    """
    rounds = 0
    for message in reversed(messages or []):
        if message.get("role") != "user":
            continue
        if not _is_loop_round(message.get("content")):
            break
        rounds += 1
    return rounds


def _tokens(text: str) -> int:
    return max(1, len(text) // 4)


def _request_tokens(system: Any, messages: list[dict] | None) -> int:
    size = len(system_text(system))
    for message in messages or []:
        size += len(str(_without_image_data(message.get("content", ""))))
    return max(1, size // 4)


def _deltas(text: str) -> list[str]:
    return [text[i : i + DELTA_SIZE] for i in range(0, len(text), DELTA_SIZE)]


# ---------------------------------------------------------------------------
# Streaming events
# ---------------------------------------------------------------------------


@dataclass
class _TextDelta:
    text: str
    type: str = "text_delta"


@dataclass
class _JsonDelta:
    # No `text` attribute, on purpose: the streaming gateway tells a text delta
    # from a tool-input delta by asking for one, and that guard needs exercising.
    partial_json: str
    type: str = "input_json_delta"


@dataclass
class _Event:
    type: str
    index: int = 0
    content_block: Any = None
    delta: Any = None
    message: Any = None


class FakeStream:
    """What `async with client.messages.stream(...)` yields: the events, then the final message."""

    def __init__(self, message: FakeMessage):
        self._message = message

    async def __aenter__(self) -> "FakeStream":
        return self

    async def __aexit__(self, *_exc_info) -> bool:
        return False

    def __aiter__(self):
        return self._events()

    async def _events(self):
        yield _Event(type="message_start", message=self._message)

        for index, block in enumerate(self._message.content):
            if isinstance(block, FakeTextBlock | FakeCitedTextBlock):
                yield _Event(type="content_block_start", index=index, content_block=FakeTextBlock(text=""))
                for sent, delta in enumerate(_deltas(block.text), start=1):
                    if self._message.delta_delay:
                        await asyncio.sleep(self._message.delta_delay)
                    yield _Event(type="content_block_delta", index=index, delta=_TextDelta(text=delta))
                    if sent == self._message.stall_after_deltas:
                        await asyncio.sleep(STALL_SECONDS)
            else:
                yield _Event(type="content_block_start", index=index, content_block=block)
                yield _Event(
                    type="content_block_delta",
                    index=index,
                    delta=_JsonDelta(partial_json=""),
                )
            yield _Event(type="content_block_stop", index=index)

        yield _Event(type="message_stop", message=self._message)

    async def get_final_message(self) -> FakeMessage:
        return self._message


# ---------------------------------------------------------------------------
# The client
# ---------------------------------------------------------------------------


class FakeMessages:
    def __init__(self, library: ScenarioLibrary):
        self._library = library

    def _build(
        self,
        *,
        model: str,
        system: Any,
        messages: list[dict] | None,
        tools: Any,
    ) -> FakeMessage:
        user_text = last_user_text(messages)
        prompt = system_text(system)
        index = turn_index(messages)
        has_image = last_user_has_image(messages)
        if has_image and index == 0:
            global _images_received
            _images_received += 1

        history = history_text(messages)
        results = round_results(messages)
        scenario = self._library.resolve(user_text, prompt, history, has_image=has_image)
        if scenario is None:
            logger.error(f"[fake-llm] no scenario for {user_text!r} — add one under {self._library.directory}")
            turn = Turn(text=no_scenario_message(user_text))
            scenario_name = "none"
        else:
            scenario_name = scenario.name
            found = scenario.turn(index)
            if found is None:
                logger.error(f"[fake-llm] scenario {scenario.name!r} ran out of turns at round {index + 1}")
                turn = Turn(text=no_turn_message(scenario.name, index + 1))
            else:
                turn = found

        text = scenario.render(turn.text, user_text, history, results) if scenario else turn.text

        offered = {tool["name"] for tool in tools if isinstance(tool, dict) and "name" in tool} if tools else set()
        content: list = []
        search = turn.web_search
        if search is not None:
            if offered and "web_search" not in offered:
                logger.error(f"[fake-llm] scenario {scenario_name!r} searches the web, but web_search was not offered")
            search_id = f"srvtoolu_fake_{index}"
            query = scenario.render(search.query, user_text, history, results) if scenario else search.query
            content.append(FakeServerToolUseBlock(id=search_id, input={"query": query}))
            content.append(
                FakeWebSearchResultBlock(
                    tool_use_id=search_id,
                    content=[
                        {"type": "web_search_result", "title": title, "url": url, "page_age": None}
                        for title, url in search.results
                    ],
                )
            )
        if text and search is not None and search.cited:
            content.append(
                FakeCitedTextBlock(
                    text=text, citations=[FakeCitation(url=url, title=title) for title, url in search.cited]
                )
            )
        elif text:
            content.append(FakeTextBlock(text=text))
        for position, tool in enumerate(turn.tools):
            if offered and tool.name not in offered:
                logger.error(
                    f"[fake-llm] scenario {scenario_name!r} calls {tool.name!r}, which was not offered — "
                    "is its module still in discovery.scan_dirs?"
                )
            arguments = dict(tool.input)
            if scenario:
                arguments = scenario.render_input(arguments, user_text, history, results)
            content.append(
                FakeToolUseBlock(
                    id=f"toolu_fake_{index}_{position}",
                    name=tool.name,
                    input=arguments,
                )
            )

        output = text + "".join(str(tool.input) for tool in turn.tools) + (search.query if search else "")
        return FakeMessage(
            content=content,
            stop_reason=turn.stop_reason,
            usage=FakeUsage(
                input_tokens=_request_tokens(system, messages),
                output_tokens=_tokens(output),
            ),
            model=model or "fake",
            delta_delay=(scenario.stream_delay_ms / 1000) if scenario else 0.0,
            stall_after_deltas=scenario.stall_after_deltas if scenario else 0,
        )

    async def create(
        self,
        *,
        model: str = "fake",
        system: Any = None,
        messages: list[dict] | None = None,
        tools: Any = None,
        **_ignored,
    ) -> FakeMessage:
        return self._build(model=model, system=system, messages=messages, tools=tools)

    def stream(
        self,
        *,
        model: str = "fake",
        system: Any = None,
        messages: list[dict] | None = None,
        tools: Any = None,
        **_ignored,
    ) -> FakeStream:
        # Not a coroutine: the real client's `stream()` returns the context
        # manager itself, and the gateway relies on that.
        return FakeStream(self._build(model=model, system=system, messages=messages, tools=tools))


class FakeAnthropicClient:
    """Stands in for `anthropic.AsyncAnthropic` when `LLM_PROVIDER=fake`."""

    def __init__(self, fixtures_dir: Path | str | None = None):
        directory = Path(fixtures_dir) if fixtures_dir else Path(settings.fake_llm_fixtures_dir or DEFAULT_FIXTURES_DIR)
        self.library = ScenarioLibrary(directory=directory)
        self.library.load()
        self.messages = FakeMessages(self.library)

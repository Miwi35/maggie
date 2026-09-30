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

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from app.config import settings

logger = logging.getLogger(__name__)

DEFAULT_FIXTURES_DIR = Path(__file__).resolve().parent.parent.parent / "fixtures" / "fake-llm"

# Deltas are plain slices of the answer rather than words: `"".join(deltas)`
# then provably reconstitutes the text, which is the property a streaming
# journey asserts. The real API does not align on words either.
DELTA_SIZE = 24


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
class FakeToolUseBlock:
    id: str
    name: str
    input: dict
    type: str = "tool_use"


@dataclass
class FakeUsage:
    input_tokens: int
    output_tokens: int
    cache_creation_input_tokens: int = 0
    cache_read_input_tokens: int = 0


@dataclass
class FakeMessage:
    content: list[FakeTextBlock | FakeToolUseBlock]
    stop_reason: str
    usage: FakeUsage
    model: str = "fake"
    role: str = "assistant"
    type: str = "message"


# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ScriptedTool:
    name: str
    input: dict


@dataclass(frozen=True)
class Turn:
    """One model turn: some text, and the tools it asks for."""

    text: str = ""
    tools: tuple[ScriptedTool, ...] = ()

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
    is_default: bool = False
    source: str = ""

    def matches(self, user_text: str, system_text: str) -> bool:
        """Every declared condition has to hold. A scenario declaring none matches nothing."""
        if self.is_default:
            return True
        conditions = bool(self.user_contains or self.user_matches or self.system_contains)
        if not conditions:
            return False
        lowered_user = user_text.lower()
        lowered_system = system_text.lower()
        if any(needle.lower() not in lowered_user for needle in self.user_contains):
            return False
        if self.user_matches and not re.search(self.user_matches, user_text, re.IGNORECASE):
            return False
        return all(needle.lower() in lowered_system for needle in self.system_contains)

    def turn(self, index: int) -> Turn | None:
        return self.turns[index] if 0 <= index < len(self.turns) else None

    def render(self, text: str, user_text: str) -> str:
        r"""Put the capture groups of `user_matches` back into a turn's text, as `\1`, `\2`…

        The one thing a scripted answer cannot hardcode is an id, since it
        differs between runs. This is how the context router answers with the id
        of a context the stack made a moment ago.
        """
        if not text or not self.user_matches:
            return text
        found = re.search(self.user_matches, user_text, re.IGNORECASE)
        if found is None:
            return text
        try:
            return found.expand(text)
        except (re.error, IndexError) as exc:
            logger.error(f"[fake-llm] scenario {self.name!r}: cannot expand its text ({exc})")
            return text


def _as_tuple(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        return (value,)
    return tuple(str(item) for item in value)


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
        if not text and not tools:
            raise ValueError(f"{source}: turn {position} has neither 'text' nor 'tools'")
        turns.append(Turn(text=text, tools=tools))

    if not turns:
        raise ValueError(f"{source}: a scenario needs at least one turn")

    return Scenario(
        name=str(name),
        turns=tuple(turns),
        user_contains=_as_tuple(match.get("user_contains")),
        user_matches=match.get("user_matches"),
        system_contains=_as_tuple(match.get("system_contains")),
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

    def resolve(self, user_text: str, system_text: str) -> Scenario | None:
        self.ensure_loaded()
        for scenario in self.scenarios:
            if scenario.matches(user_text, system_text):
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


def last_user_text(messages: list[dict] | None) -> str:
    """The last thing the user actually said — tool result batches are not it."""
    for message in reversed(messages or []):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, str):
            return content
    return ""


def turn_index(messages: list[dict] | None) -> int:
    """How many tool rounds this run has already been through.

    The tool loop appends one assistant message and one batch of tool results
    per round, and conversation history holds nothing but plain strings — so
    counting the batches counts the rounds, without the fake keeping any state
    of its own between requests.
    """
    return sum(1 for m in messages or [] if m.get("role") == "user" and isinstance(m.get("content"), list))


def _tokens(text: str) -> int:
    return max(1, len(text) // 4)


def _request_tokens(system: Any, messages: list[dict] | None) -> int:
    size = len(system_text(system))
    for message in messages or []:
        size += len(str(message.get("content", "")))
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
            if isinstance(block, FakeTextBlock):
                yield _Event(type="content_block_start", index=index, content_block=FakeTextBlock(text=""))
                for delta in _deltas(block.text):
                    yield _Event(type="content_block_delta", index=index, delta=_TextDelta(text=delta))
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

        scenario = self._library.resolve(user_text, prompt)
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

        text = scenario.render(turn.text, user_text) if scenario else turn.text

        offered = {tool["name"] for tool in tools if isinstance(tool, dict) and "name" in tool} if tools else set()
        content: list[FakeTextBlock | FakeToolUseBlock] = []
        if text:
            content.append(FakeTextBlock(text=text))
        for position, tool in enumerate(turn.tools):
            if offered and tool.name not in offered:
                logger.error(
                    f"[fake-llm] scenario {scenario_name!r} calls {tool.name!r}, which was not offered — "
                    "is its module still in discovery.scan_dirs?"
                )
            content.append(
                FakeToolUseBlock(
                    id=f"toolu_fake_{index}_{position}",
                    name=tool.name,
                    input=dict(tool.input),
                )
            )

        output = text + "".join(str(tool.input) for tool in turn.tools)
        return FakeMessage(
            content=content,
            stop_reason=turn.stop_reason,
            usage=FakeUsage(
                input_tokens=_request_tokens(system, messages),
                output_tokens=_tokens(output),
            ),
            model=model or "fake",
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

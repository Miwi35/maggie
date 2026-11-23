"""The tool loop's behaviour must not change when the two loops become one (MAG-953).

Each scenario scripts the model and the tools, runs a public entry point (`chat_stream` for the streamed chat,
`run_tool_loop` for everything else), and compares what came out — AG-UI events or the returned dict, every request
sent to the model, every tool call, what was stored, the checkpoints, the thread's log — to
`fixtures/loop_transcripts.json`. Recorded on the two loops as they were before the single one: delete the file and
run once to record it again, then read the diff.
"""

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import anthropic
import pytest

from app.container import Container
from app.db.provider_state_repository import provider_state_repo
from app.llm.prompt_cache import build_system
from app.llm.runner import run_tool_loop
from tests.prompt_helpers import stub_system_prompt

SNAPSHOT = Path(__file__).parent / "fixtures" / "loop_transcripts.json"
TOOLS = [
    {"name": "get_events", "description": "Events", "input_schema": {"type": "object", "properties": {}}},
    {"name": "schedule_proaction", "description": "Schedule", "input_schema": {"type": "object", "properties": {}}},
    {"name": "ask_owner", "description": "Ask", "input_schema": {"type": "object", "properties": {}}},
]
QUESTION = {
    "type": "question",
    "id": "q1",
    "text": "Quelle liste ?",
    "options": ["Courses", "Tâches"],
    "multiple": False,
    "answered": None,
}
CITATION = SimpleNamespace(type="web_search_result_location", url="https://www.example.com/page")


def _text(*chunks, cited=False):
    block = SimpleNamespace(type="text", text="".join(chunks), citations=[CITATION] if cited else None)
    block.chunks = list(chunks)
    return block


def _use(use_id, name="get_events", **tool_input):
    return SimpleNamespace(type="tool_use", id=use_id, name=name, input=tool_input)


def _search(use_id):
    return SimpleNamespace(type="server_tool_use", id=use_id, name="web_search", input={"query": "météo"})


def _found(use_id, failed=False):
    content = SimpleNamespace(type="web_search_tool_result_error") if failed else []
    return SimpleNamespace(type="web_search_tool_result", tool_use_id=use_id, content=content)


def _step(stop_reason, *content):
    usage = SimpleNamespace(
        input_tokens=10, output_tokens=5, cache_creation_input_tokens=2, cache_read_input_tokens=3
    )
    return SimpleNamespace(stop_reason=stop_reason, content=list(content), usage=usage)


def _ends(*content):
    return _step("end_turn", *content)


def _uses(*content):
    return _step("tool_use", *content)


def _plain(value):
    if isinstance(value, anthropic.NotGiven):
        return "NOT_GIVEN"
    if isinstance(value, SimpleNamespace):
        return {key: _plain(item) for key, item in vars(value).items() if key != "chunks"}
    if isinstance(value, dict):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


class _Stream:
    def __init__(self, step):
        self.step = step

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def __aiter__(self):
        for block in self.step.content:
            yield SimpleNamespace(type="content_block_start", content_block=block)
            for chunk in getattr(block, "chunks", []):
                yield SimpleNamespace(type="content_block_delta", delta=SimpleNamespace(text=chunk))
            yield SimpleNamespace(type="content_block_stop")

    async def get_final_message(self):
        return self.step


class ScriptedClient:
    """The model: the next step of the script for every call, an exception raised when the step is one."""

    def __init__(self, steps):
        self.steps = list(steps)
        self.requests: list[dict] = []
        self.messages = SimpleNamespace(create=self._create, stream=self._stream)

    def _next(self, kwargs):
        self.requests.append(_plain(kwargs))
        step = self.steps.pop(0)
        if isinstance(step, Exception):
            raise step
        return step

    async def _create(self, **kwargs):
        return self._next(kwargs)

    def _stream(self, **kwargs):
        return _Stream(self._next(kwargs))


class ScriptedRouter:
    def __init__(self, results, gate: asyncio.Event | None = None):
        self.results = {name: list(values) for name, values in results.items()}
        self.gate = gate
        self.calls: list[dict] = []
        self.started = asyncio.Event()

    async def get_tool_definitions(self):
        return TOOLS

    async def call_tool(self, name, arguments, *, user_id=None, source="chat", context_id=None):
        self.calls.append(
            {"name": name, "input": _plain(arguments), "user_id": user_id, "source": source, "context_id": context_id}
        )
        self.started.set()
        if self.gate is not None:
            await self.gate.wait()
        values = self.results[name]
        return values.pop(0) if len(values) > 1 else values[0]


FAILED = json.dumps({"error": "boom"})
EVENTS = json.dumps({"events": []})
ASKED = json.dumps({"asked": True, "question": QUESTION})
RESUMED = [
    {
        "role": "assistant",
        "content": [
            {"type": "tool_use", "id": "old1", "name": "get_events", "input": {"day": "monday"}},
            {"type": "tool_use", "id": "old2", "name": "get_events", "input": {"day": "tuesday"}},
        ],
    },
    {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "old1", "content": EVENTS}]},
]

# name -> (steps, tool results by tool, options of the run)
STREAMED = {
    "text_only": ([_ends(_text("Bon", "jour ", "Meven."))], {}, {}),
    "tool_round": (
        [_uses(_text("Je regarde."), _use("t1", day="today")), _ends(_text("Rien ", "aujourd'hui."))],
        {"get_events": [EVENTS]},
        {},
    ),
    "thread_label": ([_ends(_text("[fil «", " Courses»] ", "Salut"))], {}, {}),
    "short_label": ([_ends(_text("[autre fil] ", "Ok"))], {}, {}),
    "web_search": (
        [
            _step("pause_turn", _text("Je cherche."), _search("s1"), _found("s1")),
            _ends(_text("Il fera beau."), _search("s2"), _found("s2", failed=True), _text("Il ", "pleut.", cited=True)),
        ],
        {},
        {},
    ),
    "web_search_spoken": (
        [_ends(_text("Je cherche."), _search("s1"), _found("s1"), _text("Il ", "pleut.", cited=True))],
        {},
        {"spoken": True},
    ),
    "claim_guard": (
        [_ends(_text("Je vous rappellerai à 16h05.")), _ends(_text("Pardon, rien n'est programmé."))],
        {},
        {},
    ),
    "question": (
        [_uses(_text("Une seconde."), _use("q1", "ask_owner", text="Quelle liste ?"))],
        {"ask_owner": [ASKED]},
        {},
    ),
    "three_failures": (
        [
            _uses(_use("f1", day="a")),
            _uses(_use("f2", day="b")),
            _uses(_use("f3", day="c")),
            _ends(_text("Je n'ai pas pu.")),
        ],
        {"get_events": [FAILED]},
        {},
    ),
    "api_error_second_step": (
        [_uses(_text("Je regarde."), _use("t1")), RuntimeError("boom")],
        {"get_events": [EVENTS]},
        {},
    ),
    "budget_exhausted": (
        [_uses(_text("Je regarde."), _use(f"t{n}", n=n)) for n in range(5)],
        {"get_events": [EVENTS]},
        {},
    ),
    "resumed_with_interrupted_call": (
        [_ends(_text("Reprenons."))],
        {},
        {"checkpoint": RESUMED},
    ),
    "stopped_during_tool": (
        [_uses(_text("Je regarde."), _use("t1"))],
        {"get_events": [EVENTS]},
        {"stopped": True},
    ),
}

BLOCKING = {
    "text_only": ([_ends(_text("Bonjour."))], {}, {}),
    "tool_round": (
        [_uses(_text("Je regarde."), _use("t1", day="today")), _ends(_text("Rien."))],
        {"get_events": [EVENTS]},
        {},
    ),
    "web_search": (
        [
            _step("pause_turn", _text("Je cherche."), _search("s1"), _found("s1")),
            _ends(_text("Il fera beau."), _search("s2"), _found("s2"), _text("Il pleut.", cited=True)),
        ],
        {},
        {},
    ),
    "claim_guard": (
        [_ends(_text("Je vous rappellerai à 16h05.")), _ends(_text("Pardon, rien n'est programmé."))],
        {},
        {},
    ),
    "question": (
        [_uses(_text("Une seconde."), _use("q1", "ask_owner", text="Quelle liste ?"))],
        {"ask_owner": [ASKED]},
        {},
    ),
    "three_failures_no_text": (
        [
            _uses(_use("f1", day="a")),
            _uses(_use("f2", day="b")),
            _uses(_use("f3", day="c")),
            _ends(),
        ],
        {"get_events": [FAILED]},
        {},
    ),
    "budget_exhausted": (
        [_uses(_use(f"t{n}", n=n)) for n in range(5)],
        {"get_events": [EVENTS]},
        {},
    ),
    "review": (
        [_ends(_text("Voici le résumé.")), _ends(_text("Voici le résumé, avec le fait oublié."))],
        {},
        {"review": True},
    ),
    "api_error": (
        [_uses(_use("t1")), RuntimeError("boom")],
        {"get_events": [EVENTS]},
        {},
    ),
}


def _record(path: str, name: str, transcript: dict) -> None:
    recorded = json.loads(SNAPSHOT.read_text()) if SNAPSHOT.exists() else {}
    recorded.setdefault(path, {})
    expected = recorded[path].get(name)
    if expected is None:
        recorded[path][name] = transcript
        SNAPSHOT.write_text(json.dumps(recorded, indent=2, ensure_ascii=False, sort_keys=True) + "\n")
        return
    assert json.dumps(transcript, sort_keys=True, ensure_ascii=False) == json.dumps(
        expected, sort_keys=True, ensure_ascii=False
    )


def _normalised(event: dict) -> dict:
    return {**event, "runId": "RUN"} if "runId" in event else event


@pytest.mark.parametrize("name", STREAMED)
async def test_a_streamed_turn_is_unchanged(name):
    steps, results, options = STREAMED[name]
    gate = asyncio.Event() if options.get("stopped") else None
    client = ScriptedClient(steps)
    router = ScriptedRouter(results, gate)
    events: list[dict] = []
    created: list[dict] = []
    checkpoints: list = []
    thread_log: list[dict] = []

    async def create(**kwargs):
        created.append(_plain(kwargs))
        return SimpleNamespace(id=kwargs.get("message_id"))

    async def checkpoint(message_id, blocks):
        checkpoints.append([message_id, _plain(blocks)])

    async def log_call(context_id, entry):
        thread_log.append({"context_id": context_id, "name": entry["name"], "status": entry["status"]})

    async def consume(gateway):
        async for event in gateway.chat_stream(
            "salut",
            "user-1",
            "msg-1",
            spoken=options.get("spoken", False),
            checkpoint=options.get("checkpoint"),
            stopped=(lambda: True) if options.get("stopped") else None,
        ):
            events.append(_normalised(event))

    gateway = Container().streaming_gateway
    gateway.client = client
    gateway.tool_router = router
    gateway.system_prompt = stub_system_prompt(build_system("STABLE", "VOLATILE"))
    resolution = {"id": "ctx-1", "label": "Courses", "action": "matched"}
    with (
        patch("app.llm.streaming.route_message", AsyncMock(return_value=resolution)),
        patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])),
        patch("app.llm.streaming.build_history", AsyncMock(side_effect=lambda *a, **k: [{"role": "user", "content": "salut"}])),
        patch("app.llm.streaming.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
        patch("app.llm.streaming.message_repo") as messages,
        patch("app.llm.streaming.context_repo") as contexts,
        patch("app.llm.streaming.context_summarizer", MagicMock(maybe_summarize=AsyncMock())),
        patch.object(provider_state_repo, "mark_ok", AsyncMock()),
        patch.object(provider_state_repo, "mark_down", AsyncMock()),
    ):
        messages.create = AsyncMock(side_effect=create)
        messages.checkpoint_turn = AsyncMock(side_effect=checkpoint)
        messages.announce = AsyncMock()
        contexts.append_tool_call = AsyncMock(side_effect=log_call)
        if gate is not None:
            task = asyncio.create_task(consume(gateway))
            await router.started.wait()
            task.cancel()
            await asyncio.sleep(0)
            gate.set()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            await consume(gateway)

    _record(
        "streamed",
        name,
        {
            "events": events,
            "requests": client.requests,
            "tool_calls": router.calls,
            "created": created,
            "checkpoints": checkpoints,
            "thread_log": thread_log,
        },
    )


@pytest.mark.parametrize("name", BLOCKING)
async def test_a_blocking_run_is_unchanged(name):
    steps, results, options = BLOCKING[name]
    client = ScriptedClient(steps)
    router = ScriptedRouter(results)
    reviewed: list = []

    def review(text, calls):
        reviewed.append(text)
        return "Un fait a été oublié." if len(reviewed) == 1 else None

    messages = [{"role": "user", "content": "salut"}]
    with (
        patch.object(provider_state_repo, "mark_ok", AsyncMock()),
        patch.object(provider_state_repo, "mark_down", AsyncMock()),
    ):
        try:
            outcome = _plain(
                await run_tool_loop(
                    build_system("STABLE", "VOLATILE"),
                    messages,
                    TOOLS,
                    client=client,
                    tool_router=router,
                    user_id="user-1",
                    model="test-model",
                    effort="medium",
                    context_id="ctx-1",
                    review=review if options.get("review") else None,
                )
            )
        except RuntimeError as error:
            outcome = {"raised": str(error)}

    _record(
        "blocking",
        name,
        {"outcome": outcome, "requests": client.requests, "tool_calls": router.calls, "reviewed": reviewed},
    )

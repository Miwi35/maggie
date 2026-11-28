"""The single tool loop (MAG-953): the events it emits, how a cancellation reaches a running tool, its budget."""

import ast
import asyncio
from pathlib import Path
from unittest.mock import patch

from app.config import settings
from app.runtime.loop import (
    LoopOptions,
    QuestionAsked,
    RoundEnded,
    StepEnded,
    StepStarted,
    ToolCallEnded,
    ToolCallsPlanned,
    TurnEnded,
    TurnState,
    run_loop,
)
from tests.test_chat_gateway import _gateway, _Run, _stubs
from tests.test_loop_unchanged import (
    EVENTS,
    TOOLS,
    ScriptedClient,
    ScriptedRouter,
    _ends,
    _text,
    _use,
    _uses,
    stream_turn,
)

APP = Path(__file__).resolve().parent.parent / "app"


def _blocking(**changes) -> LoopOptions:
    return LoopOptions.blocking(model="m", user_id="user-1", max_iterations=5, **changes)


def _streaming(**changes) -> LoopOptions:
    return LoopOptions.streaming(model="m", user_id="user-1", max_iterations=5, **changes)


async def _run(steps, router, options, state=None):
    events = []
    async for event in run_loop(
        "system", [{"role": "user", "content": "salut"}], TOOLS, client=ScriptedClient(steps), tool_router=router,
        options=options, state=state,
    ):
        events.append(event)
    return events


async def test_a_tool_round_emits_planned_then_each_call_ended_in_order():
    steps = [_uses(_use("a"), _use("b", day="x")), _ends(_text("Fini."))]

    events = await _run(steps, ScriptedRouter({"get_events": [EVENTS]}), _blocking())

    assert [type(event) for event in events] == [
        StepStarted,
        StepEnded,
        ToolCallsPlanned,
        ToolCallEnded,
        ToolCallEnded,
        RoundEnded,
        StepStarted,
        StepEnded,
        TurnEnded,
    ]
    assert [use["id"] for use in events[2].uses] == ["a", "b"]
    assert [call.id for call in events[3:5]] == ["a", "b"]
    assert events[-1].outcome.response == "Fini."


async def test_the_kept_messages_carry_no_cache_control():
    messages = [{"role": "user", "content": "salut"}]
    state = TurnState()
    steps = [_uses(_use("a")), _ends(_text("Fini."))]
    client = ScriptedClient(steps)

    async for _ in run_loop(
        "system", messages, TOOLS, client=client, tool_router=ScriptedRouter({"get_events": [EVENTS]}),
        options=_blocking(), state=state,
    ):
        pass

    assert "cache_control" in str(client.requests[1]["messages"][-1])
    assert "cache_control" not in str(messages)
    assert "cache_control" not in str(state.blocks)


async def test_a_stopped_turn_lets_the_running_tool_finish_and_records_it():
    gate = asyncio.Event()
    router = ScriptedRouter({"get_events": [EVENTS]}, gate)
    state = TurnState()
    task = asyncio.create_task(_run([_uses(_use("t1"))], router, _streaming(stopped=lambda: True), state))
    await router.started.wait()

    task.cancel()
    await asyncio.sleep(0)
    gate.set()
    try:
        await task
    except asyncio.CancelledError:
        pass

    assert [call["id"] for call in state.round_calls] == ["t1"]
    assert state.round_calls[0]["result"] == EVENTS


async def test_a_cancel_without_stop_cancels_the_running_tool():
    cancelled = asyncio.Event()

    class Hanging(ScriptedRouter):
        async def call_tool(self, *args, **kwargs):
            self.started.set()
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                cancelled.set()
                raise

    router = Hanging({})
    state = TurnState()
    task = asyncio.create_task(_run([_uses(_use("t1"))], router, _streaming(stopped=lambda: False), state))
    await router.started.wait()

    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass

    assert cancelled.is_set()
    assert state.round_calls == []


async def test_ends_turn_reads_the_round_when_streamed_and_the_turn_when_blocking():
    def two_calls(calls):
        return {"text": "Deux appels."} if len(calls) >= 2 else None

    def steps():
        return [_uses(_use("a")), _uses(_use("b")), _ends(_text("Suite."))]

    router = {"get_events": [EVENTS]}
    blocking = await _run(steps(), ScriptedRouter(router), _blocking(ends_turn=two_calls))
    streamed = await _run(steps(), ScriptedRouter(router), _streaming(ends_turn=two_calls))

    assert any(isinstance(event, QuestionAsked) for event in blocking)
    assert blocking[-1].outcome.response == "Deux appels."
    assert not any(isinstance(event, QuestionAsked) for event in streamed)
    assert streamed[-1].outcome.response == "Suite."


async def test_the_chat_iteration_budget_is_a_setting():
    steps = [_uses(_text("Je regarde."), _use(f"t{n}", n=n)) for n in range(5)]

    with patch.object(settings, "chat_max_iterations", 2):
        streamed = await stream_turn(steps, {"get_events": [EVENTS]}, {})

        run = _Run()
        seen: dict = {}

        async def capture(system, messages, tools, **kwargs):
            seen.update(kwargs)
            return await run(system, messages, tools, **kwargs)

        with _stubs(capture, lambda *a, **k: _history()):
            await _gateway().chat("salut", "user-1", exclude_message_id="msg-1")

    assert len(streamed["requests"]) == 2
    assert seen["max_iterations"] == 2


async def _history():
    return [{"role": "user", "content": "salut"}]


def _imports(path: Path) -> set[str]:
    found: set[str] = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            found |= {alias.name for alias in node.names}
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            found.add(node.module)
            found |= {f"{node.module}.{alias.name}" for alias in node.names}
    return found


def _attribute_calls(path: Path) -> set[str]:
    return {
        node.func.attr
        for node in ast.walk(ast.parse(path.read_text()))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }


def _model_calls(path: Path) -> set[str]:
    """`create` / `stream` among the attributes read off a `.messages`."""
    return {
        node.attr
        for node in ast.walk(ast.parse(path.read_text()))
        if isinstance(node, ast.Attribute)
        and node.attr in ("create", "stream")
        and isinstance(node.value, ast.Attribute)
        and node.value.attr == "messages"
    }


def test_only_the_runtime_loop_calls_the_model_for_a_turn():
    others = ["llm/runner.py", "llm/streaming.py", "llm/gateway.py", "domain/approvals/service.py", "agents/delegate.py"]

    assert _model_calls(APP / "runtime" / "loop.py") == {"create", "stream"}
    assert {name: _model_calls(APP / name) for name in others if _model_calls(APP / name)} == {}


def test_the_streamed_adapter_calls_neither_the_model_nor_a_tool():
    path = APP / "llm" / "streaming.py"

    assert not _model_calls(path)
    assert "call_tool" not in _attribute_calls(path)


def test_the_loop_imports_no_domain_module():
    forbidden = ("app.llm.questions", "app.llm.history", "app.llm.gateway", "app.db")

    offenders = {
        module
        for module in _imports(APP / "runtime" / "loop.py")
        if any(module == name or module.startswith(name + ".") for name in forbidden)
    }

    assert not offenders

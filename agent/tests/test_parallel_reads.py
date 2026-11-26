"""The reads of a round run side by side, the writes one at a time in the order asked (MAG-994)."""

import asyncio

import pytest

from app.runtime.loop import LoopOptions, RoundEnded, ToolCallEnded, TurnState, run_loop
from tests.test_loop_unchanged import TOOLS, ScriptedClient, _ends, _text, _use, _uses

READ = "get_events"
WRITE = "schedule_proaction"


class TimedRouter:
    """Logs the start and the end of every call; a call whose id has a gate waits for it."""

    def __init__(self, gates: dict[str, asyncio.Event] | None = None):
        self.gates = gates or {}
        self.log: list[tuple[str, str]] = []
        self.started: dict[str, asyncio.Event] = {}

    def started_event(self, call_id):
        return self.started.setdefault(call_id, asyncio.Event())

    async def get_tool_definitions(self):
        return TOOLS

    async def call_tool(self, name, arguments, *, user_id=None, source="chat", context_id=None, origin=None):
        call_id = arguments["id"]
        self.log.append(("start", call_id))
        self.started_event(call_id).set()
        gate = self.gates.get(call_id)
        if gate is not None:
            await gate.wait()
        else:
            await asyncio.sleep(0)
        self.log.append(("end", call_id))
        return f'{{"done": "{call_id}"}}'


def _call(call_id, name=READ):
    return _use(call_id, name, id=call_id)


def _blocking(**changes) -> LoopOptions:
    return LoopOptions.blocking(model="m", user_id="user-1", max_iterations=5, **changes)


def _streaming(**changes) -> LoopOptions:
    return LoopOptions.streaming(model="m", user_id="user-1", max_iterations=5, **changes)


def _steps(*calls):
    return [_uses(*calls), _ends(_text("Fini."))]


async def _run(steps, router, options, state=None):
    events = []
    client = ScriptedClient(steps)
    async for event in run_loop(
        "system", [{"role": "user", "content": "salut"}], TOOLS, client=client, tool_router=router,
        options=options, state=state,
    ):
        events.append(event)
    return events, client


async def test_two_reads_of_a_round_run_at_the_same_time():
    gate = asyncio.Event()
    router = TimedRouter({"a": gate})
    task = asyncio.create_task(_run(_steps(_call("a"), _call("b")), router, _blocking()))

    await asyncio.wait_for(router.started_event("b").wait(), 1)
    assert ("end", "a") not in router.log

    gate.set()
    await task


async def test_a_write_waits_for_the_reads_before_it_and_the_reads_after_it_wait_for_the_write():
    gates = {"w": asyncio.Event()}
    router = TimedRouter(gates)
    steps = _steps(_call("r1"), _call("w", WRITE), _call("r2"))
    task = asyncio.create_task(_run(steps, router, _blocking()))

    await asyncio.wait_for(router.started_event("w").wait(), 1)
    await asyncio.sleep(0.01)
    assert router.log == [("start", "r1"), ("end", "r1"), ("start", "w")]

    gates["w"].set()
    await task
    assert router.log[3:] == [("end", "w"), ("start", "r2"), ("end", "r2")]


async def test_two_writes_of_a_round_never_overlap():
    router = TimedRouter()

    await _run(_steps(_call("w1", WRITE), _call("w2", WRITE)), router, _blocking())

    assert router.log == [("start", "w1"), ("end", "w1"), ("start", "w2"), ("end", "w2")]


@pytest.mark.parametrize("options", [_blocking, _streaming])
async def test_results_and_events_keep_the_order_the_model_asked(options):
    gate = asyncio.Event()
    router = TimedRouter({"a": gate})
    state = TurnState()
    task = asyncio.create_task(_run(_steps(_call("a"), _call("b")), router, options(), state))

    await asyncio.wait_for(router.started_event("b").wait(), 1)
    await asyncio.sleep(0.01)
    assert ("end", "b") in router.log and ("end", "a") not in router.log
    gate.set()
    events, client = await task

    assert [event.id for event in events if isinstance(event, ToolCallEnded)] == ["a", "b"]
    assert [call["id"] for call in next(e for e in events if isinstance(e, RoundEnded)).calls] == ["a", "b"]
    sent = client.requests[1]["messages"][-1]["content"]
    assert [block["tool_use_id"] for block in sent] == ["a", "b"]


async def test_a_stop_cancels_the_reads_in_progress():
    cancelled = []

    class Hanging(TimedRouter):
        async def call_tool(self, name, arguments, **kwargs):
            call_id = arguments["id"]
            self.started_event(call_id).set()
            if call_id == "done":
                return "{}"
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                cancelled.append(call_id)
                raise

    router = Hanging()
    state = TurnState()
    steps = _steps(_call("done"), _call("a"), _call("b"))
    task = asyncio.create_task(_run(steps, router, _streaming(stopped=lambda: True), state))
    await asyncio.wait_for(router.started_event("b").wait(), 1)

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(task, 1)

    assert sorted(cancelled) == ["a", "b"]
    assert [call["id"] for call in state.round_calls] == ["done"]


def _delegation(call_id):
    return _use(call_id, "delegate", id=call_id, agent="advisor", task="x")


async def test_two_delegations_of_a_round_run_at_the_same_time():
    gates = {"a": asyncio.Event(), "w": asyncio.Event()}
    router = TimedRouter(gates)
    steps = _steps(_delegation("a"), _delegation("b"), _call("w", WRITE))
    task = asyncio.create_task(_run(steps, router, _blocking()))

    await asyncio.wait_for(router.started_event("b").wait(), 1)
    await asyncio.sleep(0.01)
    assert ("end", "a") not in router.log
    assert ("start", "w") not in router.log

    gates["a"].set()
    await asyncio.wait_for(router.started_event("w").wait(), 1)
    assert router.log.index(("end", "a")) < router.log.index(("start", "w"))
    assert router.log.index(("end", "b")) < router.log.index(("start", "w"))
    gates["w"].set()
    await task


async def test_a_stop_cancels_the_delegations_in_progress():
    cancelled = []

    class Hanging(TimedRouter):
        async def call_tool(self, name, arguments, **kwargs):
            call_id = arguments["id"]
            self.started_event(call_id).set()
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                cancelled.append(call_id)
                raise

    router = Hanging()
    task = asyncio.create_task(
        _run(_steps(_delegation("a"), _delegation("b")), router, _streaming(stopped=lambda: True), TurnState())
    )
    await asyncio.wait_for(router.started_event("b").wait(), 1)

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await asyncio.wait_for(task, 1)

    assert sorted(cancelled) == ["a", "b"]


async def test_the_classifier_can_be_given_by_the_caller():
    router = TimedRouter()

    await _run(_steps(_call("a"), _call("b")), router, _blocking(), None)
    assert router.log[:2] == [("start", "a"), ("start", "b")]

    serial = TimedRouter()
    options = LoopOptions.blocking(model="m", user_id="user-1", max_iterations=5)
    options = LoopOptions(**{**options.__dict__, "reads": lambda *_: False})
    await _run(_steps(_call("a"), _call("b")), serial, options)
    assert serial.log == [("start", "a"), ("end", "a"), ("start", "b"), ("end", "b")]

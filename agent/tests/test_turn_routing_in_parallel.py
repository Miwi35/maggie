"""The thread is chosen beside the rest of the turn (MAG-869).

The router waits on a fast model; tools, personality and the parts of the prompt that do not depend
on the thread load meanwhile. History, memory recall and the open-threads section wait for it.
"""

import asyncio
from contextlib import ExitStack
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.container import Container
from app.db.context_repository import context_repo
from app.llm import contexts as contexts_module
from app.llm import history as history_module
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeUsage
from app.llm.streaming import StreamingGateway
from app.memory.agent_memory import MemoryBlocks
from tests.prompt_helpers import system_prompt_with

ROUTED = {"action": "matched", "id": "ctx-1", "label": "Courses", "status": "active", "summary": None}


def _gateway(calls: list[str]) -> StreamingGateway:
    gw = Container().streaming_gateway
    gw.client = MagicMock()
    gw.client.messages.stream = lambda **_kwargs: FakeStream(
        FakeMessage(
            content=[FakeTextBlock(text="C'est noté.")],
            stop_reason="end_turn",
            usage=FakeUsage(input_tokens=10, output_tokens=5),
        )
    )

    async def blocks(_user_id, _about, *, context_id=None, **_rest):
        calls.append(f"memory:{context_id}")
        return MemoryBlocks()

    async def tools():
        calls.append("tools")
        return []

    personality = MagicMock()
    personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    agent_memory = MagicMock()
    agent_memory.blocks = blocks
    gw.system_prompt = system_prompt_with(personality=personality, agent_memory=agent_memory)
    gw.tool_router = MagicMock()
    gw.tool_router.get_tool_definitions = tools
    gw._resolve_context = AsyncMock(return_value=ROUTED)
    return gw


def _turn_environment(stack: ExitStack, calls: list[str]) -> MagicMock:
    """Everything a turn reads that is not under test; returns the patched timezone resolver."""
    skills = stack.enter_context(patch("app.prompt_sources.skill_index"))
    skills.get_skills_index.return_value = ""
    skills.skills_for_moment.return_value = ""
    skills.refresh = AsyncMock()
    messages = stack.enter_context(patch("app.llm.streaming.message_repo"))
    stack.enter_context(patch("app.llm.streaming.message_service", messages))
    messages.create = AsyncMock()
    summarizer = stack.enter_context(patch("app.llm.streaming.context_summarizer"))
    summarizer.maybe_summarize = AsyncMock(return_value=None)
    stack.enter_context(patch("app.prompt_sources.behavior_directives_section", AsyncMock(return_value="")))
    stack.enter_context(patch("app.prompt_sources.last_exchange_section", AsyncMock(return_value="")))
    stack.enter_context(patch("app.prompt_sources.resolve_work_hours", AsyncMock(return_value=None)))
    stack.enter_context(patch("app.prompt_sources.work_hours_line", return_value=""))
    stack.enter_context(patch("app.prompt_sources.resolve_day_context", AsyncMock(return_value=None)))
    stack.enter_context(
        patch("app.prompt_sources.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris")))
    )
    return stack.enter_context(
        patch("app.llm.streaming.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris")))
    )


@pytest.fixture()
def around():
    """The turn's surroundings, and the log of what it called."""
    calls: list[str] = []

    async def section(_user_id, current_context_id=None, **_rest):
        calls.append(f"section:{current_context_id}")
        return ""

    async def history(_user_id, *, context_id=None, **_rest):
        calls.append(f"history:{context_id}")
        return [{"role": "user", "content": "Il me faut de la farine"}]

    with ExitStack() as stack:
        _turn_environment(stack, calls)
        stack.enter_context(patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])))
        stack.enter_context(patch("app.prompt_sources.active_contexts_section", section))
        stack.enter_context(patch("app.llm.streaming.build_history", history))
        yield calls


async def _drain(gw: StreamingGateway) -> None:
    async for _ in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1"):
        pass
    await asyncio.gather(*gw._background)


async def test_tools_and_personality_load_while_the_router_runs(around):
    calls = around
    gw = _gateway(calls)
    personality_called = asyncio.Event()

    async def personality(*_args, **_kwargs):
        calls.append("personality")
        personality_called.set()
        return "Tu es Maggie."

    async def route(*_args, **_kwargs):
        calls.append("route:start")
        await personality_called.wait()
        calls.append("route:end")
        return ROUTED

    gw.system_prompt.sources.personality.get_system_prompt = personality
    gw._resolve_context = route

    await asyncio.wait_for(_drain(gw), 2)

    assert calls.index("route:start") < calls.index("tools") < calls.index("route:end")
    assert calls.index("personality") < calls.index("route:end")


async def test_what_depends_on_the_thread_waits_for_it(around):
    calls = around
    gw = _gateway(calls)

    async def route(*_args, **_kwargs):
        calls.append("route:start")
        await asyncio.sleep(0.05)
        calls.append("route:end")
        return ROUTED

    gw._resolve_context = route

    await asyncio.wait_for(_drain(gw), 2)

    end = calls.index("route:end")
    for name in ("history:ctx-1", "memory:ctx-1", "section:ctx-1"):
        assert calls.index(name) > end


async def test_a_failure_beside_the_router_cancels_it(around):
    gw = _gateway(around)
    cancelled = asyncio.Event()

    async def route(*_args, **_kwargs):
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled.set()
            raise

    async def broken():
        await asyncio.sleep(0.01)
        raise RuntimeError("tools are down")

    gw._resolve_context = route
    gw.tool_router.get_tool_definitions = broken

    with pytest.raises(RuntimeError, match="tools are down"):
        await asyncio.wait_for(_drain(gw), 2)
    await asyncio.sleep(0)

    assert cancelled.is_set()


async def test_a_stopped_turn_cancels_the_router(around):
    gw = _gateway(around)
    cancelled = asyncio.Event()
    started = asyncio.Event()

    async def route(*_args, **_kwargs):
        started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled.set()
            raise

    gw._resolve_context = route
    turn = asyncio.create_task(_drain(gw))
    await asyncio.wait_for(started.wait(), 2)
    turn.cancel()
    with pytest.raises(asyncio.CancelledError):
        await turn
    await asyncio.sleep(0)

    assert cancelled.is_set()


async def test_the_open_threads_are_read_once_per_turn():
    calls: list[str] = []
    gw = _gateway(calls)
    del gw._resolve_context  # the real router, on a stand-in model
    thread = MagicMock(id="ctx-1", label="Courses", summary=None, user_id="user-1", tool_calls_log=[])
    other = MagicMock(id="ctx-2", label="Budget", summary=None, user_id="user-1", tool_calls_log=[])
    foreign = MagicMock(
        id="msg-0",
        role="user",
        content="Où en est mon budget ?",
        context_id="ctx-2",
        created_at=datetime.now(timezone.utc),
        blocks=None,
        interrupted=False,
    )
    answer = MagicMock()
    answer.content = [MagicMock(text='{"context_id": "ctx-1"}')]
    answer.usage.input_tokens = 10
    answer.usage.output_tokens = 5
    gw.client.messages.create = AsyncMock(return_value=answer)

    with ExitStack() as stack:
        _turn_environment(stack, calls)
        find_active = stack.enter_context(
            patch.object(context_repo, "find_active", AsyncMock(return_value=[thread, other]))
        )
        stack.enter_context(patch.object(context_repo, "touch", AsyncMock()))
        stack.enter_context(patch("app.llm.contexts.client_for", AsyncMock(side_effect=lambda _user, client: client)))
        routed_messages = stack.enter_context(patch.object(contexts_module, "message_repo"))
        stack.enter_context(patch.object(contexts_module, "message_service", routed_messages))
        routed_messages.find_recent = AsyncMock(return_value=[])
        routed_messages.update_context = AsyncMock()
        history_messages = stack.enter_context(patch.object(history_module, "message_repo"))
        history_messages.find_by_context = AsyncMock(return_value=[foreign])
        history_messages.find_recent = AsyncMock(return_value=[])

        await asyncio.wait_for(_drain(gw), 2)

    assert find_active.await_count == 1


async def test_the_timezone_is_resolved_once_per_turn():
    calls: list[str] = []
    gw = _gateway(calls)

    with ExitStack() as stack:
        resolver = _turn_environment(stack, calls)
        stack.enter_context(patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])))
        stack.enter_context(patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "x"}])))
        stack.enter_context(patch("app.prompt_sources.active_contexts_section", AsyncMock(return_value="")))

        await asyncio.wait_for(_drain(gw), 2)

    assert resolver.await_count == 1

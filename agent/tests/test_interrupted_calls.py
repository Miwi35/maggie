"""A turn lost during a tool call keeps the call, and the turn that takes it up closes it (MAG-872)."""

import asyncio
import json
from unittest.mock import AsyncMock, MagicMock, patch

from tests.prompt_helpers import stub_system_prompt
from app.container import Container
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeToolUseBlock, FakeUsage
from app.llm.prompt_cache import build_system
from app.llm.streaming import StreamingGateway
from app.llm.tool_blocks import INTERRUPTED_RESULT, record

USAGE = FakeUsage(input_tokens=1, output_tokens=1)


def _tool_turn(name="create_event", tool_id="tu-1", tool_input=None):
    return FakeMessage(
        content=[FakeToolUseBlock(id=tool_id, name=name, input=tool_input or {"title": "Dentiste"})],
        stop_reason="tool_use",
        usage=USAGE,
    )


def _text_turn(text):
    return FakeMessage(content=[FakeTextBlock(text=text)], stop_reason="end_turn", usage=USAGE)


def _gateway(turns, call_tool):
    seen: list[dict] = []

    def stream(**kwargs):
        seen.append({**kwargs, "messages": list(kwargs["messages"])})
        return FakeStream(turns[len(seen) - 1])

    gateway = Container().streaming_gateway
    gateway.client = MagicMock()
    gateway.client.messages.stream = stream
    gateway.tool_router = MagicMock(
        get_tool_definitions=AsyncMock(return_value=[{"name": "create_event"}, {"name": "schedule_proaction"}]),
        call_tool=call_tool,
    )
    gateway.system_prompt = stub_system_prompt(build_system("S", "V"))
    gateway._resolve_context = AsyncMock(return_value=None)
    gateway.system_prompt.thread_free = AsyncMock(return_value=None)
    return gateway, seen


async def _drain(gateway, **kwargs):
    with patch("app.llm.streaming.message_repo") as repo, patch("app.llm.streaming.message_service", repo), patch("app.runtime.loop.record_llm_usage"), patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])):
        repo.find_recent = AsyncMock(return_value=[])
        repo.create = AsyncMock()
        repo.checkpoint_turn = AsyncMock()
        async for _ in gateway.chat_stream("Mets le dentiste", "user-1", "msg-1", **kwargs):
            pass
    return repo


async def test_the_calls_are_kept_before_they_run():
    kept: list[list[dict]] = []

    async def call_tool(*_args, **_kwargs):
        kept.append(list(repo.checkpoint_turn.call_args.args[1]))
        return '{"id": "evt-1"}'

    gateway, _ = _gateway([_tool_turn(), _text_turn("C'est fait.")], AsyncMock(side_effect=call_tool))
    with patch("app.llm.streaming.message_repo") as repo, patch("app.llm.streaming.message_service", repo), patch("app.runtime.loop.record_llm_usage"), patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])):
        repo.find_recent = AsyncMock(return_value=[])
        repo.create = AsyncMock()
        repo.checkpoint_turn = AsyncMock()
        async for _ in gateway.chat_stream("Mets le dentiste", "user-1", "msg-1"):
            pass

    assert [t["role"] for t in kept[0]] == ["assistant"]
    assert kept[0][0]["content"][0]["id"] == "tu-1"
    assert repo.checkpoint_turn.call_args.args[1][1]["content"][0]["content"] == '{"id": "evt-1"}'


async def test_a_shutdown_mid_call_leaves_the_call_without_its_result():
    started = asyncio.Event()

    async def call_tool(*_args, **_kwargs):
        started.set()
        await asyncio.sleep(60)

    gateway, _ = _gateway([_tool_turn()], AsyncMock(side_effect=call_tool))
    with patch("app.llm.streaming.message_repo") as repo, patch("app.llm.streaming.message_service", repo), patch("app.runtime.loop.record_llm_usage"), patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])):
        repo.find_recent = AsyncMock(return_value=[])
        repo.create = AsyncMock()
        repo.checkpoint_turn = AsyncMock()

        async def run():
            async for _ in gateway.chat_stream("Mets le dentiste", "user-1", "msg-1", stopped=lambda: False):
                pass

        task = asyncio.create_task(run())
        await asyncio.wait_for(started.wait(), timeout=5)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    last = repo.checkpoint_turn.call_args.args[1]
    assert [t["role"] for t in last] == ["assistant"]
    assert last[0]["content"][0]["id"] == "tu-1"
    repo.create.assert_not_called()


async def test_a_resumed_turn_sends_the_interrupted_call_closed_and_does_not_run_it():
    call_tool = AsyncMock(return_value="{}")
    gateway, seen = _gateway([_text_turn("Je vérifie avant de recréer.")], call_tool)
    checkpoint = [record([{"id": "tu-0", "name": "create_event", "input": {"title": "Dentiste"}, "result": ""}])[0]]

    repo = await _drain(gateway, checkpoint=checkpoint)

    last_two = seen[0]["messages"][-2:]
    assert last_two[0]["content"][0]["id"] == "tu-0"
    assert last_two[1]["content"] == [{"type": "tool_result", "tool_use_id": "tu-0", "content": INTERRUPTED_RESULT}]
    call_tool.assert_not_awaited()
    stored = repo.create.call_args.kwargs["blocks"]
    assert stored[1]["content"][0]["content"] == INTERRUPTED_RESULT


async def test_a_resumed_call_that_had_its_result_backs_the_claim_guard():
    gateway, seen = _gateway([_text_turn("Je vous rappellerai demain.")], AsyncMock())
    checkpoint = record(
        [
            {
                "id": "tu-0",
                "name": "schedule_proaction",
                "input": {"when": "demain"},
                "result": json.dumps({"id": "pro-1"}),
            }
        ]
    )

    await _drain(gateway, checkpoint=checkpoint)

    assert len(seen) == 1

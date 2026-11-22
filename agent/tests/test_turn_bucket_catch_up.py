from unittest.mock import AsyncMock, MagicMock

import pytest

from app.container import Container
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeUsage
from app.llm.streaming import StreamingGateway
from app.memory.agent_memory import MemoryBlocks
from tests.prompt_helpers import system_prompt_with


class Sentinel(Exception):
    pass


async def test_the_memory_catch_up_starts_before_the_thread_is_chosen():
    order: list[str] = []
    gw = Container().streaming_gateway
    gw.client = MagicMock()
    gw.client.messages.stream = lambda **_kwargs: FakeStream(
        FakeMessage(
            content=[FakeTextBlock(text="C'est noté.")],
            stop_reason="end_turn",
            usage=FakeUsage(input_tokens=10, output_tokens=5),
        )
    )
    personality = MagicMock()
    personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    agent_memory = MagicMock()
    agent_memory.blocks = AsyncMock(return_value=MemoryBlocks())
    agent_memory.catch_up_soon = lambda user_id: order.append("catch_up")
    gw.system_prompt = system_prompt_with(personality=personality, agent_memory=agent_memory)
    gw.tool_router = MagicMock()
    gw.tool_router.get_tool_definitions = AsyncMock(return_value=[])

    async def route(*_args, **_kwargs):
        order.append("route")
        raise Sentinel

    gw._resolve_context = route

    with pytest.raises(Sentinel):
        async for _ in gw.chat_stream("Bonjour", "user-1", "msg-1"):
            pass

    assert order == ["catch_up", "route"]

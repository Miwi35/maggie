"""Tests for the shared tool loop."""

from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import httpx
import pytest

from app.llm.runner import ITERATION_LIMIT_MESSAGE, run_tool_loop


def _text_response(text: str) -> MagicMock:
    block = MagicMock(spec=["type", "text"])
    block.type = "text"
    block.text = text
    response = MagicMock()
    response.content = [block]
    response.stop_reason = "end_turn"
    response.usage.input_tokens = 10
    response.usage.output_tokens = 5
    return response


def _tool_response(name: str = "list_events", tool_input: dict | None = None, tool_id: str = "tu-1") -> MagicMock:
    block = MagicMock(spec=["type", "name", "input", "id"])
    block.type = "tool_use"
    block.name = name
    block.input = tool_input or {}
    block.id = tool_id
    response = MagicMock()
    response.content = [block]
    response.stop_reason = "tool_use"
    response.usage.input_tokens = 10
    response.usage.output_tokens = 5
    return response


@pytest.fixture()
def client():
    client = MagicMock()
    client.messages = MagicMock()
    client.messages.create = AsyncMock()
    return client


@pytest.fixture()
def tool_router():
    router = MagicMock()
    router.call_tool = AsyncMock(return_value='{"events": []}')
    return router


@pytest.fixture()
def usage():
    with patch("app.llm.runner.record_llm_usage") as mock_usage:
        yield mock_usage


async def _run(client, tool_router, messages=None, tools=None, **kwargs):
    return await run_tool_loop(
        "system",
        messages if messages is not None else [{"role": "user", "content": "hello"}],
        tools,
        client=client,
        tool_router=tool_router,
        user_id="user-1",
        model="claude-test",
        **kwargs,
    )


class TestPromptCaching:
    async def test_passes_system_blocks_and_marks_last_tool_cacheable(self, client, tool_router, usage):
        client.messages.create.return_value = _text_response("ok")
        system = [{"type": "text", "text": "stable", "cache_control": {"type": "ephemeral"}}]
        tools = [{"name": "a"}, {"name": "b"}]

        await run_tool_loop(
            system,
            [{"role": "user", "content": "hi"}],
            tools,
            client=client,
            tool_router=tool_router,
            user_id="user-1",
            model="claude-test",
        )

        kwargs = client.messages.create.call_args.kwargs
        assert kwargs["system"] == system
        assert kwargs["tools"][-1]["cache_control"] == {"type": "ephemeral"}
        assert "cache_control" not in kwargs["tools"][0]
        assert "cache_control" not in tools[-1]

    async def test_records_cache_tokens(self, client, tool_router, usage):
        response = _text_response("ok")
        response.usage.cache_creation_input_tokens = 120
        response.usage.cache_read_input_tokens = 800
        client.messages.create.return_value = response

        await _run(client, tool_router, tools=[{"name": "a"}])

        kwargs = usage.call_args.kwargs
        assert kwargs["cache_creation_input_tokens"] == 120
        assert kwargs["cache_read_input_tokens"] == 800


class TestRunToolLoop:
    async def test_text_response(self, client, tool_router, usage):
        client.messages.create.return_value = _text_response("Bonjour")

        result = await _run(client, tool_router, tools=[{"name": "list_events"}])

        assert result == {"response": "Bonjour", "tool_calls": []}
        client.messages.create.assert_awaited_once()
        tool_router.call_tool.assert_not_awaited()
        usage.assert_called_once()
        assert usage.call_args.kwargs["call_type"] == "chat"
        assert usage.call_args.kwargs["input_tokens"] == 10

    async def test_tool_turn_then_text(self, client, tool_router, usage):
        client.messages.create.side_effect = [
            _tool_response("list_events", {"day": "2026-09-30"}),
            _text_response("Rien de prévu"),
        ]
        messages = [{"role": "user", "content": "mon agenda ?"}]

        result = await _run(client, tool_router, messages, tools=[{"name": "list_events"}], source="proaction")

        assert result["response"] == "Rien de prévu"
        assert result["tool_calls"] == [
            {"name": "list_events", "input": {"day": "2026-09-30"}, "result": '{"events": []}'}
        ]
        tool_router.call_tool.assert_awaited_once_with(
            "list_events", {"day": "2026-09-30"}, user_id="user-1", source="proaction"
        )
        assert [m["role"] for m in messages] == ["user", "assistant", "user"]
        assert messages[-1]["content"] == [{"type": "tool_result", "tool_use_id": "tu-1", "content": '{"events": []}'}]
        assert client.messages.create.await_count == 2

    async def test_default_source_is_chat(self, client, tool_router, usage):
        client.messages.create.side_effect = [_tool_response(), _text_response("ok")]

        await _run(client, tool_router, tools=[{"name": "list_events"}])

        assert tool_router.call_tool.call_args.kwargs["source"] == "chat"

    async def test_iteration_limit(self, client, tool_router, usage):
        client.messages.create.return_value = _tool_response()

        result = await _run(client, tool_router, tools=[{"name": "list_events"}], max_iterations=2)

        assert result["response"] == ITERATION_LIMIT_MESSAGE
        assert len(result["tool_calls"]) == 2
        assert client.messages.create.await_count == 2

    async def test_api_error_is_recorded_and_raised(self, client, tool_router, usage):
        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        client.messages.create.side_effect = anthropic.APIConnectionError(request=request)

        with pytest.raises(anthropic.APIConnectionError):
            await _run(client, tool_router, tools=[{"name": "list_events"}])

        usage.assert_called_once()
        assert usage.call_args.kwargs["status"] == "error"
        assert usage.call_args.kwargs["input_tokens"] == 0

    async def test_tools_none_sends_no_tools(self, client, tool_router, usage):
        client.messages.create.return_value = _text_response("Réponse directe")

        result = await _run(client, tool_router, tools=None)

        assert result["response"] == "Réponse directe"
        assert client.messages.create.call_args.kwargs["tools"] is anthropic.NOT_GIVEN

    async def test_passes_model_and_max_tokens(self, client, tool_router, usage):
        client.messages.create.return_value = _text_response("ok")

        await _run(client, tool_router, max_tokens=512)

        kwargs = client.messages.create.call_args.kwargs
        assert kwargs["model"] == "claude-test"
        assert kwargs["max_tokens"] == 512
        assert kwargs["system"] == "system"

"""A tool that fails three times in a row is stopped, and the model answers (MAG-872)."""

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.container import Container
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient, FakeMessage, FakeStream, FakeToolUseBlock, FakeUsage
from app.llm.prompt_cache import build_system
from app.llm.runner import run_tool_loop
from app.llm.streaming import StreamingGateway
from app.llm.tool_failures import TOOL_FAILURE_LIMIT, FailureStreak, invalid_input
from app.llm.tools import ToolRouter
from app.native_tools import build_native_registry
from tests.test_runner import _text_response, _tool_response

FAILED = json.dumps({"error": "Store not found"})
HELD = json.dumps({"status": "pending_approval"})


class TestFailureStreak:
    def test_three_failures_in_a_row_give_up_on_the_tool(self):
        streak = FailureStreak()
        for _ in range(TOOL_FAILURE_LIMIT - 1):
            streak.record("add_grocery_item", FAILED)
        assert streak.gave_up() is None
        streak.record("add_grocery_item", FAILED)
        assert streak.gave_up() == "add_grocery_item"

    def test_a_success_resets_the_count(self):
        streak = FailureStreak()
        streak.record("a", FAILED)
        streak.record("a", FAILED)
        streak.record("a", '{"ok": true}')
        streak.record("a", FAILED)
        assert streak.gave_up() is None

    def test_failures_of_two_tools_do_not_add_up(self):
        streak = FailureStreak()
        for name in ("a", "b", "a", "b"):
            streak.record(name, FAILED)
        assert streak.gave_up() is None

    def test_a_call_held_for_approval_is_not_a_failure(self):
        streak = FailureStreak()
        for _ in range(5):
            streak.record("a", HELD)
        assert streak.gave_up() is None

    def test_the_note_names_the_tool_and_its_last_error_and_signals(self):
        streak = FailureStreak()
        for _ in range(3):
            streak.record("add_grocery_item", FAILED)
        with patch("app.llm.tool_failures.capture_signal") as signal:
            note = streak.note("user-1", "chat_stream")
        assert note["type"] == "text"
        assert "add_grocery_item" in note["text"]
        assert "Store not found" in note["text"]
        assert signal.call_args.kwargs["tool"] == "add_grocery_item"


class TestRunToolLoop:
    async def test_the_loop_stops_at_the_third_failure_and_answers_without_tools(self):
        client = MagicMock()
        client.messages.create = AsyncMock(
            side_effect=[
                _tool_response("add_grocery_item", {}, "tu-1"),
                _tool_response("add_grocery_item", {}, "tu-2"),
                _tool_response("add_grocery_item", {}, "tu-3"),
                _text_response("Je n'ai pas pu."),
            ]
        )
        router = MagicMock()
        router.call_tool = AsyncMock(return_value=FAILED)
        messages = [{"role": "user", "content": "hello"}]

        with patch("app.llm.runner.record_llm_usage"), patch("app.llm.tool_failures.capture_signal"):
            result = await run_tool_loop(
                "system", messages, [{"name": "add_grocery_item"}], client=client, tool_router=router,
                user_id="user-1", model="m",
            )

        assert router.call_tool.await_count == 3
        calls = client.messages.create.call_args_list
        assert calls[3].kwargs["tool_choice"] == {"type": "none"}
        assert "tool_choice" not in calls[0].kwargs or calls[0].kwargs["tool_choice"] != {"type": "none"}
        last = messages[-1]["content"]
        assert [b["type"] for b in last] == ["tool_result", "text"]
        assert "add_grocery_item" in last[1]["text"]
        assert result["response"] == "Je n'ai pas pu."

    async def test_a_tool_call_after_the_stop_is_not_run(self):
        client = MagicMock()
        client.messages.create = AsyncMock(
            side_effect=[_tool_response("a", {}, f"tu-{i}") for i in range(4)]
        )
        router = MagicMock()
        router.call_tool = AsyncMock(return_value=FAILED)

        with patch("app.llm.runner.record_llm_usage"), patch("app.llm.tool_failures.capture_signal"):
            await run_tool_loop(
                "system", [{"role": "user", "content": "hi"}], [{"name": "a"}], client=client, tool_router=router,
                user_id="user-1", model="m",
            )

        assert router.call_tool.await_count == 3


class TestChatStream:
    async def test_the_stream_stops_at_the_third_failure(self):
        def tool_turn(i):
            return FakeMessage(
                content=[FakeToolUseBlock(id=f"tu-{i}", name="a", input={})],
                stop_reason="tool_use",
                usage=FakeUsage(input_tokens=1, output_tokens=1),
            )

        from app.llm.fake import FakeTextBlock

        turns = [
            tool_turn(1),
            tool_turn(2),
            tool_turn(3),
            FakeMessage(
                content=[FakeTextBlock(text="Je n'ai pas pu.")],
                stop_reason="end_turn",
                usage=FakeUsage(input_tokens=1, output_tokens=1),
            ),
        ]
        seen: list[dict] = []

        def stream(**kwargs):
            seen.append(kwargs)
            return FakeStream(turns[len(seen) - 1])

        with (
            patch("app.llm.streaming.message_repo") as message_repo,
            patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])),
            patch("app.llm.tool_failures.capture_signal"),
        ):
            message_repo.find_recent = AsyncMock(return_value=[])
            message_repo.create = AsyncMock()
            gateway = Container().streaming_gateway
            gateway.client = MagicMock()
            gateway.client.messages.stream = stream
            gateway.tool_router = MagicMock(
                get_tool_definitions=AsyncMock(return_value=[{"name": "a"}]),
                call_tool=AsyncMock(return_value=FAILED),
            )
            gateway._build_system_prompt = AsyncMock(return_value=build_system("S", "V"))
            gateway._resolve_context = AsyncMock(return_value=None)
            gateway._thread_free_parts = AsyncMock(return_value=None)
            with patch("app.llm.streaming.record_llm_usage"):
                async for _ in gateway.chat_stream("salut", "user-1", "msg-1"):
                    pass

        assert gateway.tool_router.call_tool.await_count == 3
        assert len(seen) == 4
        assert seen[3]["tool_choice"] == {"type": "none"}
        assert message_repo.create.call_args.kwargs["content"] == "Je n'ai pas pu."


class TestOnTheFakeModel:
    async def test_after_three_failures_the_answer_is_maggies(self):
        mcp = MagicMock()
        mcp.call_tool = AsyncMock(return_value=FAILED)
        offered = [{"name": "add_grocery_item", "input_schema": {}}]

        with patch("app.llm.tools.mcp_client", mcp), patch("app.llm.runner.record_llm_usage"):
            result = await run_tool_loop(
                "system",
                [{"role": "user", "content": "ajoute du pain chez le boulanger fantôme"}],
                offered,
                client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
                tool_router=ToolRouter(natives=build_native_registry()),
                user_id="user-1",
                model="fake",
            )

        assert mcp.call_tool.await_count == 3
        assert result["response"].startswith("Je n'arrive pas à ajouter le pain")


class TestInvalidInput:
    def test_the_invalid_input_result_names_fields_and_schema(self):
        schema = {"type": "object", "required": ["content"]}
        result = json.loads(invalid_input("add_instruction", [{"field": "content", "problem": "missing"}], schema))
        assert result == {
            "error": "invalid_input",
            "tool": "add_instruction",
            "fields": [{"field": "content", "problem": "missing"}],
            "expected": schema,
        }
        assert "expected" not in json.loads(invalid_input("t", [], None))


    async def test_a_native_tool_missing_a_required_field_is_not_run(self):
        with patch("app.instruction_tools.instruction_repo") as repo, patch("app.llm.tools.policy_engine") as policy:
            policy.evaluate = AsyncMock()
            result = json.loads(await ToolRouter(natives=build_native_registry()).call_tool("add_instruction", {}, user_id="user-1"))

        assert result["error"] == "invalid_input"
        assert result["tool"] == "add_instruction"
        assert {f["field"] for f in result["fields"]} == {"content", "kind"}
        assert result["expected"]["type"] == "object"
        repo.create.assert_not_called()
        policy.evaluate.assert_not_called()

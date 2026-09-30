"""Tests for prompt caching: cacheable prefix, volatile suffix, cache-aware cost."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.gateway import LLMGateway
from app.llm.prompt_cache import EPHEMERAL, build_system, cache_tools
from app.metrics import LLM_COST_USD, usage_kwargs
from app.personality.engine import PersonalityEngine
from app.skills.index import SkillIndex


class TestBuildSystem:
    def test_stable_block_is_cached_and_volatile_is_not(self):
        blocks = build_system("STABLE", "\n\nVOLATILE")

        assert blocks == [
            {"type": "text", "text": "STABLE", "cache_control": EPHEMERAL},
            {"type": "text", "text": "VOLATILE"},
        ]

    def test_empty_volatile_is_omitted(self):
        assert build_system("STABLE", "  \n") == [{"type": "text", "text": "STABLE", "cache_control": EPHEMERAL}]


class TestCacheTools:
    def test_marks_only_the_last_tool_without_mutating_input(self):
        tools = [{"name": "a"}, {"name": "b"}]

        cached = cache_tools(tools)

        assert cached == [{"name": "a"}, {"name": "b", "cache_control": EPHEMERAL}]
        assert tools == [{"name": "a"}, {"name": "b"}]

    @pytest.mark.parametrize("tools", [None, []])
    def test_no_tools_unchanged(self, tools):
        assert cache_tools(tools) == tools


class TestGatewayPrefix:
    @staticmethod
    def _gateway(tmp_path, memory: str = "") -> LLMGateway:
        index = SkillIndex(tmp_path)
        index.rebuild()
        gateway = LLMGateway.__new__(LLMGateway)
        gateway.personality = PersonalityEngine()
        gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=memory))
        gateway._index = index
        return gateway

    async def _build(self, gateway, datetime_line: str, **kwargs) -> list[dict]:
        with (
            patch("app.llm.gateway.skill_index", gateway._index),
            patch("app.llm.gateway.current_datetime_line", return_value=datetime_line),
            patch("app.personality.engine.personality_repo") as repo,
        ):
            repo.get = AsyncMock(return_value=None)
            return await gateway._build_system_prompt("user-1", tools=[{"name": "list_events"}], **kwargs)

    async def test_prefix_is_identical_when_date_and_memory_change(self, tmp_path):
        first = await self._build(
            self._gateway(tmp_path, memory="\n\nAllergie : noix"), "Nous sommes lundi, il est 9h."
        )
        second = await self._build(self._gateway(tmp_path, memory=""), "Nous sommes mardi, il est 10h.")

        assert first[0] == second[0]
        assert first[0]["cache_control"] == EPHEMERAL
        assert "Nous sommes" not in first[0]["text"]
        assert "Allergie : noix" not in first[0]["text"]

    async def test_date_memory_and_preamble_follow_the_prefix(self, tmp_path):
        blocks = await self._build(
            self._gateway(tmp_path, memory="\n\nAllergie : noix"),
            "Nous sommes lundi, il est 9h.",
            preamble="\n\nMode proaction.",
        )

        assert len(blocks) == 2
        assert "cache_control" not in blocks[1]
        assert "Allergie : noix" in blocks[1]["text"]
        assert "Nous sommes lundi, il est 9h." in blocks[1]["text"]
        assert "Mode proaction." in blocks[1]["text"]


class TestUsageAndCost:
    def test_usage_kwargs_reads_cache_counters(self):
        usage = MagicMock(
            input_tokens=10, output_tokens=5, cache_creation_input_tokens=100, cache_read_input_tokens=900
        )

        assert usage_kwargs(usage) == {
            "input_tokens": 10,
            "output_tokens": 5,
            "cache_creation_input_tokens": 100,
            "cache_read_input_tokens": 900,
        }

    def test_usage_kwargs_defaults_missing_counters_to_zero(self):
        usage = MagicMock(spec=["input_tokens", "output_tokens"])
        usage.input_tokens = 10
        usage.output_tokens = 5

        kwargs = usage_kwargs(usage)

        assert kwargs["cache_creation_input_tokens"] == 0
        assert kwargs["cache_read_input_tokens"] == 0

    def test_cost_prices_cache_writes_and_reads(self):
        from app.metrics import record_llm_usage

        labels = {"model": "claude-sonnet-5-5", "call_type": "cost_test"}
        before = LLM_COST_USD.labels(**labels)._value.get()

        record_llm_usage(
            "claude-sonnet-5-5",
            "cost_test",
            input_tokens=1_000_000,
            output_tokens=1_000_000,
            duration_seconds=0.1,
            cache_creation_input_tokens=1_000_000,
            cache_read_input_tokens=1_000_000,
        )

        # 2.00 input + 2.50 cache write + 0.20 cache read + 10.00 output
        assert LLM_COST_USD.labels(**labels)._value.get() - before == pytest.approx(14.70)


class TestStreamingPrefix:
    async def test_chat_stream_sends_cached_system_and_tools_and_records_cache_usage(self):
        from app.llm.streaming import StreamingGateway

        final = MagicMock(stop_reason="end_turn", content=[])
        final.usage = MagicMock(
            input_tokens=10, output_tokens=5, cache_creation_input_tokens=0, cache_read_input_tokens=700
        )

        class FakeStream:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *exc):
                return False

            def __aiter__(self):
                return self

            async def __anext__(self):
                raise StopAsyncIteration

            async def get_final_message(self):
                return final

        with patch("app.llm.streaming.message_repo") as message_repo, patch("app.llm.streaming.context_repo"):
            message_repo.find_recent = AsyncMock(return_value=[])
            message_repo.create = AsyncMock()
            gateway = StreamingGateway()
            gateway.client = MagicMock()
            gateway.client.messages.stream = MagicMock(return_value=FakeStream())
            gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[{"name": "a"}, {"name": "b"}]))
            gateway._build_system_prompt = AsyncMock(return_value=build_system("STABLE", "VOLATILE"))
            gateway._resolve_context = AsyncMock(return_value=None)

            with patch("app.llm.streaming.record_llm_usage") as usage:
                async for _ in gateway.chat_stream("salut", "user-1", "msg-1"):
                    pass

        kwargs = gateway.client.messages.stream.call_args.kwargs
        assert kwargs["system"][0]["cache_control"] == EPHEMERAL
        assert kwargs["tools"][-1]["cache_control"] == EPHEMERAL
        assert usage.call_args.kwargs["cache_read_input_tokens"] == 700


class TestStreamingSystemPrompt:
    async def test_active_contexts_and_date_go_after_the_cached_prefix(self, tmp_path):
        from app.db.context_model import ContextStatus
        from app.llm.streaming import StreamingGateway

        index = SkillIndex(tmp_path)
        index.rebuild()
        context = MagicMock(label="Courses", status=ContextStatus.ACTIVE)

        with (
            patch("app.llm.streaming.skill_index", index),
            patch("app.llm.streaming.context_repo") as context_repo,
            patch("app.llm.streaming.current_datetime_line", return_value="Nous sommes lundi, il est 9h."),
        ):
            context_repo.find_active = AsyncMock(return_value=[context])
            gateway = StreamingGateway.__new__(StreamingGateway)
            gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="BASE"))
            gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value="\n\nAllergie : noix"))

            blocks = await gateway._build_system_prompt("user-1")

        assert blocks[0] == {"type": "text", "text": "BASE", "cache_control": EPHEMERAL}
        assert "Courses" in blocks[1]["text"]
        assert "Allergie : noix" in blocks[1]["text"]
        assert "Nous sommes lundi, il est 9h." in blocks[1]["text"]

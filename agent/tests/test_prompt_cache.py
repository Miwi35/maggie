"""Tests for prompt caching: cacheable prefix, volatile suffix, cache-aware cost."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.gateway import LLMGateway
from app.llm.prompt_cache import EPHEMERAL, build_system, cache_tools
from app.memory.agent_memory import MemoryBlocks
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

    def test_memory_has_its_own_blocks_between_the_stable_prefix_and_the_volatile_text(self):
        blocks = build_system("STABLE", "VOLATILE", "MOMENT", "PROFILE", "RECALLED")

        assert [b["text"] for b in blocks] == ["STABLE", "MOMENT", "PROFILE", "RECALLED", "VOLATILE"]
        # The profile is cached, what came back for this message is not: four breakpoints with the tools.
        assert [("cache_control" in b) for b in blocks] == [True, True, True, False, False]

    def test_a_change_of_memory_leaves_the_stable_prefix_untouched(self):
        before = build_system("STABLE", "V", "MOMENT", "PROFILE A", "RECALLED A")
        after = build_system("STABLE", "V", "MOMENT", "PROFILE B", "")

        assert before[:2] == after[:2]
        assert before[2] != after[2]


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
    def _gateway(memory: str = "", profile: str = "", recalled: str = "") -> LLMGateway:
        index = SkillIndex()
        gateway = LLMGateway.__new__(LLMGateway)
        gateway.personality = PersonalityEngine()
        gateway.agent_memory = MagicMock(
            blocks=AsyncMock(return_value=MemoryBlocks(notice=memory, profile=profile, recalled=recalled))
        )
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

    async def test_prefix_is_identical_when_date_and_memory_change(self):
        first = await self._build(
            self._gateway(memory="\n\nAllergie : noix"), "Nous sommes lundi, il est 9h."
        )
        second = await self._build(self._gateway(memory=""), "Nous sommes mardi, il est 10h.")

        assert first[0] == second[0]
        assert first[0]["cache_control"] == EPHEMERAL
        assert "Nous sommes" not in first[0]["text"]
        assert "Allergie : noix" not in first[0]["text"]

    async def test_date_memory_and_preamble_follow_the_prefix(self):
        blocks = await self._build(
            self._gateway(memory="\n\nAllergie : noix"),
            "Nous sommes lundi, il est 9h.",
            preamble="\n\nMode proaction.",
        )

        assert len(blocks) == 2
        assert "cache_control" not in blocks[1]
        assert "Allergie : noix" in blocks[1]["text"]
        assert "Nous sommes lundi, il est 9h." in blocks[1]["text"]
        assert "Mode proaction." in blocks[1]["text"]


    async def test_profile_and_recalled_cartes_are_blocks_of_their_own(self):
        blocks = await self._build(
            self._gateway(profile="PROFIL : Allergie noix", recalled="RAPPEL : roses"),
            "Nous sommes lundi, il est 9h.",
        )

        texts = [b["text"] for b in blocks]
        assert texts[1] == "PROFIL : Allergie noix"
        assert texts[2] == "RAPPEL : roses"
        assert "Nous sommes lundi" in texts[3]
        assert "cache_control" in blocks[1]
        assert "cache_control" not in blocks[2]

    async def test_a_memory_write_does_not_rewrite_the_personality_block(self):
        before = await self._build(self._gateway(profile="PROFIL A", recalled="RAPPEL A"), "Lundi 9h.")
        after = await self._build(self._gateway(profile="PROFIL A\n- Famille/Francoise.md", recalled=""), "Lundi 9h.")

        assert before[0] == after[0]
        assert before[0]["cache_control"] == EPHEMERAL
        assert "PROFIL" not in before[0]["text"]

    async def test_the_gateway_asks_the_memory_about_what_was_said(self):
        gateway = self._gateway()

        await self._build(gateway, "Lundi 9h.", about="Un bouquet pour la fete des meres", source="chat")

        args, kwargs = gateway.agent_memory.blocks.await_args
        assert args == ("user-1", "Un bouquet pour la fete des meres")
        assert kwargs["source"] == "chat"


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

    def test_opus_5_5_reads_the_cache_at_five_percent(self):
        from app.metrics import record_llm_usage

        labels = {"model": "claude-opus-5-5", "call_type": "cost_test"}
        before = LLM_COST_USD.labels(**labels)._value.get()

        record_llm_usage(
            "claude-opus-5-5",
            "cost_test",
            input_tokens=1_000_000,
            output_tokens=1_000_000,
            duration_seconds=0.1,
            cache_creation_input_tokens=1_000_000,
            cache_read_input_tokens=1_000_000,
        )

        # 4.00 input + 5.00 cache write + 0.20 cache read + 20.00 output
        assert LLM_COST_USD.labels(**labels)._value.get() - before == pytest.approx(29.20)


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

        with patch("app.llm.streaming.message_repo") as message_repo:
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
    async def test_active_contexts_and_date_go_after_the_cached_prefix(self):
        from app.db.context_model import ContextStatus
        from app.llm.streaming import StreamingGateway

        index = SkillIndex()
        context = MagicMock(label="Courses", status=ContextStatus.ACTIVE)

        with (
            patch("app.llm.streaming.skill_index", index),
            patch("app.llm.contexts.context_repo") as context_repo,
            patch("app.llm.streaming.current_datetime_line", return_value="Nous sommes lundi, il est 9h."),
        ):
            context_repo.find_active = AsyncMock(return_value=[context])
            gateway = StreamingGateway.__new__(StreamingGateway)
            gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="BASE"))
            gateway.agent_memory = MagicMock(
                blocks=AsyncMock(return_value=MemoryBlocks(notice="\n\nAllergie : noix"))
            )

            blocks = await gateway._build_system_prompt("user-1")

        assert blocks[0] == {"type": "text", "text": "BASE", "cache_control": EPHEMERAL}
        assert "Courses" in blocks[1]["text"]
        assert "Allergie : noix" in blocks[1]["text"]
        assert "Nous sommes lundi, il est 9h." in blocks[1]["text"]

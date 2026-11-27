"""Tests for prompt caching: cacheable prefix, volatile suffix, cache-aware cost."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from tests.prompt_helpers import stub_system_prompt, system_prompt_with
from app.skills.index import MOMENT_CHAT
from app.container import Container
from app.llm.gateway import LLMGateway
from app.llm.prompt_cache import EPHEMERAL, EPHEMERAL_1H, build_system, cache_tools
from app.llm.web_search import with_web_search
from app.memory.agent_memory import MemoryBlocks
from app.metrics import LLM_COST_USD, usage_kwargs
from app.personality.engine import PersonalityEngine
from app.skills.index import SkillIndex


class TestBuildSystem:
    def test_stable_block_is_cached_and_volatile_is_not(self):
        blocks = build_system("STABLE", "\n\nVOLATILE")

        assert blocks == [
            {"type": "text", "text": "STABLE", "cache_control": EPHEMERAL_1H},
            {"type": "text", "text": "VOLATILE"},
        ]

    def test_empty_volatile_is_omitted(self):
        assert build_system("STABLE", "  \n") == [{"type": "text", "text": "STABLE", "cache_control": EPHEMERAL_1H}]

    def test_memory_has_its_own_blocks_between_the_stable_prefix_and_the_volatile_text(self):
        blocks = build_system("STABLE", "VOLATILE", "MOMENT", "PROFILE", "RECALLED")

        assert [b["text"] for b in blocks] == ["STABLE", "MOMENT", "PROFILE", "RECALLED", "VOLATILE"]
        # The profile is cached, what came back for this message is not: four breakpoints with the tools.
        assert [("cache_control" in b) for b in blocks] == [True, True, True, False, False]

    def test_stable_block_is_one_hour_and_moment_and_profile_five_minutes(self):
        blocks = build_system("STABLE", "V", "MOMENT", "PROFILE")

        assert [b["cache_control"] for b in blocks[:3]] == [EPHEMERAL_1H, EPHEMERAL, EPHEMERAL]

    def test_a_change_of_memory_leaves_the_stable_prefix_untouched(self):
        before = build_system("STABLE", "V", "MOMENT", "PROFILE A", "RECALLED A")
        after = build_system("STABLE", "V", "MOMENT", "PROFILE B", "")

        assert before[:2] == after[:2]
        assert before[2] != after[2]


class TestCacheTools:
    def test_marks_only_the_last_tool_without_mutating_input(self):
        tools = [{"name": "a"}, {"name": "b"}]

        cached = cache_tools(tools)

        assert cached == [{"name": "a"}, {"name": "b", "cache_control": EPHEMERAL_1H}]
        assert tools == [{"name": "a"}, {"name": "b"}]

    def test_the_breakpoint_stays_on_the_last_shared_tool_when_the_web_is_offered(self):
        cached = cache_tools(with_web_search([{"name": "a"}, {"name": "b"}]))

        assert cached[1] == {"name": "b", "cache_control": EPHEMERAL_1H}
        assert cached[-1]["name"] == "web_search"
        assert "cache_control" not in cached[-1]
        assert "cache_control" not in cached[0]

    def test_a_web_search_alone_gets_no_breakpoint(self):
        cached = cache_tools(with_web_search([]))

        assert [t["name"] for t in cached] == ["web_search"]
        assert "cache_control" not in cached[0]

    @pytest.mark.parametrize("tools", [None, []])
    def test_no_tools_unchanged(self, tools):
        assert cache_tools(tools) == tools


class TestGatewayPrefix:
    @staticmethod
    def _gateway(memory: str = "", profile: str = "", recalled: str = "") -> LLMGateway:
        index = SkillIndex()
        gateway = LLMGateway.__new__(LLMGateway)
        gateway.agent_memory = MagicMock(
            blocks=AsyncMock(return_value=MemoryBlocks(notice=memory, profile=profile, recalled=recalled))
        )
        gateway.system_prompt = system_prompt_with(personality=PersonalityEngine(), agent_memory=gateway.agent_memory)
        gateway._index = index
        return gateway

    async def _build(self, gateway, datetime_line: str, **kwargs) -> list[dict]:
        with (
            patch("app.prompt_sources.skill_index", gateway._index),
            patch("app.prompt_sources.current_datetime_line", return_value=datetime_line),
            patch("app.personality.engine.personality_repo") as repo,
        ):
            repo.get = AsyncMock(return_value=None)
            return await gateway.system_prompt.build("user-1", tools=[{"name": "list_events"}], **kwargs)

    async def test_prefix_is_identical_when_date_and_memory_change(self):
        first = await self._build(
            self._gateway(memory="\n\nAllergie : noix"), "Nous sommes lundi, il est 9h."
        )
        second = await self._build(self._gateway(memory=""), "Nous sommes mardi, il est 10h.")

        assert first[0] == second[0]
        assert first[0]["cache_control"] == EPHEMERAL_1H
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
        assert before[0]["cache_control"] == EPHEMERAL_1H
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
            "cache_creation_1h_input_tokens": 0,
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
        assert LLM_COST_USD.labels(**labels)._value.get() - before == pytest.approx(14.60)

    def test_usage_kwargs_reads_the_one_hour_writes(self):
        usage = MagicMock(input_tokens=1, output_tokens=1, cache_creation_input_tokens=100, cache_read_input_tokens=0)
        usage.cache_creation = MagicMock(ephemeral_1h_input_tokens=60)

        assert usage_kwargs(usage)["cache_creation_1h_input_tokens"] == 60
        assert usage_kwargs(MagicMock())["cache_creation_1h_input_tokens"] == 0

    def test_one_hour_writes_cost_twice_the_input_price(self):
        from app.metrics import record_llm_usage

        labels = {"model": "claude-sonnet-5-5", "call_type": "cost_test"}
        before = LLM_COST_USD.labels(**labels)._value.get()

        record_llm_usage(
            "claude-sonnet-5-5",
            "cost_test",
            input_tokens=0,
            output_tokens=0,
            duration_seconds=0.1,
            cache_creation_input_tokens=1_000_000,
            cache_creation_1h_input_tokens=600_000,
        )

        # 400k at 1.25 x 2.00 + 600k at 2 x 2.00
        assert LLM_COST_USD.labels(**labels)._value.get() - before == pytest.approx(1.0 + 2.4)

    def test_sonnet_5_5_reads_the_cache_at_five_percent(self):
        from app.metrics import record_llm_usage

        labels = {"model": "claude-sonnet-5-5", "call_type": "cost_test"}
        before = LLM_COST_USD.labels(**labels)._value.get()

        record_llm_usage(
            "claude-sonnet-5-5",
            "cost_test",
            input_tokens=0,
            output_tokens=0,
            duration_seconds=0.1,
            cache_read_input_tokens=1_000_000,
        )

        assert LLM_COST_USD.labels(**labels)._value.get() - before == pytest.approx(0.10)

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

        with patch("app.llm.streaming.message_repo") as message_repo, patch("app.llm.streaming.message_service", message_repo):
            message_repo.find_recent = AsyncMock(return_value=[])
            message_repo.create = AsyncMock()
            gateway = Container().streaming_gateway
            gateway.client = MagicMock()
            gateway.client.messages.stream = MagicMock(return_value=FakeStream())
            gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[{"name": "a"}, {"name": "b"}]))
            gateway.system_prompt = stub_system_prompt(build_system("STABLE", "VOLATILE"))
            gateway._resolve_context = AsyncMock(return_value=None)
            gateway.system_prompt.thread_free = AsyncMock(return_value=None)

            with patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])), patch(
                "app.runtime.loop.record_llm_usage"
            ) as usage:
                async for _ in gateway.chat_stream("salut", "user-1", "msg-1"):
                    pass

        kwargs = gateway.client.messages.stream.call_args.kwargs
        assert kwargs["system"][0]["cache_control"] == EPHEMERAL_1H
        assert kwargs["tools"][-2]["cache_control"] == EPHEMERAL_1H
        assert usage.call_args.kwargs["cache_read_input_tokens"] == 700

    async def test_tools_and_stable_block_carry_the_one_hour_ttl_before_any_five_minute_entry(self):
        from app.config import settings
        from app.llm.streaming import StreamingGateway

        final = MagicMock(stop_reason="end_turn", content=[])
        final.usage = MagicMock(input_tokens=1, output_tokens=1, cache_creation_input_tokens=0, cache_read_input_tokens=0)

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

        with patch("app.llm.streaming.message_repo") as message_repo, patch("app.llm.streaming.message_service", message_repo):
            message_repo.find_recent = AsyncMock(return_value=[])
            message_repo.create = AsyncMock()
            gateway = Container().streaming_gateway
            gateway.client = MagicMock()
            gateway.client.messages.stream = MagicMock(return_value=FakeStream())
            gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[{"name": "a"}, {"name": "b"}]))
            gateway.system_prompt = stub_system_prompt(build_system("STABLE", "VOLATILE", "MOMENT", "PROFILE"))
            gateway._resolve_context = AsyncMock(return_value=None)
            gateway.system_prompt.thread_free = AsyncMock(return_value=None)

            with patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])), patch(
                "app.runtime.loop.record_llm_usage"
            ):
                async for _ in gateway.chat_stream("salut", "user-1", "msg-1"):
                    pass

        kwargs = gateway.client.messages.stream.call_args.kwargs
        rendered = [*kwargs["tools"], *kwargs["system"]]
        assert [b["cache_control"] for b in rendered if "cache_control" in b] == [
            EPHEMERAL_1H,
            EPHEMERAL_1H,
            EPHEMERAL,
            EPHEMERAL,
        ]
        assert kwargs["output_config"] == {"effort": "medium"}
        assert kwargs["max_tokens"] == settings.llm_max_tokens


class TestSharedToolPrefix:
    async def test_chat_proaction_and_planning_send_the_same_tools_first(self):
        from app.llm.streaming import StreamingGateway

        tools = [{"name": "a"}, {"name": "b"}]
        final = MagicMock(stop_reason="end_turn", content=[])
        final.usage = MagicMock(input_tokens=1, output_tokens=1, cache_creation_input_tokens=0, cache_read_input_tokens=0)

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

        with patch("app.llm.streaming.message_repo") as message_repo, patch("app.llm.streaming.message_service", message_repo):
            message_repo.find_recent = AsyncMock(return_value=[])
            message_repo.create = AsyncMock()
            chat = Container().streaming_gateway
            chat.client = MagicMock()
            chat.client.messages.stream = MagicMock(return_value=FakeStream())
            chat.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=tools))
            chat.system_prompt = stub_system_prompt(build_system("STABLE"))
            chat._resolve_context = AsyncMock(return_value=None)
            chat.system_prompt.thread_free = AsyncMock(return_value=None)
            with patch("app.llm.streaming.open_threads", AsyncMock(return_value=[])), patch(
                "app.runtime.loop.record_llm_usage"
            ):
                async for _ in chat.chat_stream("salut", "user-1", "msg-1"):
                    pass
        chat_tools = chat.client.messages.stream.call_args.kwargs["tools"]

        response = MagicMock(stop_reason="end_turn", content=[MagicMock(type="text", text="ok")])
        response.usage = final.usage
        sent = {}
        for silent in (False, True):
            gateway = LLMGateway.__new__(LLMGateway)
            gateway.client = MagicMock()
            gateway.client.messages.create = AsyncMock(return_value=response)
            gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=tools))
            gateway.system_prompt = stub_system_prompt(build_system("STABLE"))
            gateway._resolve_proaction_context = AsyncMock(return_value=None)
            with (
                patch("app.prompt_sources.behavior_directives_section", AsyncMock(return_value="")),
                patch("app.llm.contexts.context_repo") as contexts,
                patch("app.runtime.loop.note_provider_success", AsyncMock()),
            ):
                contexts.find_active = AsyncMock(return_value=[])
                await gateway.proaction("Fais-le", "user-1", silent=silent)
            sent[silent] = gateway.client.messages.create.call_args.kwargs["tools"]

        assert chat_tools[-1]["name"] == "web_search"
        assert sent[False] == chat_tools[:-1] == sent[True]
        assert sent[False][-1] == {"name": "b", "cache_control": EPHEMERAL_1H}


class TestStreamingSystemPrompt:
    async def test_active_contexts_and_date_go_after_the_cached_prefix(self):
        from app.db.context_model import ContextStatus
        from app.llm.streaming import StreamingGateway

        index = SkillIndex()
        context = MagicMock(label="Courses", status=ContextStatus.ACTIVE)

        with (
            patch("app.prompt_sources.skill_index", index),
            patch("app.llm.contexts.context_repo") as context_repo,
            patch("app.prompt_sources.current_datetime_line", return_value="Nous sommes lundi, il est 9h."),
        ):
            context_repo.find_active = AsyncMock(return_value=[context])
            gateway = StreamingGateway.__new__(StreamingGateway)
            gateway.system_prompt = system_prompt_with(
                personality=MagicMock(get_system_prompt=AsyncMock(return_value="BASE")),
                agent_memory=MagicMock(blocks=AsyncMock(return_value=MemoryBlocks(notice="\n\nAllergie : noix"))),
            )

            blocks = await gateway.system_prompt.build("user-1", moment=MOMENT_CHAT)

        assert blocks[0] == {"type": "text", "text": "BASE", "cache_control": EPHEMERAL_1H}
        assert "Courses" in blocks[1]["text"]
        assert "Allergie : noix" in blocks[1]["text"]
        assert "Nous sommes lundi, il est 9h." in blocks[1]["text"]

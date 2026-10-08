"""Tests for StreamingGateway AG-UI event emission."""

import asyncio
from zoneinfo import ZoneInfo
import json
from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.db.context_model import ContextStatus
from app.llm.fake import FakeMessage, FakeStream, FakeTextBlock, FakeUsage
from app.llm.streaming import StreamingGateway, tool_result_status


class TestStreamingGateway:
    """Test StreamingGateway event emission."""

    @pytest.mark.asyncio
    async def test_no_client_returns_error(self):
        """When no API key is configured, emit error text."""
        with patch("app.llm.streaming.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            gw = StreamingGateway()
            assert gw.client is None

            events = []
            async for event in gw.chat_stream("hello", "user-1", "msg-1"):
                events.append(event)

            types = [e["type"] for e in events]
            assert types[0] == "RUN_STARTED"
            assert "TEXT_MESSAGE_START" in types
            assert "TEXT_MESSAGE_CONTENT" in types
            assert "TEXT_MESSAGE_END" in types
            assert types[-1] == "RUN_FINISHED"

    @pytest.mark.asyncio
    async def test_resolve_context_creates_new(self):
        """When no contexts exist, creates a new one."""
        with (
            patch("app.llm.contexts.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            mock_repo.find_active = AsyncMock(return_value=[])
            ctx = MagicMock()
            ctx.id = "ctx-new"
            ctx.label = "Liste de courses"
            ctx.status = MagicMock()
            ctx.status.value = "active"
            mock_repo.create = AsyncMock(return_value=ctx)
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            # Mock the Anthropic client response
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": null, "label": "Liste de courses"}')]
            response.usage.input_tokens = 50
            response.usage.output_tokens = 20
            gw.client = MagicMock()
            gw.client.messages = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Qu'est-ce que j'ai sur ma liste ?", "user-1", "msg-1")

            assert result["action"] == "created"
            assert result["label"] == "Liste de courses"
            mock_repo.create.assert_called_once_with("user-1", "Liste de courses")

    @pytest.mark.asyncio
    async def test_resolve_context_matches_existing(self):
        """When an existing context matches, returns it."""
        with (
            patch("app.llm.contexts.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            existing = MagicMock()
            existing.id = "ctx-123"
            existing.label = "Tâches urgentes"
            existing.status = MagicMock()
            existing.status.value = "active"
            existing.updated_at = datetime.now(UTC)
            mock_repo.find_active = AsyncMock(return_value=[existing])
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": "ctx-123"}')]
            response.usage.input_tokens = 80
            response.usage.output_tokens = 15
            gw.client = MagicMock()
            gw.client.messages = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Marque la première comme faite", "user-1", "msg-2")

            assert result["action"] == "matched"
            assert result["id"] == "ctx-123"
            mock_repo.create.assert_not_called()

    @pytest.mark.asyncio
    async def test_resolve_context_returns_none_on_error(self):
        """On LLM error, returns None gracefully."""
        with patch("app.llm.contexts.context_repo") as mock_repo:
            mock_repo.find_active = AsyncMock(return_value=[])

            gw = StreamingGateway()
            gw.client = MagicMock()
            gw.client.messages = MagicMock()
            gw.client.messages.create = AsyncMock(side_effect=Exception("API error"))

            result = await gw._resolve_context("test", "user-1", "msg-1")
            assert result is None

    @pytest.mark.asyncio
    async def test_resolve_context_no_client(self):
        """When no client configured, returns None."""
        gw = StreamingGateway()
        gw.client = None
        result = await gw._resolve_context("test", "user-1", "msg-1")
        assert result is None

    @pytest.mark.asyncio
    async def test_resolve_context_carries_the_summary(self):
        """The Mind panel replaces the whole context on this event — a missing summary erases it (MAG-11)."""
        with (
            patch("app.llm.contexts.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            existing = MagicMock()
            existing.id = "ctx-123"
            existing.label = "Courses de la semaine"
            existing.summary = "L'utilisateur prépare ses courses."
            existing.updated_at = datetime.now(UTC)
            mock_repo.find_active = AsyncMock(return_value=[existing])
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": "ctx-123"}')]
            response.usage.input_tokens = 80
            response.usage.output_tokens = 15
            gw.client = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Et aussi du beurre", "user-1", "msg-2")

            assert result["summary"] == "L'utilisateur prépare ses courses."

    @pytest.mark.asyncio
    async def test_resolve_context_new_has_no_summary_yet(self):
        with (
            patch("app.llm.contexts.context_repo") as mock_repo,
            patch("app.llm.streaming.message_repo") as mock_msg_repo,
        ):
            mock_repo.find_active = AsyncMock(return_value=[])
            ctx = MagicMock()
            ctx.id = "ctx-new"
            mock_repo.create = AsyncMock(return_value=ctx)
            mock_msg_repo.update_context = AsyncMock()

            gw = StreamingGateway()
            response = MagicMock()
            response.content = [MagicMock(text='{"context_id": null, "label": "Budget"}')]
            response.usage.input_tokens = 50
            response.usage.output_tokens = 20
            gw.client = MagicMock()
            gw.client.messages.create = AsyncMock(return_value=response)

            result = await gw._resolve_context("Où en est mon budget ?", "user-1", "msg-1")

            assert result["summary"] is None


def _context(
    label: str,
    summary: str | None = None,
    status: ContextStatus = ContextStatus.ACTIVE,
    context_id: str = "ctx-1",
) -> MagicMock:
    ctx = MagicMock()
    ctx.id = context_id
    ctx.label = label
    ctx.summary = summary
    ctx.status = status
    ctx.tool_calls_log = []
    return ctx


async def _system_blocks(contexts: list, current_context_id: str | None = None) -> list[dict]:
    """The system blocks the gateway would send, with everything but the contexts emptied."""
    with (
        patch("app.llm.contexts.context_repo") as repo,
        patch("app.llm.streaming.skill_index") as skills,
        patch("app.llm.streaming.behavior_directives_section", AsyncMock(return_value="")),
    ):
        repo.find_active = AsyncMock(return_value=contexts)
        skills.get_skills_index.return_value = ""
        skills.skills_for_moment.return_value = ""
        skills.refresh = AsyncMock()
        gw = StreamingGateway()
        gw.personality = MagicMock()
        gw.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
        gw.agent_memory = MagicMock()
        gw.agent_memory.get_memory_context = AsyncMock(return_value="")
        return await gw._build_system_prompt("user-1", current_context_id=current_context_id)


class TestSystemPromptSummaries:
    """What a thread leaves behind once it has scrolled past the raw history (MAG-11)."""

    async def test_an_open_thread_carries_its_summary(self):
        blocks = await _system_blocks([_context("Courses de la semaine", "Deux kilos de farine à acheter.")])

        volatile = blocks[1]["text"]
        assert "Courses de la semaine" in volatile
        assert "Résumé : Deux kilos de farine à acheter." in volatile

    async def test_a_thread_without_one_is_just_its_label(self):
        blocks = await _system_blocks([_context("Budget")])

        assert "Budget" in blocks[1]["text"]
        assert "Résumé" not in blocks[1]["text"]

    async def test_the_summary_stays_out_of_the_cached_prefix(self):
        """A summary changes while the personality and the skill index do not — caching it would waste the prefix."""
        blocks = await _system_blocks([_context("Courses", "Deux kilos de farine.")])

        assert blocks[0]["cache_control"] == {"type": "ephemeral"}
        assert "Deux kilos de farine." not in blocks[0]["text"]

    async def test_only_the_most_recent_threads_carry_one(self):
        """Every thread keeps its label; the tokens are capped on the summaries alone."""
        contexts = [_context(f"Fil {rank}", f"Résumé du fil {rank}.") for rank in range(7)]

        volatile = (await _system_blocks(contexts))[1]["text"]

        assert "Fil 6" in volatile  # the label of the oldest open thread is still there
        assert "Résumé du fil 4." in volatile
        assert "Résumé du fil 5." not in volatile

    async def test_the_thread_the_history_came_from_is_named(self):
        """The history is one thread's messages, so the prompt has to say which one (MAG-13)."""
        contexts = [
            _context("Courses", "Deux kilos de farine.", context_id="ctx-courses"),
            _context("Budget", "Le mois tient.", context_id="ctx-budget"),
        ]

        volatile = (await _system_blocks(contexts, "ctx-budget"))[1]["text"]

        assert "Budget (active) ← fil en cours" in volatile
        assert "Courses (active) ←" not in volatile


class TestSummaryTrigger:
    """The summary is spawned after the exchange is stored, and the run does not wait for it."""

    @staticmethod
    def _gateway():
        gw = StreamingGateway()
        gw.client = MagicMock()
        gw.client.messages.stream = lambda **_kwargs: FakeStream(
            FakeMessage(
                content=[FakeTextBlock(text="C'est noté.")],
                stop_reason="end_turn",
                usage=FakeUsage(input_tokens=10, output_tokens=5),
            )
        )
        gw.personality = MagicMock()
        gw.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
        gw.agent_memory = MagicMock()
        gw.agent_memory.get_memory_context = AsyncMock(return_value="")
        gw.tool_router = MagicMock()
        gw.tool_router.get_tool_definitions = AsyncMock(return_value=[])
        gw._resolve_context = AsyncMock(
            return_value={"action": "matched", "id": "ctx-1", "label": "Courses", "status": "active", "summary": None}
        )
        return gw

    async def test_the_run_finishes_without_waiting_for_the_summary(self):
        running = asyncio.Event()
        release = asyncio.Event()

        async def never_finishes(context_id: str) -> None:
            running.set()
            await release.wait()

        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "Il me faut de la farine"}])),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            messages.create = AsyncMock()
            summarizer.maybe_summarize = never_finishes

            gw = self._gateway()
            events = [event async for event in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1")]

            # The stream is over while the summary is still mid-flight: that is the
            # whole point of spawning it, since nobody is waiting for the result.
            assert events[-1]["type"] == "RUN_FINISHED"
            await asyncio.sleep(0)
            assert running.is_set()

            release.set()
            await asyncio.gather(*gw._background)

    async def test_it_summarizes_the_thread_the_exchange_landed_in(self):
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "Il me faut de la farine"}])),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = self._gateway()
            async for _ in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1"):
                pass
            await asyncio.gather(*gw._background)

            summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")

    async def test_the_thread_is_routed_before_the_history_is_loaded(self):
        """The whole point of MAG-13: a routing that happens afterwards decides nothing."""
        calls: list[str] = []
        seen: dict = {}

        async def route(*_args, **_kwargs):
            calls.append("route")
            return {"action": "matched", "id": "ctx-1", "label": "Courses", "status": "active", "summary": None}

        async def history(_user_id, *, context_id=None, pending_message=None, **rest):
            calls.append(f"history:{context_id}")
            seen.update(rest)
            return [{"role": "user", "content": "Il me faut de la farine"}]

        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.build_history", history),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = self._gateway()
            gw._resolve_context = route
            gw._build_system_prompt = AsyncMock(return_value=[{"type": "text", "text": "Tu es Maggie."}])
            async for _ in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1"):
                pass
            await asyncio.gather(*gw._background)

        # And the history was built for the thread the routing had just resolved, which is
        # the only reason the order matters.
        assert calls == ["route", "history:ctx-1"]
        # The same thread reaches the system prompt, so the open-threads list can mark the
        # one whose messages the history is made of.
        assert gw._build_system_prompt.await_args.kwargs["current_context_id"] == "ctx-1"
        # And the message being answered is named, so a tag that could not be written does
        # not have Maggie reading the question as a neighbour thread's.
        # The user's timezone dates the days of the history (MAG-349).
        assert isinstance(seen.pop("tz"), ZoneInfo)
        assert seen == {
            "fallback_message": "Il me faut de la farine",
            "current_message_id": "msg-1",
            "screen_context": None,
        }

    async def test_a_routing_failure_still_answers_from_the_global_window(self):
        seen: list[str | None] = []

        async def history(_user_id, *, context_id=None, pending_message=None, **_rest):
            seen.append(context_id)
            return [{"role": "user", "content": "Il me faut de la farine"}]

        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.build_history", history),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = self._gateway()
            gw._resolve_context = AsyncMock(return_value=None)
            events = [event async for event in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1")]

        assert seen == [None]
        assert events[-1]["type"] == "RUN_FINISHED"

    async def test_a_run_with_no_context_summarizes_nothing(self):
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.message_repo") as messages,
            patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "Il me faut de la farine"}])),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            messages.create = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = self._gateway()
            gw._resolve_context = AsyncMock(return_value=None)
            async for _ in gw.chat_stream("Il me faut de la farine", "user-1", "msg-1"):
                pass

            assert gw._background == set()
            summarizer.maybe_summarize.assert_not_awaited()


class TestStreamedExchangeReachesOtherDevices:
    """A second tab or the phone only learns of a streamed exchange over Mercure (MAG-109)."""

    async def test_the_answer_is_published_under_the_id_the_stream_announced(self, chat_db):
        publish = AsyncMock()

        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.build_history", AsyncMock(return_value=[{"role": "user", "content": "Bonjour"}])),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.context_summarizer") as summarizer,
            patch("app.db.message_repository.message_repo.publisher.publish", new=publish),
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            gw = TestSummaryTrigger._gateway()
            events = [event async for event in gw.chat_stream("Bonjour", "user-1", "msg-1")]
            await asyncio.gather(*gw._background)

        streamed_id = next(e["messageId"] for e in events if e["type"] == "TEXT_MESSAGE_START")
        publish.assert_awaited_once()
        topic, payload = publish.await_args.args
        assert topic == "/chat/user-1"
        assert payload["content"] == "C'est noté."
        # The device that streamed the answer keeps the id it was given, so the echo
        # of the same message is recognised and not shown a second time.
        assert payload["id"] == streamed_id

    def test_the_user_message_is_published_when_the_run_is_streamed(self, authed_client, chat_db):
        publish = AsyncMock()

        async def empty_run(message, user_id, user_msg_id, **_kwargs):
            yield {"type": "RUN_STARTED", "runId": "run-1"}
            yield {"type": "RUN_FINISHED", "runId": "run-1"}

        with (
            patch("app.api.routes.streaming_gateway") as gateway,
            patch("app.db.message_repository.message_repo.publisher.publish", new=publish),
        ):
            gateway.chat_stream = empty_run
            response = authed_client.post("/chat/stream", json={"message": "Bonjour Maggie"})

        assert response.status_code == 200
        publish.assert_awaited_once()
        topic, payload = publish.await_args.args
        assert topic == "/chat/test-user"
        assert (payload["role"], payload["content"]) == ("user", "Bonjour Maggie")

    def test_the_published_question_carries_no_screen_context(self, authed_client, chat_db):
        """What goes out on the topic is what the other clients show (MAG-30).

        The recette was refused on this very payload: the mobile chat and the web chat both
        drew the user's bubble from it, block included.
        """
        publish = AsyncMock()

        async def empty_run(message, user_id, user_msg_id, **_kwargs):
            yield {"type": "RUN_FINISHED", "runId": "run-1"}

        block = "[Contexte de l'écran]\nPage : https://dice.fm/event/x?utm_source=spam"

        with (
            patch("app.api.routes.streaming_gateway") as gateway,
            patch("app.db.message_repository.message_repo.publisher.publish", new=publish),
        ):
            gateway.chat_stream = empty_run
            response = authed_client.post(
                "/chat/stream",
                json={"message": "De quoi parle cette page ?", "screen_context": block},
            )

        assert response.status_code == 200
        _topic, payload = publish.await_args.args
        assert payload["content"] == "De quoi parle cette page ?"


class TestToolResultStatus:
    """What the Mind panel and the thread's activity log are told a tool call did (MAG-4)."""

    def test_a_held_call_is_its_own_outcome_not_an_error(self):
        # Drawn as an error it would read « Maggie tried and failed »; the truth is she
        # has asked and the card is on the user's screen.
        held = json.dumps({"status": "pending_approval", "approval_id": "01H", "message": "…"})

        assert tool_result_status(held) == "pending_approval"

    def test_an_error_is_an_error(self):
        assert tool_result_status(json.dumps({"error": "Action interdite par la politique"})) == "error"

    def test_a_result_is_a_success(self):
        assert tool_result_status(json.dumps({"deleted": True})) == "success"

    def test_a_list_result_is_a_success(self):
        assert tool_result_status(json.dumps([{"id": "evt-1"}])) == "success"

    def test_plain_text_mentioning_an_error_key_is_still_an_error(self):
        assert tool_result_status('oops {"error": "boom"} oops') == "error"

    def test_plain_text_is_a_success(self):
        assert tool_result_status("Event created successfully") == "success"

"""The thread summarizer (MAG-11).

Everything here is best-effort by design: the summary is written after the answer the
user was waiting for has been sent, so no failure has anyone to report to. The tests
below are mostly about *that* — each error branch has to leave the stored summary
untouched and return `None`, rather than raise into a background task nobody awaits.
"""

from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.context_summary import ContextSummarizer


def _answer(text: str) -> MagicMock:
    response = MagicMock()
    response.content = [SimpleNamespace(text=text, type="text")]
    response.usage = SimpleNamespace(input_tokens=120, output_tokens=40)
    return response


def _context(summary: str | None = None, summary_updated_at: datetime | None = None) -> MagicMock:
    ctx = MagicMock()
    ctx.id = "ctx-1"
    ctx.label = "Courses de la semaine"
    ctx.summary = summary
    ctx.summary_updated_at = summary_updated_at
    return ctx


def _messages(*pairs: tuple[str, str]) -> list[SimpleNamespace]:
    return [SimpleNamespace(role=role, content=content) for role, content in pairs]


@pytest.fixture()
def summarizer():
    """A summarizer whose model and repositories are mocked, with the client already built."""
    with (
        patch("app.llm.context_summary.context_repo") as context_repo,
        patch("app.llm.context_summary.message_repo") as message_repo,
    ):
        instance = ContextSummarizer()
        instance.client = MagicMock()
        instance.client.messages.create = AsyncMock(return_value=_answer("L'utilisateur prépare ses courses."))
        yield SimpleNamespace(it=instance, contexts=context_repo, messages=message_repo)


class TestSummarize:
    async def test_stores_what_the_model_answered(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(
            return_value=_messages(("user", "Il me faut de la farine"), ("assistant", "C'est noté"))
        )

        result = await summarizer.it.summarize("ctx-1")

        assert result == "L'utilisateur prépare ses courses."
        summarizer.contexts.set_summary.assert_awaited_once_with("ctx-1", "L'utilisateur prépare ses courses.")

    async def test_sends_the_thread_with_who_said_what(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(
            return_value=_messages(("user", "Il me faut de la farine"), ("assistant", "C'est noté"))
        )

        await summarizer.it.summarize("ctx-1")

        sent = summarizer.it.client.messages.create.await_args.kwargs
        prompt = sent["messages"][0]["content"]
        # Without the role prefix a summary cannot tell a request from an answer.
        assert "Utilisateur : Il me faut de la farine" in prompt
        assert "Maggie : C'est noté" in prompt
        assert "Courses de la semaine" in prompt

    async def test_reads_only_what_the_previous_summary_does_not_cover(self, summarizer):
        covered_up_to = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)
        summarizer.contexts.get = AsyncMock(
            return_value=_context(summary="Déjà dit.", summary_updated_at=covered_up_to)
        )
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(return_value=_messages(("user", "Et aussi du beurre")))

        await summarizer.it.summarize("ctx-1")

        assert summarizer.messages.find_by_context.await_args.kwargs["since"] == covered_up_to
        # The previous summary goes back in, which is what keeps a pass incremental
        # instead of re-reading a thread from its first message.
        assert "Déjà dit." in summarizer.it.client.messages.create.await_args.kwargs["messages"][0]["content"]

    async def test_records_what_the_call_cost(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(return_value=_messages(("user", "Bonjour")))

        with patch("app.llm.context_summary.record_llm_usage") as record:
            await summarizer.it.summarize("ctx-1")

        assert record.call_args.kwargs["call_type"] == "context_summary"
        assert record.call_args.kwargs["input_tokens"] == 120

    async def test_unknown_context_writes_nothing(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=None)
        summarizer.contexts.set_summary = AsyncMock()

        assert await summarizer.it.summarize("ctx-gone") is None
        summarizer.contexts.set_summary.assert_not_awaited()

    async def test_a_thread_with_nothing_new_is_left_alone(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context(summary="Déjà dit."))
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(return_value=[])

        assert await summarizer.it.summarize("ctx-1") is None
        summarizer.contexts.set_summary.assert_not_awaited()
        summarizer.it.client.messages.create.assert_not_awaited()

    async def test_an_empty_answer_is_not_stored(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(return_value=_messages(("user", "Bonjour")))
        summarizer.it.client.messages.create = AsyncMock(return_value=_answer("   "))

        assert await summarizer.it.summarize("ctx-1") is None
        summarizer.contexts.set_summary.assert_not_awaited()

    async def test_an_api_failure_is_swallowed_and_recorded(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.find_by_context = AsyncMock(return_value=_messages(("user", "Bonjour")))
        summarizer.it.client.messages.create = AsyncMock(side_effect=RuntimeError("API down"))

        with patch("app.llm.context_summary.record_llm_usage") as record:
            # Nobody awaits this call in production: raising would surface as a
            # "Task exception was never retrieved" and nothing else.
            assert await summarizer.it.summarize("ctx-1") is None

        summarizer.contexts.set_summary.assert_not_awaited()
        assert record.call_args.kwargs["status"] == "error"

    async def test_without_a_model_nothing_happens(self, summarizer):
        summarizer.it.client = None
        assert await summarizer.it.summarize("ctx-1") is None


class TestMaybeSummarize:
    async def test_below_the_threshold_does_not_call_the_model(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.messages.count_by_context = AsyncMock(return_value=3)

        with patch("app.llm.context_summary.settings") as settings:
            settings.context_summary_every_messages = 10
            assert await summarizer.it.maybe_summarize("ctx-1") is None

        summarizer.it.client.messages.create.assert_not_awaited()

    async def test_at_the_threshold_summarizes(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=_context())
        summarizer.contexts.set_summary = AsyncMock()
        summarizer.messages.count_by_context = AsyncMock(return_value=10)
        summarizer.messages.find_by_context = AsyncMock(return_value=_messages(("user", "Bonjour")))

        with patch("app.llm.context_summary.settings") as settings:
            settings.context_summary_every_messages = 10
            settings.anthropic_fast_model = "claude-haiku-4-5-20251001"
            result = await summarizer.it.maybe_summarize("ctx-1")

        assert result == "L'utilisateur prépare ses courses."
        summarizer.contexts.set_summary.assert_awaited_once()

    async def test_counts_from_the_last_summary(self, summarizer):
        covered_up_to = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)
        summarizer.contexts.get = AsyncMock(return_value=_context(summary="Déjà dit.", summary_updated_at=covered_up_to))
        summarizer.messages.count_by_context = AsyncMock(return_value=0)

        await summarizer.it.maybe_summarize("ctx-1")

        assert summarizer.messages.count_by_context.await_args.kwargs["since"] == covered_up_to

    async def test_unknown_context_does_nothing(self, summarizer):
        summarizer.contexts.get = AsyncMock(return_value=None)
        assert await summarizer.it.maybe_summarize("ctx-gone") is None

    async def test_a_database_failure_is_swallowed(self, summarizer):
        summarizer.contexts.get = AsyncMock(side_effect=RuntimeError("database gone"))
        assert await summarizer.it.maybe_summarize("ctx-1") is None

    async def test_without_a_model_nothing_happens(self, summarizer):
        summarizer.it.client = None
        assert await summarizer.it.maybe_summarize("ctx-1") is None

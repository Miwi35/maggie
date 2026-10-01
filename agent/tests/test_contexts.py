"""The shared thread helpers: they must never cost a caller its answer (MAG-11, MAG-14).

Where each of them is *used* is tested next to its caller — the system prompt in
`test_streaming.py` and `test_proaction.py`, the routing and its ownership check in
`test_streaming.py` and `test_ownership_isolation.py`. What is here is the promise the
module makes to all of them: a thread list the database will not give up degrades the
answer, it does not raise through it. Both are read on paths where raising means a chat
stream that dies mid-sentence, or a reminder the user never receives.
"""

from unittest.mock import AsyncMock, MagicMock, patch

from app.db.context_model import ContextStatus
from app.llm.contexts import active_contexts_section, resolve_context


def _context(label: str, summary: str | None = None, status: ContextStatus = ContextStatus.ACTIVE) -> MagicMock:
    ctx = MagicMock()
    ctx.label = label
    ctx.summary = summary
    ctx.status = status
    return ctx


class TestActiveContextsSection:
    async def test_no_open_thread_is_an_empty_section(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[])

            assert await active_contexts_section("user-1") == ""

    async def test_an_open_thread_brings_its_label_and_its_summary(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Courses", "Deux kilos de farine à acheter.")])

            section = await active_contexts_section("user-1")

        assert "Courses" in section
        assert "Résumé : Deux kilos de farine à acheter." in section

    async def test_a_dormant_thread_is_marked_as_such(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Budget", status=ContextStatus.DORMANT)])

            assert "(dormant)" in await active_contexts_section("user-1")

    async def test_a_database_failure_costs_the_threads_not_the_answer(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(side_effect=RuntimeError("boom"))

            assert await active_contexts_section("user-1") == ""


class TestResolveContext:
    async def test_a_database_failure_leaves_the_caller_without_a_thread(self):
        """And without a model call: the router cannot place a message it cannot list threads for."""
        client = MagicMock()
        client.messages.create = AsyncMock()

        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(side_effect=RuntimeError("boom"))

            assert await resolve_context(client, "Et du beurre", "user-1") is None

        client.messages.create.assert_not_awaited()

    async def test_an_unparseable_answer_leaves_the_caller_without_a_thread(self):
        client = MagicMock()
        response = MagicMock()
        response.content = [MagicMock(text="je ne sais pas trop")]
        response.usage.input_tokens = 10
        response.usage.output_tokens = 5
        client.messages.create = AsyncMock(return_value=response)

        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[])
            repo.create = AsyncMock()

            assert await resolve_context(client, "Et du beurre", "user-1") is None
            repo.create.assert_not_awaited()

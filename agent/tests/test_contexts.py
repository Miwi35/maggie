"""The shared thread helpers: they must never cost a caller its answer (MAG-11, MAG-14).

Where each of them is *used* is tested next to its caller — the system prompt in
`test_streaming.py` and `test_proaction.py`, the routing and its ownership check in
`test_streaming.py` and `test_ownership_isolation.py`. What is here is the promise the
module makes to all of them: a thread list the database will not give up degrades the
answer, it does not raise through it. Both are read on paths where raising means a chat
stream that dies mid-sentence, or a reminder the user never receives.
"""

from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.context_model import ContextStatus
from app.llm.contexts import active_contexts_section, resolve_context, route_message


def _context(
    label: str,
    summary: str | None = None,
    status: ContextStatus = ContextStatus.ACTIVE,
    context_id: str = "ctx-1",
    tool_calls_log: list | None = None,
) -> MagicMock:
    ctx = MagicMock()
    ctx.id = context_id
    ctx.label = label
    ctx.summary = summary
    ctx.status = status
    ctx.tool_calls_log = tool_calls_log
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


class TestTheCurrentThreadIsNamed:
    """The history is made of one thread's messages, so the prompt has to say which (MAG-13)."""

    async def test_it_is_marked_among_the_others(self):
        threads = [_context("Courses", context_id="ctx-courses"), _context("Budget", context_id="ctx-budget")]
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=threads)

            section = await active_contexts_section("user-1", "ctx-budget")

        assert "Budget (active) ← fil en cours" in section
        assert "Courses (active)\n" in section or section.endswith("Courses (active)")

    async def test_no_current_thread_marks_nothing(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Courses")])

            assert "fil en cours" not in await active_contexts_section("user-1")

    async def test_the_current_thread_carries_its_summary_whatever_its_rank(self):
        """Its summary covers the part of the thread the history window no longer reaches."""
        threads = [_context(f"Fil {rank}", f"Résumé {rank}.", context_id=f"ctx-{rank}") for rank in range(7)]
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=threads)

            section = await active_contexts_section("user-1", "ctx-6")

        assert "Résumé 6." in section
        # And the cap still holds for the others: only the first five carry one.
        assert "Résumé 5." not in section

    async def test_the_tool_calls_of_the_current_thread_are_recalled(self):
        """The one trace of what Maggie already did in the thread that survives a turn."""
        log = [
            {"name": "get_grocery_list", "status": "success", "timestamp": "2026-10-01T09:00:00+00:00"},
            {"name": "add_grocery_item", "status": "error", "timestamp": "2026-10-01T09:01:00+00:00"},
        ]
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Courses", tool_calls_log=log)])

            section = await active_contexts_section("user-1", "ctx-1")

        assert "Outils déjà appelés dans ce fil : get_grocery_list (success), add_grocery_item (error)" in section

    async def test_only_the_last_five_are_recalled(self):
        log = [{"name": f"tool_{rank}", "status": "success"} for rank in range(8)]
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Courses", tool_calls_log=log)])

            section = await active_contexts_section("user-1", "ctx-1")

        assert "tool_7 (success)" in section
        assert "tool_2 (success)" not in section

    async def test_a_thread_that_called_nothing_says_nothing(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Courses", tool_calls_log=[])])

            assert "Outils déjà appelés" not in await active_contexts_section("user-1", "ctx-1")

    async def test_a_malformed_log_entry_is_skipped(self):
        """The log is JSONB written by past versions of the code: it is not trusted to be well shaped."""
        log = ["pas un objet", {"status": "success"}, {"name": "get_grocery_list"}]
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[_context("Courses", tool_calls_log=log)])

            section = await active_contexts_section("user-1", "ctx-1")

        assert "Outils déjà appelés dans ce fil : get_grocery_list (inconnu)" in section

    async def test_the_other_threads_keep_their_tool_calls_to_themselves(self):
        log = [{"name": "get_budget", "status": "success"}]
        threads = [_context("Budget", context_id="ctx-budget", tool_calls_log=log)]
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=threads)

            assert "get_budget" not in await active_contexts_section("user-1", "ctx-courses")


class TestRouteMessage:
    """Routing plus the tagging of the message it came from, shared by both chat paths (MAG-13)."""

    @staticmethod
    def _client(answer: str) -> MagicMock:
        response = MagicMock()
        response.content = [MagicMock(text=answer)]
        response.usage.input_tokens = 30
        response.usage.output_tokens = 10
        client = MagicMock()
        client.messages.create = AsyncMock(return_value=response)
        return client

    async def test_it_writes_the_resolved_thread_on_the_message(self):
        created = MagicMock()
        created.id = "ctx-new"
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.contexts.message_repo") as messages,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            contexts.create = AsyncMock(return_value=created)
            messages.update_context = AsyncMock()

            resolution = await route_message(
                self._client('{"context_id": null, "label": "Budget"}'),
                "Où en est mon budget ?",
                "user-1",
                message_id="msg-1",
            )

        assert resolution["id"] == "ctx-new"
        messages.update_context.assert_awaited_once_with("msg-1", "ctx-new")

    async def test_a_tag_that_could_not_be_written_still_gives_the_thread(self):
        """The turn about to run carries the thread; losing the tag costs the summary one message."""
        created = MagicMock()
        created.id = "ctx-new"
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.contexts.message_repo") as messages,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            contexts.create = AsyncMock(return_value=created)
            messages.update_context = AsyncMock(side_effect=RuntimeError("no database"))

            resolution = await route_message(
                self._client('{"context_id": null, "label": "Budget"}'),
                "Où en est mon budget ?",
                "user-1",
                message_id="msg-1",
            )

        assert resolution["id"] == "ctx-new"

    async def test_nothing_is_tagged_when_the_routing_failed(self):
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.contexts.message_repo") as messages,
        ):
            contexts.find_active = AsyncMock(side_effect=RuntimeError("no database"))
            messages.update_context = AsyncMock()

            assert await route_message(self._client("{}"), "Bonjour", "user-1", message_id="msg-1") is None

        messages.update_context.assert_not_called()

    async def test_a_caller_with_no_stored_message_just_routes(self):
        """The A2A path stores nothing, and a proaction routes what Maggie is about to send (MAG-14)."""
        created = MagicMock()
        created.id = "ctx-new"
        with (
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.contexts.message_repo") as messages,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            contexts.create = AsyncMock(return_value=created)
            messages.update_context = AsyncMock()

            resolution = await route_message(
                self._client('{"context_id": null, "label": "Budget"}'), "Où en est mon budget ?", "user-1"
            )

        assert resolution["id"] == "ctx-new"
        messages.update_context.assert_not_called()


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


class TestResolveContextKeepsThreadsAlive:
    """A message that lands in a thread is what keeps it from going dormant (MAG-12)."""

    @staticmethod
    def _client(context_id: str | None) -> MagicMock:
        client = MagicMock()
        response = MagicMock()
        answer = f'{{"context_id": "{context_id}"}}' if context_id else '{"context_id": null, "label": "Neuf"}'
        response.content = [MagicMock(text=answer)]
        response.usage.input_tokens = 10
        response.usage.output_tokens = 5
        client.messages.create = AsyncMock(return_value=response)
        return client

    @staticmethod
    def _existing(status: ContextStatus) -> MagicMock:
        ctx = MagicMock()
        ctx.id = "ctx-1"
        ctx.label = "Budget"
        ctx.summary = "Le budget de septembre."
        ctx.status = status
        ctx.updated_at = datetime.now(UTC) - timedelta(days=3)
        return ctx

    async def test_a_message_on_a_dormant_thread_wakes_it(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[self._existing(ContextStatus.DORMANT)])
            repo.touch = AsyncMock()

            result = await resolve_context(self._client("ctx-1"), "Où en est mon budget ?", "user-1")

        repo.touch.assert_awaited_once_with("ctx-1")
        assert result["action"] == "matched"
        assert result["status"] == "active"
        assert result["summary"] == "Le budget de septembre."

    async def test_a_message_on_an_active_thread_counts_as_activity(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[self._existing(ContextStatus.ACTIVE)])
            repo.touch = AsyncMock()

            await resolve_context(self._client("ctx-1"), "Et en octobre ?", "user-1")

        repo.touch.assert_awaited_once_with("ctx-1")

    async def test_a_new_thread_is_not_touched(self):
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[])
            repo.create = AsyncMock(return_value=MagicMock(id="ctx-2"))
            repo.touch = AsyncMock()

            await resolve_context(self._client(None), "Un nouveau sujet", "user-1")

        repo.touch.assert_not_awaited()

    async def test_a_touch_that_fails_keeps_the_routing(self):
        """The thread is resolved either way: a failed write costs the idle clock, not the answer."""
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[self._existing(ContextStatus.DORMANT)])
            repo.touch = AsyncMock(side_effect=RuntimeError("db down"))

            result = await resolve_context(self._client("ctx-1"), "Où en est mon budget ?", "user-1")

        assert result["action"] == "matched"
        assert result["id"] == "ctx-1"

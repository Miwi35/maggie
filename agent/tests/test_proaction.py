from dataclasses import dataclass
from datetime import UTC
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.context_model import ContextStatus
from app.db.proaction_model import ProactionStatus
from app.llm.gateway import LLMGateway


class TestProactionModel:
    def test_proaction_status_values(self):
        """ProactionStatus enum should have the expected values."""
        assert ProactionStatus.PENDING == "pending"
        assert ProactionStatus.RUNNING == "running"
        assert ProactionStatus.COMPLETED == "completed"
        assert ProactionStatus.FAILED == "failed"

    def test_proaction_to_dict(self):
        """Proaction.to_dict() should serialize correctly."""
        from datetime import datetime

        from app.db.proaction_model import Proaction

        proaction = Proaction(
            id="abc123",
            user_id="user-1",
            prompt="Check calendar",
            status=ProactionStatus.PENDING,
            scheduled_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
            created_at=datetime(2026, 2, 19, 10, 0, tzinfo=UTC),
        )

        d = proaction.to_dict()
        assert d["id"] == "abc123"
        assert d["userId"] == "user-1"
        assert d["prompt"] == "Check calendar"
        assert d["status"] == "pending"
        assert d["scheduledAt"] == "2026-02-20T09:00:00+00:00"
        assert d["response"] is None
        assert d["error"] is None
        assert d["completedAt"] is None


class TestProactionRouteAuth:
    def test_proactions_endpoint_requires_auth(self, client):
        """GET /proactions without auth returns 401 or 403."""
        response = client.get("/proactions")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.proaction_repo")
    def test_proactions_endpoint_with_auth(self, mock_repo, authed_client):
        """GET /proactions with valid auth returns proactions list."""
        mock_proaction = MagicMock()
        mock_proaction.to_dict.return_value = {
            "id": "abc123",
            "userId": "test-user",
            "prompt": "Check calendar",
            "status": "pending",
        }
        mock_repo.find_by_user = AsyncMock(return_value=[mock_proaction])

        response = authed_client.get("/proactions")

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["id"] == "abc123"


class TestProactionRoute:
    """`POST /proaction` — the manual trigger, and the other sink of a proaction.

    It owes the conversation exactly what `proaction_consumer` owes it: the message
    stored in the thread the gateway routed it to, and that thread re-summarized, so
    a reply to the reminder is routed against something that mentions it (MAG-14).
    """

    def test_it_requires_auth(self, client):
        assert client.post("/proaction", json={"message": "Rappelle-lui le plombier"}).status_code in (401, 403)

    @staticmethod
    def _fire(authed_client, result: dict):
        stored = MagicMock()
        stored.to_dict.return_value = {"id": "msg-1", "role": "assistant", "contextId": result.get("context_id")}
        with (
            patch("app.api.routes.llm_gateway") as gateway,
            patch("app.api.routes.message_repo") as messages,
            patch("app.api.routes.context_summarizer") as summarizer,
        ):
            gateway.proaction = AsyncMock(return_value=result)
            messages.create = AsyncMock(return_value=stored)
            summarizer.maybe_summarize = AsyncMock(return_value=None)

            response = authed_client.post("/proaction", json={"message": "Rappelle-lui le plombier"})
            return response, messages, summarizer

    def test_it_stores_the_message_in_the_thread_the_gateway_routed_it_to(self, authed_client):
        response, messages, summarizer = self._fire(
            authed_client, {"response": "Pense au plombier.", "tool_calls": [], "context_id": "ctx-1"}
        )

        assert response.status_code == 200
        assert response.json()["response"] == "Pense au plombier."
        messages.create.assert_awaited_once_with(
            user_id="test-user", role="assistant", content="Pense au plombier.", context_id="ctx-1", blocks=None
        )
        summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")
        # That the stored message reaches the caller at all — what it carries is the
        # repository's business, and the journey reads `contextId` off the live endpoint.
        assert response.json()["messages"] != []

    def test_a_proaction_with_no_thread_is_still_delivered(self, authed_client):
        """A routing call that failed must cost the thread, not the message."""
        response, messages, summarizer = self._fire(
            authed_client, {"response": "Pense au plombier.", "tool_calls": []}
        )

        assert response.status_code == 200
        messages.create.assert_awaited_once_with(
            user_id="test-user", role="assistant", content="Pense au plombier.", context_id=None, blocks=None
        )
        summarizer.maybe_summarize.assert_not_awaited()


def _gateway() -> LLMGateway:
    """An `LLMGateway` with everything but the piece under test stubbed out."""
    gateway = LLMGateway()
    gateway.client = MagicMock()
    gateway.personality = MagicMock()
    gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gateway.agent_memory = MagicMock()
    gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
    gateway.tool_router = MagicMock()
    gateway.tool_router.get_tool_definitions = AsyncMock(return_value=[])
    return gateway


class TestProactionHistory:
    """A proaction used to run with no idea what the conversation was about (MAG-14)."""

    @staticmethod
    async def _volatile_block(contexts: list) -> str:
        with (
            patch("app.llm.contexts.context_repo") as repo,
            patch("app.llm.gateway.skill_index") as skills,
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
        ):
            repo.find_active = AsyncMock(return_value=contexts)
            skills.get_skills_index.return_value = ""
            blocks = await _gateway()._build_system_prompt("user-1", preamble="\n\nTu es en mode proaction.")
        return blocks[1]["text"]

    @staticmethod
    def _context(label: str, summary: str | None = None) -> MagicMock:
        ctx = MagicMock()
        ctx.label = label
        ctx.summary = summary
        ctx.status = ContextStatus.ACTIVE
        return ctx

    async def test_the_open_threads_reach_the_proaction(self):
        """Without this, a reminder cannot refer to what the user already decided."""
        volatile = await self._volatile_block(
            [self._context("Courses de la semaine", "L'utilisateur doit acheter deux kilos de farine.")]
        )

        assert "Courses de la semaine" in volatile
        assert "Résumé : L'utilisateur doit acheter deux kilos de farine." in volatile

    async def test_they_stay_out_of_the_cached_prefix(self):
        """A summary changes while the personality does not — caching it would waste the prefix."""
        with (
            patch("app.llm.contexts.context_repo") as repo,
            patch("app.llm.gateway.skill_index") as skills,
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
        ):
            repo.find_active = AsyncMock(return_value=[self._context("Courses", "Deux kilos de farine.")])
            skills.get_skills_index.return_value = ""
            blocks = await _gateway()._build_system_prompt("user-1")

        assert blocks[0]["cache_control"] == {"type": "ephemeral"}
        assert "Deux kilos de farine." not in blocks[0]["text"]

    async def test_no_open_thread_leaves_the_prompt_as_it_was(self):
        volatile = await self._volatile_block([])

        assert "Contextes de conversation en cours" not in volatile
        assert "Tu es en mode proaction." in volatile


class TestProactionContextAttachment:
    """The thread the proaction's message lands in, so a reply to it stays there (MAG-14)."""

    @staticmethod
    def _run(answer: str = "C'est l'heure d'appeler le plombier !"):
        return patch(
            "app.llm.gateway.run_tool_loop",
            AsyncMock(return_value={"response": answer, "tool_calls": []}),
        )

    async def test_the_message_is_routed_to_a_thread(self):
        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])
        resolve = AsyncMock(return_value={"action": "matched", "id": "ctx-1", "label": "Plomberie"})

        with self._run(), patch("app.llm.gateway.resolve_context", resolve):
            result = await gateway.proaction("Rappelle-lui d'appeler le plombier", "user-1")

        assert result["context_id"] == "ctx-1"
        # Routed on the message Maggie is about to send, not on the internal prompt: it
        # is what lands in the thread, and what the user will answer.
        assert resolve.await_args.args[1] == "C'est l'heure d'appeler le plombier !"
        assert resolve.await_args.args[2] == "user-1"

    async def test_a_new_thread_is_opened_when_none_matches(self):
        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])
        resolve = AsyncMock(return_value={"action": "created", "id": "ctx-new", "label": "Plomberie"})

        with self._run(), patch("app.llm.gateway.resolve_context", resolve):
            result = await gateway.proaction("Rappelle-lui d'appeler le plombier", "user-1")

        assert result["context_id"] == "ctx-new"

    async def test_a_planning_run_is_attached_to_nothing(self):
        """Its output is an internal log: a thread for it is one the user never sees."""
        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])
        resolve = AsyncMock()

        with self._run("3 proactions planifiées."), patch("app.llm.gateway.resolve_context", resolve):
            result = await gateway.proaction("Planifie la journée", "user-1", silent=True)

        assert result.get("context_id") is None
        resolve.assert_not_awaited()

    async def test_an_empty_answer_is_attached_to_nothing(self):
        """Nothing is stored in the chat, so there is no message to place — and no model call to pay for."""
        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])
        resolve = AsyncMock()

        with self._run("   "), patch("app.llm.gateway.resolve_context", resolve):
            result = await gateway.proaction("Rappelle-lui d'appeler le plombier", "user-1")

        assert result["context_id"] is None
        resolve.assert_not_awaited()

    async def test_a_run_that_gave_up_is_attached_to_nothing(self):
        """`run_tool_loop`'s apology is not a message: a thread labelled from it is noise."""
        from app.llm.runner import ITERATION_LIMIT_MESSAGE

        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])
        resolve = AsyncMock()

        with self._run(ITERATION_LIMIT_MESSAGE), patch("app.llm.gateway.resolve_context", resolve):
            result = await gateway.proaction("Rappelle-lui d'appeler le plombier", "user-1")

        assert result["context_id"] is None
        resolve.assert_not_awaited()

    async def test_a_routing_failure_still_delivers_the_message(self):
        """The reminder matters more than the thread it belongs to."""
        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])

        with self._run(), patch("app.llm.gateway.resolve_context", AsyncMock(return_value=None)):
            result = await gateway.proaction("Rappelle-lui d'appeler le plombier", "user-1")

        assert result["context_id"] is None
        assert result["response"] == "C'est l'heure d'appeler le plombier !"


@dataclass
class Consumed:
    """What one trip through the consumer's callback did, as the assertions read it."""

    gateway: MagicMock
    proactions: MagicMock
    messages: MagicMock
    summarizer: MagicMock


async def _consume(result: dict, *, proaction_id: str = "pro-1", prompt: str = "Rappelle-lui d'appeler le plombier"):
    """Run the queue callback once over a proaction whose gateway returns `result`."""
    from app.queue.proaction_consumer import start_consumer

    proaction = MagicMock()
    proaction.id = proaction_id
    proaction.user_id = "user-1"
    proaction.prompt = prompt

    gateway_instance = MagicMock()
    gateway_instance.proaction = AsyncMock(return_value=result)

    message = MagicMock()
    message.body = proaction_id.encode()
    message.process = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(), __aexit__=AsyncMock()))

    # `start_consumer` hands its callback to `queue.consume`, which is the only way
    # to get hold of it: capture it there, then call it as RabbitMQ would.
    captured = {}

    async def capture_consume(callback):
        captured["callback"] = callback

    channel = MagicMock()
    queue = MagicMock()
    queue.consume = capture_consume
    channel.declare_queue = AsyncMock(return_value=queue)

    with (
        patch("app.queue.proaction_consumer.message_repo") as messages,
        patch("app.queue.proaction_consumer.proaction_repo") as proactions,
        patch("app.queue.proaction_consumer.context_summarizer") as summarizer,
        patch("app.queue.proaction_consumer.LLMGateway", return_value=gateway_instance),
        patch("app.queue.proaction_consumer.get_channel", return_value=channel),
    ):
        proactions.get = AsyncMock(return_value=proaction)
        proactions.mark_running = AsyncMock()
        proactions.mark_completed = AsyncMock()
        proactions.mark_failed = AsyncMock()
        messages.create = AsyncMock()
        summarizer.maybe_summarize = AsyncMock(return_value=None)

        await start_consumer()
        assert "callback" in captured
        await captured["callback"](message)

        return Consumed(gateway=gateway_instance, proactions=proactions, messages=messages, summarizer=summarizer)


class TestProactionConsumer:
    """Tests for proaction_consumer message delivery."""

    async def test_consumer_creates_chat_message(self):
        """Consumer should create a chat message from the proaction response."""
        consumed = await _consume({"response": "C'est l'heure d'appeler le plombier !", "tool_calls": []})

        # Verify gateway.proaction called without silent (defaults to False)
        consumed.gateway.proaction.assert_called_once_with("Rappelle-lui d'appeler le plombier", "user-1")

        consumed.messages.create.assert_called_once_with(
            user_id="user-1",
            role="assistant",
            content="C'est l'heure d'appeler le plombier !",
            context_id=None,
            blocks=None,
        )

    async def test_consumer_skips_empty_response(self):
        """Consumer should NOT create a chat message when response is empty."""
        consumed = await _consume({"response": "", "tool_calls": []})

        consumed.messages.create.assert_not_called()

    async def test_the_message_is_stored_in_the_thread_the_gateway_routed_it_to(self):
        """Stored without one, the message is an orphan: the reply to it opens a thread of its own (MAG-14)."""
        consumed = await _consume(
            {"response": "C'est l'heure d'appeler le plombier !", "tool_calls": [], "context_id": "ctx-1"}
        )

        consumed.messages.create.assert_called_once_with(
            user_id="user-1",
            role="assistant",
            content="C'est l'heure d'appeler le plombier !",
            context_id="ctx-1",
            blocks=None,
        )

    async def test_the_thread_is_summarized_so_the_reply_has_something_to_land_on(self):
        consumed = await _consume({"response": "Pense au plombier.", "tool_calls": [], "context_id": "ctx-1"})

        consumed.summarizer.maybe_summarize.assert_awaited_once_with("ctx-1")

    async def test_a_message_with_no_thread_summarizes_nothing(self):
        consumed = await _consume({"response": "Pense au plombier.", "tool_calls": []})

        consumed.summarizer.maybe_summarize.assert_not_awaited()

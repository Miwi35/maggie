import json
from dataclasses import dataclass
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import respx

from app.db.context_model import ContextStatus
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.gateway import LLMGateway
from app.llm.tools import ToolRouter
from app.mcp.client import McpClient
from app.proactions.api import Proaction, ProactionApiUnreachable
from app.queue.proaction_consumer import execute_proaction

API_PROACTION = {
    "id": "01JPROACTION0000000000000A",
    "prompt": "Check calendar",
    "summary": "Check calendar",
    "scheduledAt": "2037-02-20T09:00:00+01:00",
    "status": "pending",
    "response": None,
    "error": None,
    "completedAt": None,
    "userId": "user-1",
}


class TestProactionFromTheApi:
    def test_it_reads_what_the_api_describes_and_keeps_the_shape_the_clients_read(self):
        """`GET /agent/proactions` and the Mercure topic keep their fields: the screens move in MAG-328."""
        d = Proaction.from_api(API_PROACTION).to_dict()

        assert d["id"] == "01JPROACTION0000000000000A"
        assert d["userId"] == "user-1"
        assert d["prompt"] == "Check calendar"
        assert d["status"] == "pending"
        assert d["scheduledAt"] == "2037-02-20T09:00:00+01:00"
        assert d["response"] is None
        assert d["error"] is None
        assert d["completedAt"] is None

    def test_a_listed_one_takes_the_user_it_was_listed_for(self):
        assert Proaction.from_api(API_PROACTION | {"userId": None}, "user-2").user_id == "user-2"


class TestProactionRouteAuth:
    def test_proactions_endpoint_requires_auth(self, client):
        """GET /proactions without auth returns 401 or 403."""
        response = client.get("/proactions")
        assert response.status_code in (401, 403)

    def test_proactions_are_read_from_the_api_latest_first(self, authed_client):
        later = Proaction.from_api(API_PROACTION | {"id": "later", "scheduledAt": "2037-02-21T09:00:00+01:00"})
        with patch("app.api.routes.proactions_api.list_for_user", AsyncMock(return_value=[Proaction.from_api(API_PROACTION), later])) as listed:
            response = authed_client.get("/proactions")

        assert response.status_code == 200
        assert [p["id"] for p in response.json()] == ["later", "01JPROACTION0000000000000A"]
        listed.assert_awaited_once_with("test-user")

    def test_an_unreachable_api_is_a_502_not_an_empty_list(self, authed_client):
        with patch("app.api.routes.proactions_api.list_for_user", AsyncMock(side_effect=ProactionApiUnreachable("down"))):
            response = authed_client.get("/proactions")

        assert response.status_code == 502


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
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
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
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
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
    publisher: MagicMock


async def _consume(
    result: dict | Exception, *, proaction_id: str = "pro-1", prompt: str = "Rappelle-lui d'appeler le plombier", body: bytes | None = None
):
    """Run the queue callback once over a claimed proaction whose gateway returns `result`."""
    from app.queue.proaction_consumer import start_consumer

    gateway_instance = MagicMock()
    if isinstance(result, Exception):
        gateway_instance.proaction = AsyncMock(side_effect=result)
    else:
        gateway_instance.proaction = AsyncMock(return_value=result)

    claimed = API_PROACTION | {"id": proaction_id, "prompt": prompt, "status": "running"}
    message = MagicMock()
    message.body = body if body is not None else json.dumps(claimed).encode()
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

    async def record(proaction, status, *, response=None, error=None):
        return Proaction.from_api(claimed | {"status": status, "response": response, "error": error})

    with (
        patch("app.queue.proaction_consumer.message_repo") as messages,
        patch("app.queue.proaction_consumer.proactions_api") as proactions,
        patch("app.queue.proaction_consumer.publisher") as publisher,
        patch("app.queue.proaction_consumer.context_summarizer") as summarizer,
        patch("app.queue.proaction_consumer.LLMGateway", return_value=gateway_instance),
        patch("app.queue.proaction_consumer.get_channel", return_value=channel),
    ):
        proactions.record = AsyncMock(side_effect=record)
        publisher.publish = AsyncMock()
        messages.create = AsyncMock()
        summarizer.maybe_summarize = AsyncMock(return_value=None)

        await start_consumer()
        assert "callback" in captured
        await captured["callback"](message)

        return Consumed(
            gateway=gateway_instance, proactions=proactions, messages=messages, summarizer=summarizer, publisher=publisher
        )


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

    async def test_the_run_is_recorded_completed_on_the_api_and_told_to_the_screens(self):
        """The API holds the status since MAG-327; the admin interrupts on the completed one it hears of."""
        consumed = await _consume({"response": "Pense au plombier.", "tool_calls": []})

        proaction, status = consumed.proactions.record.await_args.args
        assert (proaction.id, status) == ("pro-1", "completed")
        assert consumed.proactions.record.await_args.kwargs == {"response": "Pense au plombier.", "error": None}
        topic, data = consumed.publisher.publish.await_args.args
        assert topic.endswith("proactions/user-1")
        assert (data["id"], data["status"], data["response"]) == ("pro-1", "completed", "Pense au plombier.")

    async def test_a_summary_that_fails_does_not_turn_a_delivered_message_into_a_failure(self):
        from app.queue.proaction_consumer import execute_proaction

        proaction = Proaction.from_api(API_PROACTION | {"status": "running"})
        gateway = MagicMock()
        gateway.proaction = AsyncMock(return_value={"response": "Pense au plombier.", "tool_calls": [], "context_id": "ctx-1"})
        with (
            patch("app.queue.proaction_consumer.message_repo") as messages,
            patch("app.queue.proaction_consumer.proactions_api") as proactions,
            patch("app.queue.proaction_consumer.publisher") as publisher,
            patch("app.queue.proaction_consumer.context_summarizer") as summarizer,
        ):
            messages.create = AsyncMock()
            proactions.record = AsyncMock(return_value=proaction)
            publisher.publish = AsyncMock()
            summarizer.maybe_summarize = AsyncMock(side_effect=RuntimeError("model down"))

            result = await execute_proaction(gateway, proaction)

        assert result["status"] == "completed"
        assert proactions.record.await_args.args[1] == "completed"

    async def test_a_run_that_fails_is_recorded_failed_with_its_error(self):
        consumed = await _consume(RuntimeError("LLM timeout"))

        consumed.messages.create.assert_not_awaited()
        _, status = consumed.proactions.record.await_args.args
        assert status == "failed"
        assert consumed.proactions.record.await_args.kwargs["error"] == "LLM timeout"

    async def test_a_message_left_from_before_the_api_is_dropped(self):
        """A bare id of the old table, queued across the deploy: its proaction was moved to the API still pending."""
        consumed = await _consume({"response": "x", "tool_calls": []}, body=b"0123456789abcdef0123456789abcdef")

        consumed.gateway.proaction.assert_not_awaited()
        consumed.proactions.record.assert_not_awaited()


class TestADueReminderIsDelivered:
    """7 Oct., 19:39 and 19:43: « Rappelle monsieur d'appeler sa mère » came due, and Maggie
    answered « À quel moment souhaitez-vous que je vous le rappelle ? ». She read the stored
    prompt as a new request to schedule, so a reminder that comes due must say so."""

    @staticmethod
    async def _sent(prompt: str, *, silent: bool = False):
        gateway = _gateway()
        gateway._build_system_prompt = AsyncMock(return_value=[])
        loop = AsyncMock(return_value={"response": "C'est l'heure.", "tool_calls": []})
        resolve = AsyncMock(return_value={"action": "matched", "id": "ctx-1", "label": "Rappels"})
        with patch("app.llm.gateway.run_tool_loop", loop), patch("app.llm.gateway.resolve_context", resolve):
            await gateway.proaction(prompt, "user-1", silent=silent)
        return gateway, loop.await_args.args[1][0]["content"]

    async def test_the_prompt_is_handed_over_as_due_now(self):
        _, content = await self._sent("Rappelle monsieur d'appeler sa mère.")

        assert "Rappelle monsieur d'appeler sa mère." in content
        assert content != "Rappelle monsieur d'appeler sa mère."
        assert "maintenant" in content

    async def test_the_preamble_says_to_deliver_it_not_to_schedule_it(self):
        gateway, _ = await self._sent("Rappelle monsieur d'appeler sa mère.")

        preamble = gateway._build_system_prompt.await_args.kwargs["preamble"]
        assert "maintenant" in preamble
        assert "ne programme pas" in preamble.lower()

    async def test_a_planning_run_keeps_its_prompt(self):
        _, content = await self._sent("Planifie la journée", silent=True)

        assert content == "Planifie la journée"


TREASURY_ALERT_PROMPT = (
    "Alerte trésorerie du 2026-10-31. Le salaire attendu le 2026-10-25 n'est pas arrivé : 6 jour(s) de retard.\n"
    "Le compte courant passerait à découvert le 2026-11-06 (solde le plus bas : -100,00 €, limite de découvert autorisée : 0,00 €).\n"
    "Avance conseillée : 100,00 €, depuis le compte « Livret A » (source : get_treasury_outlook, solde du compte 6 000,00 €).\n"
    "Tu ne fais que proposer : n'exécute aucun virement."
)
TREASURY_OUTLOOK = {
    "salary": {"isConfigured": True, "isLate": True, "delayDays": 6},
    "overdraft": {"isForecast": True, "onDate": "2026-11-06", "worstBalanceCents": -10000, "limitCents": 0},
    "advice": {"amountCents": 10000, "sourceAccountName": "Livret A", "sourceAmountCents": 10000},
}
MCP_URL = "http://nginx/_mcp"


class TestTreasuryAlertProaction:
    """The proaction `app:finance:treasury-alerts` files (MAG-281), run through the real tool loop.

    Only the model (the fake LLM's `92-treasury-alert.yaml`) and the HTTP to the API (respx) are
    simulated: the gateway, the tool router, the policy and the MCP client are the production ones.
    """

    @staticmethod
    def _gateway() -> LLMGateway:
        gateway = _gateway()
        gateway.client = FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)
        gateway.tool_router = ToolRouter()
        gateway.tool_router.get_tool_definitions = AsyncMock(
            return_value=[
                {
                    "name": "get_treasury_outlook",
                    "description": "Read the treasury until the salary comes.",
                    "input_schema": {"type": "object", "properties": {}},
                }
            ]
        )
        gateway._build_system_prompt = AsyncMock(return_value=[{"type": "text", "text": "Tu es Maggie."}])
        return gateway

    @staticmethod
    def _proaction(status: str = "running") -> Proaction:
        return Proaction.from_api(
            API_PROACTION | {"id": "pro-1", "userId": "01JTREASURYUSER000000000A", "prompt": TREASURY_ALERT_PROMPT, "status": status}
        )

    @staticmethod
    def _outlook_reply(outlook: dict) -> httpx.Response:
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": json.dumps(outlook)}]}},
        )

    @staticmethod
    async def _run(gateway: LLMGateway, proaction: Proaction, *, dry_run: bool = False):
        mcp = McpClient()
        mcp.server_url = MCP_URL
        mcp._http_client = httpx.AsyncClient()
        mcp._session_id = "session-1"
        try:
            with (
                patch("app.llm.tools.mcp_client", mcp),
                patch("app.llm.gateway.resolve_context", AsyncMock(return_value={"action": "matched", "id": "ctx-1"})),
                patch("app.queue.proaction_consumer.proactions_api") as repo,
                patch("app.queue.proaction_consumer.publisher") as publisher,
                patch("app.queue.proaction_consumer.message_repo") as messages,
                patch("app.queue.proaction_consumer.context_summarizer") as summarizer,
            ):
                repo.record = AsyncMock(return_value=proaction)
                publisher.publish = AsyncMock()
                messages.create = AsyncMock()
                summarizer.maybe_summarize = AsyncMock(return_value=None)
                result = await execute_proaction(gateway, proaction, dry_run=dry_run)
                return result, repo, messages
        finally:
            await mcp._http_client.aclose()

    @respx.mock
    async def test_maggie_reads_the_outlook_for_the_user_and_proposes_without_claiming_a_transfer(self):
        route = respx.post(MCP_URL).mock(return_value=self._outlook_reply(TREASURY_OUTLOOK))
        proaction = self._proaction()

        result, repo, messages = await self._run(self._gateway(), proaction)

        assert result["status"] == "completed"
        sent = json.loads(route.calls.last.request.content)
        assert sent["params"]["name"] == "get_treasury_outlook"
        assert route.calls.last.request.headers["X-Maggie-User-Id"] == proaction.user_id
        assert json.loads(result["toolCalls"][0]["result"])["overdraft"]["onDate"] == "2026-11-06"
        repo.record.assert_awaited_once()
        assert repo.record.await_args.args[1] == "completed"
        messages.create.assert_awaited_once()
        stored = messages.create.await_args.kwargs
        assert stored["user_id"] == proaction.user_id
        assert stored["role"] == "assistant"
        assert stored["context_id"] == "ctx-1"
        assert "Je vous propose une avance" in stored["content"]
        assert "rendre" in stored["content"]
        for claim in ("c'est fait", "j'ai viré", "virement effectué", "ai effectué"):
            assert claim not in stored["content"].lower()

    @respx.mock
    async def test_a_dry_run_shows_the_message_without_storing_or_taking_the_proaction(self):
        respx.post(MCP_URL).mock(return_value=self._outlook_reply(TREASURY_OUTLOOK))

        result, repo, messages = await self._run(self._gateway(), self._proaction("pending"), dry_run=True)

        assert "Je vous propose une avance" in result["message"]
        assert result["status"] == "pending"
        repo.record.assert_not_awaited()
        messages.create.assert_not_awaited()

    @respx.mock
    async def test_an_api_that_does_not_answer_is_handed_to_the_model_as_an_error_not_as_figures(self):
        respx.post(MCP_URL).mock(return_value=httpx.Response(500, text="Internal Server Error"))

        result, repo, _ = await self._run(self._gateway(), self._proaction())

        assert result["status"] == "completed"
        outcome = json.loads(result["toolCalls"][0]["result"])
        assert "error" in outcome
        assert "overdraft" not in outcome
        assert repo.record.await_args.args[1] == "completed"

    @respx.mock
    async def test_a_model_that_fails_marks_the_proaction_failed_and_stores_nothing(self):
        respx.post(MCP_URL).mock(return_value=self._outlook_reply(TREASURY_OUTLOOK))
        gateway = self._gateway()
        gateway.client = MagicMock()
        gateway.client.messages.create = AsyncMock(side_effect=RuntimeError("model down"))

        result, repo, messages = await self._run(gateway, self._proaction())

        assert result["status"] == "failed"
        assert result["error"] == "model down"
        assert repo.record.await_args.args[1] == "failed"
        assert repo.record.await_args.kwargs["error"] == "model down"
        messages.create.assert_not_awaited()

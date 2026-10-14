"""The model's provider down is not Maggie down (MAG-448).

13-14 Oct.: the Anthropic credit ran out at 23:40. Every turn answered « Désolé, une erreur
est survenue », the production smoke test read that as a broken release, froze production
and stopped the merge train all night — while neither the code nor the deployment was at
fault. `/chat` now says when the provider is the one failing, so the smoke test can warn
instead of rolling back, and the owner is told by a push, once a day at most.
"""

import json
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import anthropic
import httpx
import pytest

from app.db.models import Message
from app.llm.gateway import API_ERROR_MESSAGE, LLMGateway
from app.llm.provider_status import PROVIDER_UNAVAILABLE, alert_owner, provider_reason

CREDIT_MESSAGE = (
    "Your credit balance is too low to access the Anthropic API. "
    "Please go to Plans & Billing to upgrade or purchase credits."
)
REQUEST = httpx.Request("POST", "https://api.anthropic.com/v1/messages")


def _status_error(status: int, message: str, error_type: str = "invalid_request_error") -> anthropic.APIStatusError:
    body = {"type": "error", "error": {"type": error_type, "message": message}}
    response = httpx.Response(status, request=REQUEST, json=body)
    return anthropic.APIStatusError(message, response=response, body=body)


def _credit_error() -> anthropic.BadRequestError:
    body = {"type": "error", "error": {"type": "invalid_request_error", "message": CREDIT_MESSAGE}}
    response = httpx.Response(400, request=REQUEST, json=body)
    return anthropic.BadRequestError(f"Error code: 400 - {body}", response=response, body=body)


def _gateway_with_failing_client(error: Exception) -> LLMGateway:
    """The real gateway and tool loop, on an Anthropic client that raises `error`."""
    gateway = LLMGateway()
    gateway.client = MagicMock()
    gateway.client.messages.create = AsyncMock(side_effect=error)
    gateway.personality = MagicMock()
    gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gateway.agent_memory = MagicMock()
    gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
    gateway.tool_router = MagicMock()
    gateway.tool_router.get_tool_definitions = AsyncMock(return_value=[])
    return gateway


@pytest.fixture
def collaborators():
    """Everything around the turn but the model call: no database, no MCP, no Mercure."""
    stored = Message(id="msg-1", user_id="test-user", role="user", content="Bonjour")
    alert = AsyncMock()
    with (
        patch("app.api.routes.message_repo", MagicMock(create=AsyncMock(return_value=stored))),
        patch("app.api.routes.context_summarizer", MagicMock(maybe_summarize=AsyncMock())),
        patch("app.api.routes.find_settled_answer", AsyncMock(return_value=None)),
        patch("app.llm.gateway.route_message", AsyncMock(return_value=None)),
        patch("app.llm.gateway.build_history", AsyncMock(return_value=[{"role": "user", "content": "Bonjour"}])),
        patch(
            "app.llm.gateway.skill_index",
            MagicMock(
                get_skills_index=MagicMock(return_value=""),
                skills_for_moment=MagicMock(return_value=""),
                refresh=AsyncMock(),
            ),
        ),
        patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
        patch("app.llm.gateway.active_contexts_section", AsyncMock(return_value="")),
        patch("app.llm.gateway.last_exchange_section", AsyncMock(return_value="")),
        patch("app.llm.gateway.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
        patch("app.llm.gateway.alert_owner", alert),
    ):
        yield alert


class TestChatSaysTheProviderIsDown:
    def test_an_exhausted_credit_is_reported_as_provider_unavailable(self, collaborators, authed_client):
        """The reproduction of the 13-14 Oct. incident: the 400 Anthropic answers on an empty credit."""
        with patch("app.api.routes.llm_gateway", _gateway_with_failing_client(_credit_error())):
            response = authed_client.post("/chat", json={"message": "Bonjour"})

        assert response.status_code == 200
        body = response.json()
        assert body["error"] == PROVIDER_UNAVAILABLE
        assert body["provider_reason"] == "credit"
        # The user still reads a French sentence, not the API's wording.
        assert body["response"] == API_ERROR_MESSAGE
        collaborators.assert_awaited_once_with("test-user", "credit")

    def test_an_error_of_ours_keeps_the_plain_error(self, collaborators, authed_client):
        """A 400 about our own request is our bug: the smoke test must keep failing on it."""
        error = _status_error(400, "messages: roles must alternate")
        with patch("app.api.routes.llm_gateway", _gateway_with_failing_client(error)):
            body = authed_client.post("/chat", json={"message": "Bonjour"}).json()

        assert body["error"] is True
        assert body.get("provider_reason") is None
        collaborators.assert_not_awaited()

    def test_a_healthy_answer_carries_no_error(self, authed_client):
        stored = Message(id="msg-1", user_id="test-user", role="user", content="Bonjour")
        with (
            patch("app.api.routes.message_repo", MagicMock(create=AsyncMock(return_value=stored))),
            patch("app.api.routes.find_settled_answer", AsyncMock(return_value=None)),
            patch(
                "app.api.routes.llm_gateway",
                MagicMock(chat=AsyncMock(return_value={"response": "Bonjour !", "tool_calls": []})),
            ),
        ):
            body = authed_client.post("/chat", json={"message": "Salut"}).json()

        assert body["error"] is None
        assert body["provider_reason"] is None


class TestWhichErrorsAreTheProviders:
    @pytest.mark.parametrize(
        ("error", "reason"),
        [
            (_credit_error(), "credit"),
            (_status_error(402, "Payment required", "billing_error"), "credit"),
            (_status_error(401, "invalid x-api-key", "authentication_error"), "auth"),
            (_status_error(403, "forbidden", "permission_error"), "auth"),
            (_status_error(529, "Overloaded", "overloaded_error"), "overloaded"),
            (_status_error(429, "Number of requests exceeded", "rate_limit_error"), "rate_limited"),
            (anthropic.APIConnectionError(request=REQUEST), "connection"),
            (anthropic.APITimeoutError(request=REQUEST), "connection"),
        ],
    )
    def test_the_provider_failing(self, error, reason):
        assert provider_reason(error) == reason

    @pytest.mark.parametrize(
        "error",
        [
            _status_error(400, "messages: roles must alternate"),
            _status_error(404, "model: not found", "not_found_error"),
            _status_error(500, "boom", "api_error"),
            ValueError("our own bug"),
        ],
    )
    def test_our_own_errors(self, error):
        assert provider_reason(error) is None


class TestTheOwnerIsTold:
    NOW = datetime(2026, 10, 14, 8, 0, tzinfo=ZoneInfo("Europe/Paris"))

    @pytest.fixture(autouse=True)
    def _paris(self):
        with patch(
            "app.llm.provider_status.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))
        ):
            yield

    @staticmethod
    def _mcp(existing: list[dict]) -> MagicMock:
        async def call_tool(name, arguments, user_id=None):
            assert name == "manage_notifications"
            if arguments["action"] == "list":
                return json.dumps({"notifications": existing, "count": len(existing)})
            return json.dumps({"success": True, "created": True})

        return MagicMock(call_tool=AsyncMock(side_effect=call_tool))

    def _created(self, mcp: MagicMock) -> list[dict]:
        return [c.args[1] for c in mcp.call_tool.await_args_list if c.args[1]["action"] == "create"]

    async def test_an_exhausted_credit_sends_a_push(self):
        mcp = self._mcp([])
        with patch("app.llm.provider_status.mcp_client", mcp):
            await alert_owner("owner-1", "credit", now=self.NOW)

        [created] = self._created(mcp)
        assert created["title"] == "Maggie ne peut plus répondre : crédit Anthropic épuisé"
        assert created["type"] == "proaction"
        # Sent as the user whose turn failed, through the existing push channel.
        assert all(c.kwargs["user_id"] == "owner-1" for c in mcp.call_tool.await_args_list)

    async def test_once_a_day_at_most(self):
        sent_this_morning = {
            "title": "Maggie ne peut plus répondre : crédit Anthropic épuisé",
            "createdAt": "2026-10-14T00:12:00+02:00",
        }
        mcp = self._mcp([sent_this_morning])
        with patch("app.llm.provider_status.mcp_client", mcp):
            await alert_owner("owner-1", "credit", now=self.NOW)

        assert self._created(mcp) == []

    async def test_again_the_next_day(self):
        sent_yesterday = {
            "title": "Maggie ne peut plus répondre : crédit Anthropic épuisé",
            "createdAt": "2026-10-13T23:41:00+02:00",
        }
        mcp = self._mcp([sent_yesterday])
        with patch("app.llm.provider_status.mcp_client", mcp):
            await alert_owner("owner-1", "credit", now=self.NOW)

        assert len(self._created(mcp)) == 1

    async def test_a_refused_key_is_told_too(self):
        mcp = self._mcp([])
        with patch("app.llm.provider_status.mcp_client", mcp):
            await alert_owner("owner-1", "auth", now=self.NOW)

        [created] = self._created(mcp)
        assert created["title"] == "Maggie ne peut plus répondre : clé Anthropic refusée"

    @pytest.mark.parametrize("reason", ["overloaded", "rate_limited", "connection"])
    async def test_a_passing_outage_is_not(self, reason):
        """It clears by itself: a push for each would be noise the owner can do nothing about."""
        mcp = self._mcp([])
        with patch("app.llm.provider_status.mcp_client", mcp):
            await alert_owner("owner-1", reason, now=self.NOW)

        mcp.call_tool.assert_not_awaited()

    async def test_a_failing_alert_never_breaks_the_turn(self):
        mcp = MagicMock(call_tool=AsyncMock(side_effect=httpx.ConnectError("api down")))
        with patch("app.llm.provider_status.mcp_client", mcp):
            await alert_owner("owner-1", "credit", now=self.NOW)

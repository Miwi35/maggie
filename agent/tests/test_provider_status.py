"""The provider's state, global to the account, behind GET /provider-status (MAG-727)."""

from unittest.mock import AsyncMock, MagicMock

import anthropic
import pytest

from app.llm.provider_status import note_provider_failure, note_provider_success
from app.llm.runner import run_tool_loop
from tests.test_provider_unavailable import _credit_error, _status_error


def _status(authed_client) -> dict:
    return authed_client.get("/provider-status").json()


def _client_failing(error: Exception) -> MagicMock:
    client = MagicMock()
    client.messages.create = AsyncMock(side_effect=error)
    return client


async def _run(client) -> None:
    await run_tool_loop(
        "system", [{"role": "user", "content": "Bonjour"}], [], client=client, tool_router=MagicMock(), user_id="u", model="m"
    )


@pytest.mark.usefixtures("provider_db")
class TestProviderStatus:
    def test_ok_by_default(self, authed_client):
        assert _status(authed_client) == {"status": "ok", "since": None}

    @pytest.mark.asyncio
    async def test_a_credit_failure_in_a_tool_loop_turns_it_to_credit(self, authed_client):
        with pytest.raises(anthropic.BadRequestError):
            await _run(_client_failing(_credit_error()))

        body = _status(authed_client)
        assert body["status"] == "credit"
        assert body["since"] is not None

    @pytest.mark.asyncio
    async def test_a_refused_key_turns_it_to_auth(self, authed_client):
        await note_provider_failure(_status_error(401, "invalid x-api-key", "authentication_error"))

        assert _status(authed_client)["status"] == "auth"

    @pytest.mark.asyncio
    async def test_the_next_success_turns_it_back_to_ok(self, authed_client):
        await note_provider_failure(_credit_error())
        await note_provider_success()

        assert _status(authed_client) == {"status": "ok", "since": None}

    @pytest.mark.asyncio
    @pytest.mark.parametrize("status", [529, 429])
    async def test_an_overload_or_a_quota_changes_nothing(self, authed_client, status):
        await note_provider_failure(_status_error(status, "later", "overloaded_error"))

        assert _status(authed_client)["status"] == "ok"

    @pytest.mark.asyncio
    async def test_since_is_kept_while_the_same_failure_goes_on(self, authed_client):
        await note_provider_failure(_credit_error())
        first = _status(authed_client)["since"]
        await note_provider_failure(_credit_error())

        assert _status(authed_client)["since"] == first

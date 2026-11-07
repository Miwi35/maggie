"""Technical accounts (smoke, recette) are served by the fake model, never by Anthropic (MAG-732)."""

import json
from unittest.mock import AsyncMock, MagicMock, patch

from app.llm import client as client_module
from app.llm.client import client_for
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.gateway import LLMGateway
from app.llm.runner import run_tool_loop
from app.memory.agent_memory import MemoryBlocks
from app import user_timezone

SMOKE_QUESTION = (
    "Appelle l'outil get_upcoming_events sans query, puis réponds en une phrase : "
    "quel est le titre exact de l'événement dont la description est « code 4242 » ?"
)


def _anthropic() -> MagicMock:
    """An Anthropic client that fails the test when it is called."""
    real = MagicMock()
    real.messages.create = AsyncMock(side_effect=AssertionError("Anthropic was called"))
    real.messages.stream = MagicMock(side_effect=AssertionError("Anthropic was called"))
    return real


def _technical(value: bool):
    return patch("app.llm.client.is_technical_account", AsyncMock(return_value=value))


class TestTheClientPerUser:
    async def test_a_technical_account_gets_the_fake_whatever_the_default(self):
        with _technical(True):
            assert isinstance(await client_for("smoke-id", _anthropic()), FakeAnthropicClient)
            assert isinstance(await client_for("smoke-id", None), FakeAnthropicClient)

    async def test_the_owner_keeps_the_default_client(self):
        real = _anthropic()
        with _technical(False):
            assert await client_for("owner-id", real) is real


class TestWhoIsTechnical:
    async def test_the_smoke_and_recette_emails_are_technical(self):
        user_timezone._technical.clear()
        emails = {"u-smoke": "smoke@maggieai.fr", "u-recette": "recette@maggieai.fr", "u-owner": "me@example.com"}
        with patch.object(user_timezone, "resolve_user_email", AsyncMock(side_effect=lambda uid: emails[uid])):
            assert await user_timezone.is_technical_account("u-smoke") is True
            assert await user_timezone.is_technical_account("u-recette") is True
            assert await user_timezone.is_technical_account("u-owner") is False

    async def test_an_unreadable_email_is_not_technical_and_not_remembered(self):
        user_timezone._technical.clear()
        with patch.object(user_timezone, "resolve_user_email", AsyncMock(return_value=None)):
            assert await user_timezone.is_technical_account("u-blip") is False
        with patch.object(user_timezone, "resolve_user_email", AsyncMock(return_value="smoke@maggieai.fr")):
            assert await user_timezone.is_technical_account("u-blip") is True
        user_timezone._technical.clear()


class TestNoAnthropicCallForATechnicalAccount:
    def _gateway(self) -> LLMGateway:
        gateway = LLMGateway()
        gateway.client = _anthropic()
        gateway.personality = MagicMock()
        gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
        gateway.agent_memory = MagicMock()
        gateway.agent_memory.blocks = AsyncMock(return_value=MemoryBlocks())
        gateway.tool_router = MagicMock()
        gateway.tool_router.get_tool_definitions = AsyncMock(return_value=[])
        return gateway

    def _stubs(self, run):
        skills = MagicMock(
            get_skills_index=MagicMock(return_value=""), skills_for_moment=MagicMock(return_value=""), refresh=AsyncMock()
        )
        return [
            patch("app.llm.gateway.run_tool_loop", run),
            patch("app.llm.gateway.skill_index", skills),
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
            patch("app.llm.gateway.active_contexts_section", AsyncMock(return_value="")),
            patch("app.llm.gateway.resolve_user_timezone", AsyncMock(return_value=__import__("zoneinfo").ZoneInfo("Europe/Paris"))),
        ]

    async def _clients_used(self, call, technical: bool) -> list:
        used: list = []

        async def run(_system, _messages, _tools, *, client, **_kwargs):
            used.append(client)
            return {"response": "ok", "tool_calls": []}

        patchers = [*self._stubs(run), _technical(technical)]
        for p in patchers:
            p.start()
        try:
            await call(self._gateway())
        finally:
            for p in patchers:
                p.stop()
        return used

    async def test_a_proaction_of_a_technical_account_runs_on_the_fake(self):
        used = await self._clients_used(lambda g: g.proaction("Rappelle-moi", "smoke-id", silent=True), True)

        assert used and all(isinstance(c, FakeAnthropicClient) for c in used)

    async def test_a_proaction_of_the_owner_runs_on_anthropic(self):
        used = await self._clients_used(lambda g: g.proaction("Rappelle-moi", "owner-id", silent=True), False)

        assert used and not any(isinstance(c, FakeAnthropicClient) for c in used)

    async def test_the_routing_of_a_technical_account_never_reaches_anthropic(self):
        from app.llm.contexts import resolve_context

        real = _anthropic()
        with _technical(True), patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[]))):
            await resolve_context(real, "Bonjour", "smoke-id")

        real.messages.create.assert_not_called()


class TestTheSmokeQuestion:
    async def test_it_gets_a_tool_call_and_the_title_read_from_its_result(self, monkeypatch):
        monkeypatch.setattr("app.llm.client.settings.fake_llm_fixtures_dir", "")
        monkeypatch.setattr("app.llm.fake.settings.fake_llm_fixtures_dir", "")
        client_module._technical_client = None
        events = {"events": [{"id": "1", "summary": "Rendez-vous smoke 1234", "description": "code 4242"}], "count": 1}
        router = MagicMock(call_tool=AsyncMock(return_value=json.dumps(events)))
        real = _anthropic()

        with _technical(True):
            chosen = await client_for("smoke-id", real)
            result = await run_tool_loop(
                "Tu es Maggie.",
                [{"role": "user", "content": SMOKE_QUESTION}],
                [{"name": "get_upcoming_events", "description": "", "input_schema": {"type": "object"}}],
                client=chosen,
                tool_router=router,
                user_id="smoke-id",
                model="claude-sonnet-5",
            )

        assert [call["name"] for call in result["tool_calls"]] == ["get_upcoming_events"]
        assert "Rendez-vous smoke 1234" in result["response"]
        real.messages.create.assert_not_called()
        assert DEFAULT_FIXTURES_DIR.joinpath("99-smoke-prod-event-title.yaml").exists()

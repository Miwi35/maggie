import json
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.a2a import setup_a2a
from app.config import settings
from app.llm.gateway import LLMGateway
from app.llm.tools import A2A_ALLOWED_TOOLS, MEMORY_TOOLS, PROACTION_TOOLS, ToolRouter

A2A_TOKEN = "a2a-secret-token"

RPC_MESSAGE = {
    "jsonrpc": "2.0",
    "id": "1",
    "method": "message/send",
    "params": {
        "message": {
            "role": "user",
            "messageId": "m-1",
            "parts": [{"kind": "text", "text": "Qu'y a-t-il à l'agenda ?"}],
        }
    },
}


@pytest.fixture(params=["", "/agent"], ids=["bare", "behind-agent-prefix"])
def root_path(request):
    return request.param


class A2AClient:
    def __init__(self, client: TestClient, gateway_chat: AsyncMock):
        self.client = client
        self.gateway_chat = gateway_chat

    def post(self, *args, **kwargs):
        return self.client.post(*args, **kwargs)

    def get(self, *args, **kwargs):
        return self.client.get(*args, **kwargs)


@pytest.fixture()
def a2a_client(root_path):
    """The real A2A routes on a FastAPI app, served the way Traefik serves the agent."""
    app = FastAPI(root_path=root_path)
    with patch("app.a2a.executor.LLMGateway") as gateway_cls:
        gateway_cls.return_value.chat = AsyncMock(return_value={"response": "Rien de prévu.", "tool_calls": []})
        setup_a2a(app)
        with patch.object(settings, "a2a_token", A2A_TOKEN), TestClient(app) as client:
            yield A2AClient(client, gateway_cls.return_value.chat)


class TestA2AEndpointAuth:
    def test_no_token_is_401_and_nothing_runs(self, a2a_client):
        response = a2a_client.post("/a2a", json=RPC_MESSAGE)

        assert response.status_code == 401
        assert response.headers["www-authenticate"] == "Bearer"
        a2a_client.gateway_chat.assert_not_awaited()

    def test_wrong_token_is_401(self, a2a_client):
        response = a2a_client.post("/a2a", json=RPC_MESSAGE, headers={"Authorization": "Bearer nope"})

        assert response.status_code == 401
        a2a_client.gateway_chat.assert_not_awaited()

    def test_non_bearer_scheme_is_401(self, a2a_client):
        response = a2a_client.post("/a2a", json=RPC_MESSAGE, headers={"Authorization": f"Basic {A2A_TOKEN}"})

        assert response.status_code == 401

    def test_service_token_does_not_open_the_endpoint(self, a2a_client):
        with patch.object(settings, "service_token", "internal-service-token"):
            response = a2a_client.post(
                "/a2a", json=RPC_MESSAGE, headers={"Authorization": "Bearer internal-service-token"}
            )

        assert response.status_code == 401

    def test_unconfigured_token_closes_the_endpoint(self, a2a_client):
        with patch.object(settings, "a2a_token", ""):
            for header in ({}, {"Authorization": "Bearer "}, {"Authorization": "Bearer anything"}):
                response = a2a_client.post("/a2a", json=RPC_MESSAGE, headers=header)
                assert response.status_code == 401

        a2a_client.gateway_chat.assert_not_awaited()

    def test_valid_token_reaches_the_executor(self, a2a_client):
        response = a2a_client.post("/a2a", json=RPC_MESSAGE, headers={"Authorization": f"Bearer {A2A_TOKEN}"})

        assert response.status_code == 200
        assert response.json()["result"]["parts"][0]["text"] == "Rien de prévu."
        a2a_client.gateway_chat.assert_awaited_once()

    def test_agent_card_stays_public(self, a2a_client):
        response = a2a_client.get("/.well-known/agent-card.json")

        assert response.status_code == 200
        assert response.json()["name"] == settings.agent_name

    def test_agent_card_declares_the_bearer_scheme(self, a2a_client):
        card = a2a_client.get("/.well-known/agent-card.json").json()

        assert card["securitySchemes"]["bearer"]["scheme"] == "bearer"
        assert card["security"] == [{"bearer": []}]


class TestA2AToolRestriction:
    @patch("app.llm.tools.mcp_client")
    async def test_a2a_is_offered_read_only_mcp_tools_and_no_native_tool(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(
            return_value=[
                {"name": "get_upcoming_events", "description": "read", "inputSchema": {"type": "object"}},
                {"name": "create_event", "description": "write", "inputSchema": {"type": "object"}},
                {"name": "manage_accounts", "description": "write", "inputSchema": {"type": "object"}},
            ]
        )

        tools = await ToolRouter().get_tool_definitions(source="a2a")

        assert [t["name"] for t in tools] == ["get_upcoming_events"]

    def test_allowlist_holds_no_native_and_no_mutating_tool(self):
        native = {t["name"] for t in [*MEMORY_TOOLS, *PROACTION_TOOLS]}

        assert not native & A2A_ALLOWED_TOOLS
        assert not {n for n in A2A_ALLOWED_TOOLS if n.startswith(("create_", "update_", "delete_", "manage_"))}

    @pytest.mark.parametrize("name", ["store_memory", "schedule_proaction", "create_skill", "create_event", "manage_accounts"])
    async def test_a2a_call_to_a_write_tool_is_refused_without_reaching_it(self, name):
        with patch("app.llm.tools.mcp_client") as mock_mcp_client:
            mock_mcp_client.call_tool = AsyncMock(return_value="{}")

            result = await ToolRouter().call_tool(name, {}, user_id="a2a", source="a2a")

        assert "error" in json.loads(result)
        mock_mcp_client.call_tool.assert_not_awaited()

    @patch("app.llm.tools.mcp_client")
    async def test_a2a_call_to_a_read_tool_goes_through(self, mock_mcp_client):
        mock_mcp_client.call_tool = AsyncMock(return_value='{"events": []}')

        result = await ToolRouter().call_tool("get_upcoming_events", {}, user_id="a2a", source="a2a")

        assert result == '{"events": []}'

    @patch("app.llm.tools.mcp_client")
    async def test_other_sources_keep_every_tool(self, mock_mcp_client):
        mock_mcp_client.list_tools = AsyncMock(
            return_value=[{"name": "create_event", "description": "w", "inputSchema": {"type": "object"}}]
        )

        names = {t["name"] for t in await ToolRouter().get_tool_definitions()}

        assert {"create_event", "store_memory", "schedule_proaction"} <= names

    async def test_gateway_chat_asks_for_a2a_tools_only_when_called_from_a2a(self):
        with patch.object(LLMGateway, "__init__", lambda _self: None):
            gateway = LLMGateway()
        gateway.client = object()
        gateway.tool_router = ToolRouter()
        gateway._build_system_prompt = AsyncMock(return_value=[])

        with (
            patch.object(ToolRouter, "get_tool_definitions", AsyncMock(return_value=[])) as definitions,
            patch("app.llm.gateway.run_tool_loop", AsyncMock(return_value={"response": "ok", "tool_calls": []})),
            patch("app.llm.gateway.build_history", AsyncMock(return_value=[{"role": "user", "content": "salut"}])),
        ):
            await gateway.chat("salut", "a2a", source="a2a")
            definitions.assert_awaited_once_with(include_native=True, source="a2a")

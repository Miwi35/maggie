"""Maggie funds a project with savings and a projected loan through `manage_project_funding` (MAG-524).

Whether a real model fills the right fields is judged on the real model; what this proves is the
run itself with the shipped scenario: the projects and the accounts read in one round, then the
whole list of sources set on the ids that round returned — the Voiture's and the Livret jeune's,
not another project's or the cushion's.
"""

import json
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter

USER = "user-1"
CAR_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"
TRIP_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7X"
CUSHION_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7Y"
LIVRET_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7Z"
OFFERED = [
    {"name": "manage_projects", "input_schema": {}},
    {"name": "manage_accounts", "input_schema": {}},
    {"name": "manage_project_funding", "input_schema": {}},
]
MESSAGE = "Pour la voiture, prends 1 000 € sur le Livret jeune et emprunte 6 000 € sur 24 mois à 4 %"


class FakeFinanceApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        if name == "manage_projects":
            projects = [
                {"id": TRIP_ID, "name": "Voyage", "amountCents": 300000},
                {"id": CAR_ID, "name": "Voiture", "amountCents": 1000000},
            ]
            return json.dumps({"projects": projects})
        if name == "manage_accounts":
            accounts = [
                {"id": CUSHION_ID, "name": "Livret A", "type": "savings", "isCushion": True},
                {"id": LIVRET_ID, "name": "Livret jeune", "type": "savings", "isCushion": False},
            ]
            return json.dumps({"accounts": accounts})
        return json.dumps({"success": True})


@pytest.fixture()
def api():
    fake = FakeFinanceApi()
    with patch("app.llm.tools.mcp_client", fake):
        yield fake


async def ask(messages: list[dict]) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            messages,
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="chat",
        )


async def test_the_sources_are_set_on_the_ids_the_lists_gave(api):
    result = await ask([{"role": "user", "content": MESSAGE}])

    assert api.calls == [
        ("manage_projects", {"action": "list"}),
        ("manage_accounts", {"action": "list"}),
        (
            "manage_project_funding",
            {
                "action": "set",
                "projectId": CAR_ID,
                "sources": [
                    {"kind": "savings", "amountCents": 100000, "account": LIVRET_ID},
                    {
                        "kind": "loan",
                        "amountCents": 600000,
                        "loan": {"annualRateBasisPoints": 400, "durationMonths": 24},
                    },
                ],
            },
        ),
    ]
    assert "3 000 €" in result["response"]


async def test_the_answer_states_facts_and_recommends_nothing(api):
    result = await ask([{"role": "user", "content": MESSAGE}])

    lowered = result["response"].lower()
    assert "préférable" not in lowered
    assert "recommand" not in lowered

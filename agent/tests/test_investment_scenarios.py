"""Maggie makes a project an investment through `manage_investments` (MAG-557).

Whether a real model fills the right fields is judged on the real model; what this proves is the
run itself with the shipped scenario: the projects read, then the WHOLE investment set on the
Box's id — amounts in cents, the rate in basis points, the resale month as YYYY-MM — and the
approval rules that apply to the tool: `get` reads freely, `set` and `remove` are held.
"""

import json
from unittest.mock import patch

import pytest

from app.llm.dry_run import is_read_only
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.policy.summary import GENERIC_LABEL, action_label

USER = "user-1"
BOX_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"
CAR_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7X"
OFFERED = [
    {"name": "manage_projects", "input_schema": {}},
    {"name": "manage_investments", "input_schema": {}},
]
MESSAGE = (
    "Ajoute un investissement Box à louer : 30 000 €, 5 000 € d'apport, un prêt sur 20 ans à 3,9 %, "
    "loyer 160 € par mois, revente en juin 2036 pour 15 000 € net"
)


class FakeInvestmentApi:
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
                {"id": CAR_ID, "name": "Voiture", "amountCents": 1000000},
                {"id": BOX_ID, "name": "Box", "amountCents": 3000000},
            ]
            return json.dumps({"projects": projects})
        return json.dumps({"success": True})


@pytest.fixture()
def api():
    fake = FakeInvestmentApi()
    with patch("app.llm.tools.mcp_client", fake):
        yield fake


async def ask(content: str) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": content}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="chat",
        )


async def test_the_investment_is_set_whole_on_the_box_the_list_gave(api):
    result = await ask(MESSAGE)

    assert api.calls == [
        ("manage_projects", {"action": "list"}),
        (
            "manage_investments",
            {
                "action": "set",
                "projectId": BOX_ID,
                "priceCents": 3000000,
                "downPaymentCents": 500000,
                "monthlyRentCents": 16000,
                "loan": {"annualRateBasisPoints": 390, "durationMonths": 240},
                "resale": {"month": "2036-06", "netAmountCents": 1500000},
            },
        ),
    ]
    assert "Revente prévue en juin 2036" in result["response"]


class TestTheApprovalRules:
    def test_get_reads_freely(self):
        assert is_read_only("manage_investments", {"action": "get", "projectId": BOX_ID})

    @pytest.mark.parametrize("action", ["set", "remove"])
    def test_set_and_remove_are_held_for_approval(self, action):
        assert not is_read_only("manage_investments", {"action": action, "projectId": BOX_ID})

    @pytest.mark.parametrize("action", ["set", "remove"])
    def test_the_card_names_no_tool_and_no_id(self, action):
        assert action_label("manage_investments", {"action": action, "projectId": BOX_ID}) == GENERIC_LABEL

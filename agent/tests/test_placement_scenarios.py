"""Maggie creates and simulates a placement through `manage_placements` (MAG-555).

Whether a real model fills the right fields is judged on the real model; what this proves is the
run itself with the shipped scenarios: the tool they reach and its arguments — the deposit in
cents, the rate in basis points, `initial: 0` said rather than deduced.
"""

import json
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter

USER = "user-1"
OFFERED = [{"name": "manage_placements", "input_schema": {}}]
LIVRET = {"initial": 0, "deposit": 10000, "frequency": "monthly", "annualRate": 500, "years": 10}


class FakePlacementApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        return json.dumps({"success": True})


@pytest.fixture()
def api():
    fake = FakePlacementApi()
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


async def test_the_placement_is_created_with_every_field(api):
    result = await ask("Ajoute un placement Livret de 100 euros par mois à 5 % sur 10 ans, sans versement initial")

    assert api.calls == [("manage_placements", {"action": "create", "name": "Livret", **LIVRET})]
    assert "15 436 €" in result["response"]


async def test_a_what_if_is_simulated_without_saving(api):
    result = await ask("Combien j'aurais en plaçant 100 euros par mois à 5 % pendant 10 ans ?")

    assert api.calls == [("manage_placements", {"action": "simulate", **LIVRET})]
    assert result["response"].startswith("En simulation")

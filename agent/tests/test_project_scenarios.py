"""Maggie creates a project, reorders the priority list through `manage_projects` (MAG-519) and reads
its planning through `get_project_planning` (MAG-526).

Whether a real model fills the right fields is judged on the real model; what this proves is the
run itself with the shipped scenarios: the tool they reach, its arguments, and that the reorder
carries every project id, the Canapé first, taken from the list the first message read.
"""

import json
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tool_blocks import replay
from app.llm.tools import ToolRouter

USER = "user-1"
MOVE_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"
SOFA_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7X"
OFFERED = [
    {"name": "manage_projects", "input_schema": {}},
    {"name": "get_project_planning", "input_schema": {}},
]


def _project(project_id: str, name: str, position: int, depends_on: list[str]) -> dict:
    return {
        "id": project_id,
        "name": name,
        "amountCents": 120000,
        "currency": "EUR",
        "wellbeing": "medium",
        "wellbeingScore": 2,
        "position": position,
        "dateMode": "none",
        "targetOn": None,
        "dependsOn": depends_on,
        "note": None,
        "unmetDependencies": [],
    }


class FakeProjectApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        if name == "get_project_planning":
            return json.dumps({"projects": [], "months": []})
        if arguments.get("action") == "list":
            # The order the seed gives (api/fixtures/e2e/50-finance.yaml); json.dumps escapes the
            # accents as the API's json_encode does.
            projects = [_project(MOVE_ID, "Déménagement", 0, []), _project(SOFA_ID, "Canapé", 1, [MOVE_ID])]
            return json.dumps({"projects": projects})
        return json.dumps({"success": True})


@pytest.fixture()
def api():
    fake = FakeProjectApi()
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


class TestCreate:
    async def test_the_project_is_created_with_its_deadline(self, api):
        result = await ask(
            [
                {
                    "role": "user",
                    "content": "Ajoute un projet Voyage au Japon de 3000 euros avec une date limite en août 2027",
                }
            ]
        )

        assert api.calls == [
            (
                "manage_projects",
                {
                    "action": "create",
                    "name": "Voyage au Japon",
                    "amountCents": 300000,
                    "dateMode": "deadline",
                    "targetOn": "2027-08-31",
                    "wellbeing": "medium",
                },
            )
        ]
        assert "Voyage au Japon" in result["response"]


class TestReorder:
    async def test_the_first_message_only_reads_the_order(self, api):
        result = await ask([{"role": "user", "content": "Mets le canapé en premier"}])

        assert api.calls == [("manage_projects", {"action": "list"})]
        assert result["response"].endswith("?")

    async def test_the_yes_reorders_every_project_the_canape_first(self, api):
        first = await ask([{"role": "user", "content": "Mets le canapé en premier"}])
        history = [
            {"role": "user", "content": "Mets le canapé en premier"},
            *replay(first["blocks"]),
            {"role": "assistant", "content": first["response"]},
            {"role": "user", "content": "Oui, le canapé d'abord"},
        ]
        api.calls.clear()

        result = await ask(history)

        assert api.calls == [("manage_projects", {"action": "reorder", "ids": [SOFA_ID, MOVE_ID]})]
        assert "Canapé passe en premier" in result["response"]

    async def test_without_the_list_in_the_history_nothing_is_reordered(self, api):
        result = await ask([{"role": "user", "content": "Oui, le canapé d'abord"}])

        assert api.calls == []
        assert result["response"].startswith("[fake-llm]")


class TestPlanning:
    async def test_when_can_the_sofa_be_bought_reads_the_planning(self, api):
        result = await ask([{"role": "user", "content": "Quand pourrai-je acheter le canapé ?"}])

        assert api.calls == [("get_project_planning", {})]
        assert "Canapé" in result["response"]

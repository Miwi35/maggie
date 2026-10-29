"""The advisor sub-agent reads, simulates and files a draft — it never writes anything else (MAG-559)."""

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from app.agents.delegate import select_tools
from app.agents.registry import SubagentRegistry
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.policy.engine import Mode, policy_engine
from tests.test_subagent_registry import MCP_TOOL_NAMES

SHIPPED_AGENTS = Path(__file__).parent.parent / "data" / "agents"
SOURCE = "subagent:advisor"
WRITES = {
    "manage_scenarios": ["create", "update", "duplicate", "delete", "make_current", "clear_current"],
    "manage_loans": ["create", "update", "delete"],
    "manage_placements": ["create", "update", "delete"],
    "manage_projects": ["create", "update", "delete", "reorder"],
    "manage_accounts": ["create", "update", "delete"],
}
READS = {
    "manage_scenarios": ["list", "get", "compare", "timeline", "suggest"],
    "manage_loans": ["list", "timeline", "early_repayment"],
    "manage_placements": ["list", "simulate"],
    "manage_projects": ["list"],
    "manage_accounts": ["list"],
}


def advisor():
    registry = SubagentRegistry()
    registry.load(SHIPPED_AGENTS)
    definition = registry.get("advisor")
    assert definition is not None
    return definition


def test_the_advisor_only_reaches_finance_reads_and_the_manage_tools_the_policy_narrows():
    reachable = {t["name"] for t in select_tools(advisor(), [{"name": n} for n in MCP_TOOL_NAMES + ("delegate",)])}

    assert {"manage_scenarios", "manage_loans", "manage_placements", "manage_projects"} <= reachable
    assert not {n for n in reachable if n.startswith(("create_", "update_", "delete_"))}
    assert "delegate" not in reachable
    assert {n for n in reachable if n.startswith("manage_")} <= set(WRITES)


@pytest.mark.parametrize(("tool", "action"), [(t, a) for t, actions in WRITES.items() for a in actions])
def test_every_write_action_is_refused_to_the_advisor(tool, action):
    assert policy_engine.evaluate(tool, {"action": action}, SOURCE) is Mode.DENY


@pytest.mark.parametrize(("tool", "action"), [(t, a) for t, actions in READS.items() for a in actions])
def test_reads_simulations_and_the_draft_are_allowed(tool, action):
    assert policy_engine.evaluate(tool, {"action": action}, SOURCE) is Mode.ALLOW


def test_a_call_without_action_is_refused_to_the_advisor():
    assert policy_engine.evaluate("manage_scenarios", {}, SOURCE) is Mode.DENY


def test_maggie_herself_keeps_making_a_scenario_current_on_the_users_word():
    assert policy_engine.evaluate("manage_scenarios", {"action": "make_current"}, "chat") is Mode.ALLOW


PROJECTS = [
    {"id": "01HZZZZZZZZZZZZZZZZZZZZZZ1", "name": "Déménagement"},
    {"id": "01HZZZZZZZZZZZZZZZZZZZZZZ2", "name": "Canapé"},
]
LOAN_ID = "01HZZZZZZZZZZZZZZZZZZZZZZ3"
LOANS = [{"id": LOAN_ID, "name": "Crédit voiture"}]


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
            return json.dumps({"projects": PROJECTS})
        if name == "manage_loans" and arguments.get("action") == "list":
            return json.dumps({"loans": LOANS})
        return json.dumps({"success": True})


async def test_the_advisor_run_simulates_then_files_one_draft_and_nothing_else():
    api = FakeFinanceApi()
    definition = advisor()
    offered = [{"name": n, "input_schema": {}} for n in definition.tools if n.startswith("manage_")]

    with patch("app.llm.tools.mcp_client", api), patch("app.llm.runner.record_llm_usage"):
        result = await run_tool_loop(
            definition.prompt,
            [{"role": "user", "content": "Et si je remboursais le prêt auto plus tôt ?"}],
            offered,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            max_iterations=definition.max_iterations,
            source=SOURCE,
        )

    actions = [(name, args.get("action")) for name, args in api.calls]
    assert actions == [
        ("manage_projects", "list"),
        ("manage_loans", "list"),
        ("manage_loans", "early_repayment"),
        ("manage_scenarios", "suggest"),
    ]
    suggest = api.calls[-1][1]
    assert suggest["projectIds"] == [p["id"] for p in PROJECTS]
    assert suggest["repayments"][0]["loanId"] == LOAN_ID
    assert "make_current" not in json.dumps(api.calls)
    assert "vous devriez" not in result["response"].lower()
    assert "brouillon" in result["response"]


async def test_maggie_hands_a_trajectory_question_to_the_advisor():
    api = FakeFinanceApi()

    async def delegate(arguments: dict, user_id: str) -> str:
        assert arguments["agent"] == "advisor"
        return json.dumps({"agent": "advisor", "result": "brouillon déposé"})

    with (
        patch("app.llm.tools.mcp_client", api),
        patch("app.llm.runner.record_llm_usage"),
        patch.dict("app.llm.tools._NATIVE_HANDLERS", {"delegate": delegate}, clear=False),
    ):
        result = await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": "Et si je remboursais le prêt auto plus tôt ?"}],
            [{"name": "delegate", "input_schema": {}}],
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            source="chat",
        )

    assert "champ du possible" in result["response"]
    assert api.calls == []


async def test_the_prompt_sends_trajectory_questions_to_the_advisor_as_facts_never_advice():
    from unittest.mock import AsyncMock

    from app.personality.engine import PersonalityEngine

    with patch("app.personality.engine.personality_repo") as repo:
        repo.get = AsyncMock(return_value=None)
        prompt = await PersonalityEngine().get_system_prompt("user-1")

    line = next(line for line in prompt.splitlines() if "TRAJECTOIRE" in line)
    assert "`advisor`" in line and "revue mensuelle" in line
    assert "champ du possible" in line and "jamais comme un conseil" in line
    assert "ne rends jamais un scénario courant" in line

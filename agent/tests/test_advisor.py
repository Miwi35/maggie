"""The advisor sub-agent reads, simulates and proposes a draft — it never writes anything else (MAG-559)."""

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from app.agents.delegate import select_tools
from app.agents.report import ReportingToolRouter
from app.agents.registry import SubagentRegistry
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.native_tools import build_native_registry
from app.policy.engine import Mode, policy_engine
from tests.test_subagent_registry import MCP_TOOL_NAMES

SHIPPED_AGENTS = Path(__file__).parent.parent / "data" / "agents"
SOURCE = "subagent:advisor"
WRITES = {
    "manage_scenarios": ["create", "update", "duplicate", "delete", "make_current", "clear_current", "suggest"],
    "manage_loans": ["create", "update", "delete"],
    "manage_placements": ["create", "update", "delete"],
    "manage_projects": ["create", "update", "delete", "reorder"],
    "manage_accounts": ["create", "update", "delete"],
}
READS = {
    "manage_scenarios": ["list", "get", "compare", "timeline"],
    "manage_loans": ["list", "timeline", "early_repayment"],
    "manage_placements": ["list", "simulate"],
    "manage_projects": ["list", "preview"],
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
def test_reads_and_simulations_are_allowed(tool, action):
    assert policy_engine.evaluate(tool, {"action": action}, SOURCE) is Mode.ALLOW


def test_the_policy_refuses_suggest_to_the_advisor():
    assert policy_engine.evaluate("manage_scenarios", {"action": "suggest"}, SOURCE) is Mode.DENY
    assert policy_engine.evaluate("manage_scenarios", {"action": "suggest"}, "chat") is Mode.ALLOW


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


async def test_the_advisor_simulates_and_proposes_one_draft_without_writing():
    api = FakeFinanceApi()
    definition = advisor()
    offered = select_tools(definition, [{"name": n, "input_schema": {}} for n in definition.tools])
    reporting = ReportingToolRouter(ToolRouter(natives=build_native_registry()))

    with patch("app.llm.tools.mcp_client", api), patch("app.runtime.loop.record_llm_usage"):
        await run_tool_loop(
            definition.prompt,
            [{"role": "user", "content": "Et si je remboursais le prêt auto plus tôt ?"}],
            offered,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=reporting,
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
    ]
    assert "make_current" not in json.dumps(api.calls)
    report = reporting.report
    assert report is not None
    assert "vous devriez" not in report["result"].lower()
    assert "champ du possible" in report["result"].lower()
    (proposal,) = report["proposals"]
    assert proposal["what"] == "manage_scenarios suggest"
    assert proposal["values"]["projectIds"] == [p["id"] for p in PROJECTS]
    assert proposal["values"]["repayments"][0]["loanId"] == LOAN_ID


async def test_maggie_files_the_draft_the_advisor_proposed():
    api = FakeFinanceApi()
    ids = [p["id"] for p in PROJECTS]
    returned = {
        "agent": "advisor",
        "complete": True,
        "result": "champ du possible",
        "proposals": [
            {
                "what": "manage_scenarios suggest",
                "target": "Planification",
                "values": {"name": "x", "projectIds": ids, "repayments": [{"loanId": LOAN_ID, "month": "2026-12"}]},
                "why": "y",
            }
        ],
    }

    async def delegate(arguments: dict, ctx) -> str:
        assert arguments["agent"] == "advisor"
        return json.dumps(returned)

    with (
        patch("app.llm.tools.mcp_client", api),
        patch("app.runtime.loop.record_llm_usage"),
    ):
        result = await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": "Et si je remboursais le prêt auto plus tôt ?"}],
            [{"name": "delegate", "input_schema": {}}, {"name": "manage_scenarios", "input_schema": {}}],
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(natives=build_native_registry().with_handler("delegate", delegate)),
            user_id="user-1",
            model="fake",
            source="chat",
        )

    assert "champ du possible" in result["response"]
    (name, suggest), = api.calls
    assert (name, suggest["action"]) == ("manage_scenarios", "suggest")
    assert suggest["projectIds"] == ids
    assert suggest["repayments"][0]["loanId"] == LOAN_ID


async def test_the_advisor_description_gives_its_facts_never_advice_rule():
    from pathlib import Path

    text = (Path(__file__).parent.parent / "data" / "agents" / "advisor.md").read_text(encoding="utf-8")
    assert "Décris-lui la question en entier" in text
    assert "champ du possible" in text and "jamais comme un conseil" in text
    assert "ne rends un scénario courant que sur demande" in text

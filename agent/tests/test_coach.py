"""The coach sub-agent prepares the sports week; Maggie writes the sessions (MAG-778)."""

import json
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

from app.agents.delegate import select_tools
from app.agents.registry import SubagentRegistry
from app.coach_method import SECTIONS, handle_get_coach_method
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient, expand_days
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.policy.engine import Mode, policy_engine
from tests.test_subagent_registry import MCP_TOOL_NAMES

SHIPPED_AGENTS = Path(__file__).parent.parent / "data" / "agents"
SOURCE = "subagent:coach"
TOOLS = [
    "get_coach_method",
    "get_exercises",
    "get_next_prescription",
    "get_training_status",
    "manage_workouts",
    "get_events_by_date",
    "get_upcoming_events",
    "get_health_day",
    "get_user_timezone",
    "memory_search",
]


def coach():
    registry = SubagentRegistry()
    registry.load(SHIPPED_AGENTS)
    definition = registry.get("coach")
    assert definition is not None
    return definition


class TestTheRegistry:
    def test_the_coach_is_loaded_with_its_model_and_its_tools(self):
        definition = coach()

        assert "sonnet" in definition.model_id
        assert definition.tools == TOOLS

    def test_the_coach_cannot_delegate(self):
        names = [*MCP_TOOL_NAMES, "delegate", "get_coach_method", "get_user_timezone", "memory_search"]

        reachable = {t["name"] for t in select_tools(coach(), [{"name": n} for n in names]) if t["name"]}

        assert "delegate" not in reachable
        assert "get_coach_method" in reachable


class TestThePolicy:
    @pytest.mark.parametrize("action", ["create", "update", "delete"])
    def test_the_coach_cannot_write_a_workout(self, action):
        assert policy_engine.evaluate("manage_workouts", {"action": action}, SOURCE) is Mode.DENY

    def test_the_coach_can_list_workouts(self):
        assert policy_engine.evaluate("manage_workouts", {"action": "list"}, SOURCE) is Mode.ALLOW

    def test_a_call_without_action_is_refused_to_the_coach(self):
        assert policy_engine.evaluate("manage_workouts", {}, SOURCE) is Mode.DENY

    def test_maggie_writes_the_sessions(self):
        assert policy_engine.evaluate("manage_workouts", {"action": "create"}, "chat") is Mode.ALLOW


class TestGetCoachMethod:
    async def test_one_section_comes_back_whole(self):
        answer = json.loads(await handle_get_coach_method({"section": "calibrage"}, "user-1"))

        assert answer["section"] == "calibrage"
        assert answer["text"].startswith("## 7.")

    async def test_every_section_key_has_its_text(self):
        for key in SECTIONS:
            answer = json.loads(await handle_get_coach_method({"section": key}, "user-1"))
            assert answer["text"].startswith("## "), key

    async def test_without_a_section_it_lists_the_eight_sections(self):
        answer = json.loads(await handle_get_coach_method({}, "user-1"))

        assert [row["section"] for row in answer["sections"]] == SECTIONS
        assert all(row["title"] for row in answer["sections"])

    async def test_an_unknown_key_answers_the_valid_ones(self):
        answer = json.loads(await handle_get_coach_method({"section": "nope"}, "user-1"))

        assert answer["error"]
        assert answer["sections"] == SECTIONS


class TestRelativeNextWeekDays:
    def test_the_next_week_runs_monday_to_sunday_after_this_one(self):
        today = date.fromisoformat(expand_days("${today}"))
        monday = date.fromisoformat(expand_days("${next_monday}"))

        assert monday > today
        assert monday.weekday() == 0
        assert monday - today <= timedelta(days=7)
        assert date.fromisoformat(expand_days("${next_wednesday}")) == monday + timedelta(days=2)
        assert date.fromisoformat(expand_days("${next_friday}")) == monday + timedelta(days=4)
        assert date.fromisoformat(expand_days("${next_sunday}")) == monday + timedelta(days=6)


class FakeHealthApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        return json.dumps({"success": True, "programWeek": None, "events": []})


async def maggie_turn(message: str, api: FakeHealthApi, delegated: list[dict]) -> dict:
    async def delegate(arguments: dict, user_id: str) -> str:
        delegated.append(arguments)
        return json.dumps({"agent": "coach", "result": "semaine de calibrage proposée"})

    with (
        patch("app.llm.tools.mcp_client", api),
        patch("app.llm.runner.record_llm_usage"),
        patch.dict("app.llm.tools._NATIVE_HANDLERS", {"delegate": delegate}, clear=False),
    ):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": message}],
            [{"name": "delegate", "input_schema": {}}, {"name": "manage_workouts", "input_schema": {}}],
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            source="chat",
        )


@pytest.mark.parametrize(
    "message",
    ["Prépare ma semaine de sport", "Bilan du soir, étape demain : on est dimanche"],
)
async def test_maggie_delegates_to_the_coach_then_writes_three_planned_sessions(message):
    api = FakeHealthApi()
    delegated: list[dict] = []

    result = await maggie_turn(message, api, delegated)

    assert [d["agent"] for d in delegated] == ["coach"]
    assert [name for name, _ in api.calls] == ["manage_workouts"] * 3
    creations = [args for _, args in api.calls]
    assert all(c["action"] == "create" and c["source"] == "planned" and c["type"] == "strength" for c in creations)
    assert [c["variant"] for c in creations] == ["calibration", "normal", "normal"]
    assert [c["startedAt"][:10] for c in creations] == [
        expand_days("${next_monday}"),
        expand_days("${next_wednesday}"),
        expand_days("${next_friday}"),
    ]
    assert all(c["exercises"] for c in creations)
    assert "coach" not in result["response"].lower()


async def test_the_coach_run_reads_the_method_and_prescribes_without_writing():
    api = FakeHealthApi()
    definition = coach()
    offered = [{"name": n, "input_schema": {}} for n in definition.tools]

    with patch("app.llm.tools.mcp_client", api), patch("app.llm.runner.record_llm_usage"):
        result = await run_tool_loop(
            definition.prompt,
            [{"role": "user", "content": "Prépare la semaine du lundi prochain."}],
            offered,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            max_iterations=definition.max_iterations,
            source=SOURCE,
        )

    names = [name for name, _ in api.calls]
    assert names[:2] == ["get_training_status", "get_events_by_date"]
    assert names.count("get_next_prescription") == 6
    assert "manage_workouts" not in names
    assert "calibration" in result["response"].lower()


async def test_the_sport_line_hands_the_week_to_the_coach_without_naming_it():
    from app.personality.engine import PersonalityEngine

    with patch("app.personality.engine.personality_repo") as repo:
        repo.get = AsyncMock(return_value=None)
        prompt = await PersonalityEngine().get_system_prompt("user-1")

    line = next(line for line in prompt.splitlines() if line.startswith("SPORT"))
    assert "`coach`" in line and "manage_workouts" in line and "planned" in line and "strength" in line
    assert "jamais" in line

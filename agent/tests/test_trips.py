"""« Partir à l'heure » on the agent's side (MAG-657): what the morning card and the checks tell Maggie."""

from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.gateway import LLMGateway
from app.memory.agent_memory import MemoryBlocks
from app.queue.scheduler import daily_planning_prompt
from app.trips import (
    CHECK_SUMMARY_PREFIX,
    REPLAN_SUMMARY,
    check_summary,
    is_silent_trip_run,
    planning_trips_rules,
    trip_run_rules,
)


class TestTheDailyPlanningPrompt:
    prompt = daily_planning_prompt(date(2030, 11, 14))

    def test_the_origin_rules_come_in_order(self):
        order = [
            "originDecidedBy=user",
            "habitudes",
            "`position`",
            "`place:<id>` de la maison",
            "event:<id>",
        ]
        positions = [self.prompt.index(part) for part in order]

        assert positions == sorted(positions)

    def test_staying_home_needs_thirty_minutes_on_the_spot_after_the_round_trip(self):
        assert "au moins 30 minutes" in self.prompt

    def test_the_modes_are_estimated_then_the_choice_is_kept(self):
        assert self.prompt.index("estimate_trip") < self.prompt.index("c) Enregistre le choix avec set_trip")
        assert "decidedBy=user" in self.prompt

    def test_an_unknown_parking_is_asked_and_saved(self):
        assert "parkingKnown=false" in self.prompt
        assert self.prompt.index("parkingKnown=false") < self.prompt.index("save_place")

    def test_a_doubt_is_asked_with_the_departure_of_each_choice(self):
        assert "je repasse à la maison ou j'enchaîne" in self.prompt.lower()
        assert "Plus tard" in self.prompt

    def test_two_checks_are_posted_before_the_departure(self):
        assert "departAt moins 30 minutes et à departAt moins 10 minutes" in self.prompt
        assert f"« {CHECK_SUMMARY_PREFIX}<titre de l'événement> »" in self.prompt

    def test_the_proactions_of_the_instructions_are_still_only_those(self):
        assert "seulement celles-là" in self.prompt


class TestTheTripRuns:
    def test_a_check_and_a_replan_are_silent_runs_and_nothing_else_is(self):
        assert is_silent_trip_run(check_summary("Dentiste"))
        assert is_silent_trip_run(REPLAN_SUMMARY)
        assert not is_silent_trip_run("Check calendar")
        assert not is_silent_trip_run(None)

    @pytest.mark.parametrize("summary", [check_summary("Dentiste"), REPLAN_SUMMARY])
    def test_the_run_recomputes_from_the_real_position_and_keeps_a_move_over_two_minutes(self, summary):
        rules = trip_run_rules(summary)

        assert "estimate_trip" in rules and "`position`" in rules
        assert "plus de 2 minutes" in rules
        assert "set_trip" in rules
        assert "originDecidedBy=user" in rules

    @pytest.mark.parametrize("summary", [check_summary("Dentiste"), REPLAN_SUMMARY])
    def test_a_kept_origin_is_saved_again_as_the_user_s(self, summary):
        rules = trip_run_rules(summary)

        assert "decidedBy=user" in rules
        assert "place:<id>" in rules and "event:<id>" in rules

    def test_the_modes_are_named_as_the_api_spells_them(self):
        assert all(mode in planning_trips_rules("2030-11-14") for mode in ("drive", "transit", "walk", "bicycle"))

    def test_obsolete_checks_are_cancelled_then_posted_again(self):
        rules = trip_run_rules(check_summary("Dentiste"))

        assert rules.index("cancel_proaction") < rules.index("schedule_proaction")


def _gateway() -> LLMGateway:
    gateway = LLMGateway()
    gateway.client = MagicMock()
    gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
    gateway.agent_memory = MagicMock(blocks=AsyncMock(return_value=MemoryBlocks()))
    gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]))
    gateway._resolve_proaction_context = AsyncMock(return_value="ctx-1")
    return gateway


async def _run(summary: str | None) -> tuple[LLMGateway, dict, str]:
    gateway = _gateway()
    loop = AsyncMock(return_value={"response": "ok", "tool_calls": []})
    with (
        patch("app.llm.gateway.run_tool_loop", loop),
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
    ):
        contexts.find_active = AsyncMock(return_value=[])
        result = await gateway.proaction("Dentiste : recalcule le trajet.", "user-1", summary=summary)
    system = "\n".join(block["text"] for block in loop.await_args.args[0])
    return gateway, result, system


class TestTheGatewayOnATripCheck:
    async def test_it_gets_the_trip_preamble_and_no_thread(self):
        gateway, result, system = await _run(check_summary("Dentiste"))

        assert "mode proaction sur les trajets" in system
        assert "envoyé directement dans le chat" not in system
        assert "context_id" not in result
        gateway._resolve_proaction_context.assert_not_awaited()

    async def test_another_proaction_is_delivered_as_before(self):
        gateway, result, system = await _run("Rappel")

        assert "envoyé directement dans le chat" in system
        assert result["context_id"] == "ctx-1"

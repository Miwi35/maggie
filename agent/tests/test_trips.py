"""« Partir à l'heure » on the agent's side (MAG-657, MAG-730): what the morning card and the replan tell Maggie."""

from datetime import date
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.container import Container
from app.llm.gateway import LLMGateway
from app.memory.agent_memory import MemoryBlocks
from tests.prompt_helpers import system_prompt_with
from app.queue.scheduler import daily_planning_prompt
from app.trips import (
    REPLAN_SUMMARY,
    RETIRED_CHECK_SUMMARY_PREFIX,
    is_retired_trip_check,
    is_silent_trip_run,
    trip_run_rules,
)


SKILL = (Path(__file__).parent.parent / "app" / "skills" / "trajets.md").read_text()


class TestTheShippedTripsSkill:
    def test_it_is_a_planning_moment_skill(self):
        assert "name: trajets" in SKILL
        assert "moment:planification" in SKILL

    def test_the_origin_rules_come_in_order(self):
        order = [
            "originDecidedBy=user",
            "habitudes",
            "`position`",
            "`place:<id>` de la maison",
            "event:<id>",
        ]
        positions = [SKILL.index(part) for part in order]

        assert positions == sorted(positions)

    def test_staying_home_needs_thirty_minutes_on_the_spot_after_the_round_trip(self):
        assert "au moins 30 minutes" in SKILL

    def test_the_modes_are_estimated_then_the_choice_is_kept(self):
        assert SKILL.index("estimate_trip") < SKILL.index("`set_trip` (`eventId`, `mode`, `origin`)")
        assert all(mode in SKILL for mode in ("drive", "transit", "walk", "bicycle"))

    def test_the_car_left_elsewhere_is_said_with_car_at(self):
        assert "carAt=parked" in SKILL
        assert "carAt=origin" in SKILL

    def test_no_fixed_mode_rule(self):
        assert "3 km" not in SKILL
        assert "sous la pluie" not in SKILL

    def test_an_unknown_parking_is_asked_and_saved(self):
        assert "parkingKnown=false" in SKILL
        assert SKILL.index("parkingKnown=false") < SKILL.index("save_place")

    def test_a_doubt_is_asked_with_the_departure_of_each_choice(self):
        assert "je repasse à la maison ou j'enchaîne" in SKILL.lower()
        assert "Plus tard" in SKILL
        assert "Où partez-vous · <titre>" in SKILL

    def test_no_check_is_posted_before_the_departure_the_api_does_it(self):
        assert RETIRED_CHECK_SUMMARY_PREFIX not in SKILL
        assert "N'en programme aucune" in SKILL

    def test_a_stay_without_an_hour_is_asked_then_kept_with_arrive_at(self):
        assert "Arrivée · <titre>" in SKILL
        assert "Vers quelle heure arrivez-vous" in SKILL
        assert "« Pas de trajet »" in SKILL
        assert "« Autre heure »" in SKILL
        assert SKILL.index("Vers quelle heure arrivez-vous") < SKILL.index("`set_trip` avec `arriveAt`")
        assert "n'enregistre rien" in SKILL
        assert "ne la repose pas" in SKILL
        assert "origine `event:<id>` du séjour" in SKILL

    def test_the_return_of_a_stay_is_asked_then_kept_as_a_return_trip(self):
        assert "Retour · <titre>" in SKILL
        assert "À quelle heure repartez-vous" in SKILL
        assert SKILL.index("À quelle heure repartez-vous") < SKILL.index("`set_trip` avec `leg=return`")
        assert "pas de maison connue" in SKILL

    def test_the_stay_and_return_hours_are_no_fixed_list(self):
        assert "15 h" not in SKILL and "10 h, 11 h" not in SKILL

    def test_hours_are_in_the_users_timezone(self):
        assert "Paris" not in SKILL
        assert "date_time" in SKILL


class TestTheSkillInChat:
    section = SKILL.split("## En conversation", 1)[1].split("\n## ", 1)[0] if "## En conversation" in SKILL else ""

    def test_in_chat_the_trips_of_the_day_are_read_before_any_write(self):
        assert self.section
        assert "`list_trips`" in self.section
        assert self.section.index("`list_trips`") < self.section.index("`set_trip`")

    def test_set_trip_follows_an_estimate_compared_to_the_trip_in_place(self):
        assert self.section.index("`estimate_trip`") < self.section.index("`set_trip`", self.section.index("`estimate_trip`"))
        assert "remplace" in self.section

    def test_an_incoherent_result_is_never_saved(self):
        assert "n'enregistre jamais" in self.section
        assert "garde le trajet en place" in self.section

    def test_decided_by_user_only_for_an_origin_the_user_named(self):
        assert "`decidedBy=user`" in self.section
        assert "nommée" in self.section

    def test_a_display_remark_is_no_recompute(self):
        assert "affichage" in self.section
        assert "pas une demande de recalcul" in self.section

    def test_the_description_covers_the_conversation(self):
        assert "en conversation" in SKILL.split("---", 2)[1]


class TestThePlanningPrompt:
    prompt = daily_planning_prompt(date(2030, 11, 14))

    def test_it_starts_as_the_scenarios_expect(self):
        assert self.prompt.startswith("C'est le début de la journée du 2030-11-14.")

    def test_the_planning_prompt_sends_trips_to_the_skill(self):
        assert "compétence trajets" in self.prompt
        assert "get_skill" in self.prompt
        assert 'date="2030-11-14"' in self.prompt
        assert "ne programme aucun check" in self.prompt.lower()
        assert len(self.prompt) <= 1200

    def test_the_proactions_of_the_instructions_are_still_only_those(self):
        assert "seulement celles-là" in self.prompt

    def test_no_choice_rule_stays_in_the_code(self):
        for rule in ("bicycle", "3 km", "15 h", "Paris", "parkingKnown"):
            assert rule not in self.prompt


LEGACY_CHECK = f"{RETIRED_CHECK_SUMMARY_PREFIX}Dentiste"


class TestTheTripRuns:
    def test_a_replan_and_a_retired_check_are_silent_runs_and_nothing_else_is(self):
        assert is_silent_trip_run(LEGACY_CHECK)
        assert is_silent_trip_run(REPLAN_SUMMARY)
        assert is_retired_trip_check(LEGACY_CHECK)
        assert not is_retired_trip_check(REPLAN_SUMMARY)
        assert not is_silent_trip_run("Check calendar")
        assert not is_silent_trip_run(None)

    def test_the_run_recomputes_from_the_real_position_and_keeps_a_move_over_two_minutes(self):
        rules = trip_run_rules()

        assert "estimate_trip" in rules and "`position`" in rules
        assert "plus de 2 minutes" in rules
        assert "set_trip" in rules
        assert "originDecidedBy=user" in rules

    def test_a_kept_origin_is_saved_again_as_the_user_s(self):
        rules = trip_run_rules()

        assert "decidedBy=user" in rules
        assert "place:<id>" in rules and "event:<id>" in rules

    def test_the_replan_preamble_loads_the_skill_and_keeps_the_tolerance(self):
        rules = trip_run_rules()

        assert "compétence trajets" in rules and "get_skill" in rules
        assert "plus de 2 minutes" in rules
        assert "decidedBy=user" in rules

    def test_a_replan_only_treats_the_events_the_prompt_cites(self):
        rules = trip_run_rules()

        assert "seulement ceux-là" in rules
        assert "cité dans le prompt" in rules

    def test_the_run_no_longer_manages_checks(self):
        rules = trip_run_rules()

        assert "cancel_proaction" not in rules and "schedule_proaction" not in rules
        assert RETIRED_CHECK_SUMMARY_PREFIX not in rules


def _gateway() -> LLMGateway:
    gateway = Container().llm_gateway
    gateway.client = MagicMock()
    gateway.system_prompt = system_prompt_with(personality=MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie.")), agent_memory=MagicMock(blocks=AsyncMock(return_value=MemoryBlocks())))
    gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]))
    gateway._resolve_proaction_context = AsyncMock(return_value="ctx-1")
    return gateway


async def _run(summary: str | None) -> tuple[LLMGateway, dict, str]:
    gateway = _gateway()
    loop = AsyncMock(return_value={"response": "ok", "tool_calls": []})
    with (
        patch("app.llm.gateway.run_tool_loop", loop),
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.prompt_sources.behavior_directives_section", AsyncMock(return_value="")),
    ):
        contexts.find_active = AsyncMock(return_value=[])
        result = await gateway.proaction("Dentiste : recalcule le trajet.", "user-1", summary=summary)
    system = "\n".join(block["text"] for block in loop.await_args.args[0])
    return gateway, result, system


class TestTheGatewayOnAReplan:
    async def test_it_gets_the_trip_preamble_and_no_thread(self):
        gateway, result, system = await _run(REPLAN_SUMMARY)

        assert "mode proaction sur les trajets" in system
        assert "part tel quel dans le chat" not in system
        assert "context_id" not in result
        gateway._resolve_proaction_context.assert_not_awaited()

    async def test_another_proaction_is_delivered_as_before(self):
        gateway, result, system = await _run("Rappel")

        assert "part tel quel dans le chat" in system
        assert result["context_id"] == "ctx-1"

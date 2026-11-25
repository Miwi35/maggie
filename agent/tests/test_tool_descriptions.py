"""MAG-1013: each native tool says what its object is and which neighbour to prefer, with no conversation example."""

from unittest.mock import patch

import pytest

from app.agents.registry import SubagentDefinition, SubagentRegistry
from app.agents.tool import delegate_tool_definition
from app.native_tools import build_native_registry

EXPECTED: dict[str, list[str]] = {
    "add_instruction": [
        "a rule from the owner that lasts", "create_task with rrule", "create_skill", "memory_write_fiche",
        "list_instructions",
    ],
    "list_instructions": ["delete_instruction"],
    "delete_instruction": ["list_instructions", "cancel_proaction"],
    "add_bilan_consigne": ["Consignes step only", "never a grocery item"],
    "create_skill": [
        "a procedure Maggie follows", "add_instruction", "schedule_proaction", "memory_write_fiche",
        "list_skills", "update_skill",
    ],
    "list_skills": ["create_skill"],
    "update_skill": ["get_skill"],
    "delete_skill": ["delete_instruction"],
    "memory_search": ["not his app data", "memory_open"],
    "memory_open": ["Read-only", "memory_write_fiche"],
    "memory_write_fiche": [
        "Not for something he has to do", "add_instruction", "create_skill", "memory_search",
        "Refused outside a turn the owner started",
    ],
    "memory_log_miss": ["Log a recall miss", "memory_search", "It corrects no fact"],
    "ask_owner": ["Never for a clear request", "add no notification"],
    "answer_approval": ["approval card", "answer_task_reminder"],
    "send_voice_message": ["Only when he asks for a vocal", "manage_notifications"],
    "date_time": ["schedules nothing", "create_task", "schedule_proaction"],
    "get_coach_method": ["manage_workouts"],
}

EXPECTED_DELEGATE = ["ne parle pas au propriétaire"]

FORBIDDEN: dict[str, list[str]] = {
    name: ["concert", "le mardi", "acheter du pain", "tutoie-moi", "quand je demande un rappel"]
    for name in EXPECTED
}
FORBIDDEN.update({
    "memory_write_fiche": ["Françoise", "roses"],
    "memory_search": ["fête des mères"],
    "memory_log_miss": ["tu aurais dû", "Vous avez raison"],
    "ask_owner": ["je dois penser"],
    "send_voice_message": ["envoie-moi"],
    "date_time": ["Fort-de-France", "rappelle-moi"],
})


def _descriptions() -> dict[str, str]:
    return {tool["name"]: tool["description"] for tool in build_native_registry().definitions()}


def test_delegate_says_the_subagent_does_not_talk_to_the_owner():
    registry = SubagentRegistry(agents={"researcher": SubagentDefinition(
        name="researcher", description="Recherche", model="haiku", tools=["get_*"], prompt="x", max_iterations=3,
    )})
    with patch("app.agents.tool.subagent_registry", registry):
        description = delegate_tool_definition()["description"]
    for excerpt in EXPECTED_DELEGATE:
        assert excerpt in description


@pytest.mark.parametrize("name", EXPECTED)
def test_each_native_tool_says_what_its_object_is(name):
    description = _descriptions()[name]
    for excerpt in EXPECTED[name]:
        assert excerpt in description, f"{name} no longer says {excerpt!r}"


@pytest.mark.parametrize("name", FORBIDDEN)
def test_no_native_description_keeps_a_conversation_example(name):
    description = _descriptions()[name]
    for phrase in FORBIDDEN[name]:
        assert phrase not in description, f"{name} still quotes {phrase!r}"

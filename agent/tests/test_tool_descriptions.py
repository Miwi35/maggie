"""MAG-1013: each native tool says what its object is and which neighbour to prefer, with no conversation example."""

import pytest

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
}

FORBIDDEN: dict[str, list[str]] = {
    name: ["concert", "le mardi", "acheter du pain", "tutoie-moi", "quand je demande un rappel"]
    for name in EXPECTED
}


def _descriptions() -> dict[str, str]:
    return {tool["name"]: tool["description"] for tool in build_native_registry().definitions()}


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

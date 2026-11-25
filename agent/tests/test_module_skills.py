"""MAG-1012: the base gives a generic tool method; the usage of each tool lives in its description."""

import re
from unittest.mock import AsyncMock, patch

import pytest

from app.instruction_tools import INSTRUCTION_TOOLS
from app.personality.engine import PersonalityEngine
from app.skills.index import BUILTIN_SKILLS_DIR
from app.skills.tools import SKILL_TOOLS

RETIRED_SKILLS = ["agenda", "courses", "periodes", "sport", "taches", "trajectoire"]

FORBIDDEN_IN_THE_BASE = [
    "create_task", "update_task", "get_tasks", "get_events", "create_event", "manage_periods", "manage_meals",
    "manage_workouts", "add_grocery_item", "assign_product_store", "read_bank_statement",
    "import_bank_transactions", "get_activity_status", "orderScore", "SELON LE MODULE",
    "(tâches, agenda", "poubelle", "mardi sur deux", "malade", "férié",
]


@pytest.fixture
async def base() -> str:
    with patch("app.personality.engine.personality_repo") as mock_repo:
        mock_repo.get = AsyncMock(return_value=None)
        return await PersonalityEngine().get_system_prompt("user-1")


def _description(tools: list[dict], name: str) -> str:
    return next(tool["description"] for tool in tools if tool["name"] == name)


async def test_the_base_names_no_module_nor_lived_case(base):
    for name in FORBIDDEN_IN_THE_BASE:
        assert name not in base, name
    for skill in RETIRED_SKILLS:
        assert f"compétence {skill}" not in base, skill
    assert not re.search(r"\bgardes?\b", base)


async def test_the_base_gives_the_generic_tool_method(base):
    assert "choisis l'outil dont la description dit ce qu'est l'objet de la demande" in base
    assert "Fais d'abord ce qu'elle demande avant d'écrire" in base
    assert "charge-la avec get_skill au premier round" in base
    for kept in ("AUTORITÉ", "VÉRITÉ", "INTERNET :", "une demande claire s'exécute tout de suite"):
        assert kept in base
    assert "sans le nommer" in base


def test_no_module_skill_is_shipped():
    for name in RETIRED_SKILLS:
        assert not (BUILTIN_SKILLS_DIR / f"{name}.md").exists(), name


def test_get_skill_says_to_load_a_covering_skill_first():
    description = _description(SKILL_TOOLS, "get_skill")
    assert "premier round" in description and "suis-la avant d'écrire" in description


def test_add_instruction_sends_what_the_owner_does_to_a_task():
    description = _description(INSTRUCTION_TOOLS, "add_instruction")
    assert "task (create_task with rrule)" in description
    assert "le mardi" not in description


async def test_the_learning_systems_quote_no_conversation(base):
    block = base.split("Tes systèmes d'apprentissage")[1].split("OÙ ÇA SE VOIT")[0]
    for phrase in [
        "tutoie-moi", "moins d'emojis", "tous les matins à 8h", "chaque vendredi", "rappelle-moi demain à 10h",
        "envoie un résumé ce soir", "dans X minutes",
    ]:
        assert phrase not in block, phrase


async def test_the_memory_rules_name_no_one(base):
    for phrase in ["Françoise", "roses", "je te l'avais dit"]:
        assert phrase not in base, phrase

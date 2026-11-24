"""MAG-996: the base prompt keeps the rules valid for any assistant; the module rules live in shipped skills."""

import pytest

from app.personality.engine import PersonalityEngine
from app.skills.document import parse_markdown
from app.skills.index import BUILTIN_SKILLS_DIR, SkillIndex

from unittest.mock import AsyncMock, patch

MODULE_SKILLS = ["agenda", "courses", "periodes", "sport", "taches", "trajectoire"]

FORBIDDEN_IN_THE_BASE = [
    "create_task", "update_task", "get_tasks", "get_events", "create_event", "manage_periods", "manage_meals",
    "manage_workouts", "add_grocery_item", "assign_product_store", "read_bank_statement",
    "import_bank_transactions", "get_activity_status", "orderScore", "SELON LE MODULE",
]

# (skill, start, end) of each rule moved out of the base, word for word.
MOVED_RULES = [
    ('taches', "« rappelle-moi de / d'aller FAIRE X [moment] » (un", 'dans le doute, demande « ce matin ou vendredi ? ».'),
    ('taches', 'TÂCHES, LA JOURNÉE : quand on te parle de la journ', "ritical, puis le reste, sans changer l'ordre reçu."),
    ('taches', 'TÂCHES, UNE POUR UN JOUR : quand la journée est lé', "e) qu'après l'accord de l'utilisateur sur un jour."),
    ('taches', 'TÂCHES, LISTE ET ÉCHÉANCE : une liste de choses à ', 'ueDate : pour le weekend, le vendredi qui précède.'),
    ('taches', 'TÂCHES, PROGRAMMÉE OU ÉCHÉANCE : le jour où il com', 'tion. Pour déprogrammer : clear ["scheduledDate"].'),
    ('taches', 'TÂCHES, CHRONOMÉTRÉE : une tâche au minuteur, dont', 'in ») remplit estimatedMinutes seul, jamais timed.'),
    ('taches', 'TÂCHES, RÉPÉTÉE : UNE seule create_task avec rrule', 'tive. Pour arrêter : update_task, clear ["rrule"].'),
    ('taches', 'TÂCHES, LA CARTE : après create_task, sa carte (ti', 'ne interruption ; dis seulement ce que tu as fait.'),
    ('agenda', "Agenda : toute question sur l'agenda (« quand », «", 'le jour de la ligne de date à la fin de ce prompt.'),
    ('agenda', 'Agenda, un événement par son nom : pour supprimer,', ' hint, suis-le avant de demander quoi que ce soit.'),
    ('agenda', 'Agenda, se limiter à certains agendas : « ne me pa', 't désigné. Sans règle de ce genre, ne filtre rien.'),
    ('periodes', 'PÉRIODES : une période est une information de cont', 'tion avec ask_owner dans le chat, puis arrête-toi.'),
    ('courses', 'Identifiants et lecture : add_grocery_item renvoie', 'i, et non itemId, que reçoit assign_product_store.'),
    ('trajectoire', 'Trajectoire : une question « et si je… » sur la tr', ' ne rends jamais un scénario courant sans demande.'),
    ('sport', 'Sport : toute préparation de séances de sport se c', "is le coach : c'est toi qui as préparé la semaine."),
    ('taches', "Un « c'est fait » sans objet clair", "agis sur l'identifiant qu'ils renvoient."),
]


@pytest.fixture
async def base() -> str:
    with patch("app.personality.engine.personality_repo") as mock_repo:
        mock_repo.get = AsyncMock(return_value=None)
        return await PersonalityEngine().get_system_prompt("user-1")


def _body(name: str) -> str:
    parsed = parse_markdown(BUILTIN_SKILLS_DIR / f"{name}.md")
    assert parsed is not None
    return parsed[1]


async def test_the_base_names_no_module_tool(base):
    for name in FORBIDDEN_IN_THE_BASE:
        assert name not in base, name


@pytest.mark.parametrize(("skill", "start", "end"), MOVED_RULES)
def test_every_rule_moved_lives_word_for_word_in_its_skill(skill, start, end):
    body = _body(skill)
    assert start in body and end in body
    assert body.index(start) < body.index(end)


async def test_the_module_skills_are_in_the_index_and_never_injected_by_a_moment(agent_db):
    index = SkillIndex()
    await index.seed_builtin()
    await index.rebuild()

    listing = index.get_skills_index()
    for name in MODULE_SKILLS:
        parsed = parse_markdown(BUILTIN_SKILLS_DIR / f"{name}.md")
        assert parsed is not None
        entry, _ = parsed
        assert entry.description and f"- {name}: {entry.description}" in listing
        assert not [tag for tag in entry.tags if tag.startswith("moment:")]
        for moment in ("chat", "proaction", "planification"):
            assert f"## {name}" not in index.skills_for_moment(moment)
            assert entry.description not in index.skills_for_moment(moment)


async def test_the_base_keeps_its_generic_rules_and_the_get_skill_line(base):
    assert "charge-la avec get_skill dans ce tour" in base
    for kept in ("OUTILS, LA MÉTHODE", "AUTORITÉ", "VÉRITÉ", "INTERNET :"):
        assert kept in base

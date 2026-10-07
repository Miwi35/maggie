"""MAG-340: Maggie said « C'est noté » and stored nothing.

The owner asked « quand je demande un rappel, je veux une notification »; she answered
« C'est noté », then « j'ai enregistré cette préférence via mes directives », and no tool
had been called. The reproduction first, driven by the shipped fake-llm scenarios.
"""

from unittest.mock import AsyncMock, patch

import pytest

from app.db.skill_model import Skill
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.skills.index import SkillIndex

RULE = "Quand je demande un rappel, je veux une notification."
RECIPES = "Quand je te demande une recette, donne-moi les quantités pour deux."
TAUGHT = "C'est enregistré dans mes compétences : un rappel demandé arrivera en notification. Elle est visible dans Réglages › Agent › Compétences."

LEARNING_OFFERED = [
    {"name": name, "input_schema": {}}
    for name in (
        "date_time",
        "schedule_proaction",
        "store_memory",
        "search_memory",
        "add_instruction",
        "create_skill",
        "update_skill",
        "list_skills",
    )
]


@pytest.fixture()
def fake_client() -> FakeAnthropicClient:
    return FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)


@pytest.fixture()
def skills(agent_db):
    """The real skill index on the in-memory database: `create_skill` writes a row."""
    index = SkillIndex()
    index._publisher.publish = AsyncMock()
    with patch("app.llm.tools.skill_index", index):
        yield agent_db


async def run_turn(client, question: str) -> tuple[dict, list]:
    messages = [{"role": "user", "content": question}]
    with patch("app.llm.runner.record_llm_usage"):
        result = await run_tool_loop(
            "Tu es Maggie.",
            messages,
            LEARNING_OFFERED,
            client=client,
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
        )
    return result, messages


class TestTheReproduction:
    async def test_a_rule_announced_without_a_tool_is_stored_after_the_relaunch(self, fake_client, skills):
        result, _ = await run_turn(fake_client, RULE)

        assert [call["name"] for call in result["tool_calls"]] == ["create_skill"]
        with skills() as session:
            assert [skill.name for skill in session.query(Skill).all()] == ["rappel-avec-notification"]
        assert result["response"] == TAUGHT

    async def test_two_unbacked_announcements_end_on_the_truth(self, fake_client, skills):
        result, _ = await run_turn(fake_client, RECIPES)

        assert "noté" not in result["response"].lower()
        assert "retiens" not in result["response"].lower()
        with skills() as session:
            assert session.query(Skill).count() == 0

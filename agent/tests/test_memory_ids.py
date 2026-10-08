"""MAG-349: `update_memory(memory_id: 'julie-soiree-rennes', …)` — an identifier the model made up.

The call can only fail, and it must say so in words that send the model to the right
place; and an answer that then says « j'ai corrigé la mémoire » is a claim nothing backs.
"""

import json

import pytest
from sqlalchemy import select

from app.db.memory_model import Memory
from app.llm.claim_guard import ClaimGuard, Verdict, claims_learning
from app.llm.tools import MEMORY_TOOLS, ToolRouter

OWNER = "user-owner"
MEMORIES = [{"name": "store_memory", "input_schema": {}}, {"name": "update_memory", "input_schema": {}}]


async def _call(tool: str, arguments: dict) -> dict:
    return json.loads(await ToolRouter().call_tool(tool, arguments, user_id=OWNER))


def _stored(factory) -> list[Memory]:
    with factory() as session:
        return list(session.execute(select(Memory)).scalars())


class TestAnIdentifierTheModelMadeUp:
    @pytest.mark.parametrize("memory_id", ["julie-soiree-rennes", "mem-1", "0" * 32])
    async def test_update_memory_with_an_unknown_id_is_an_error_that_names_the_way_out(self, agent_db, memory_id):
        result = await _call("update_memory", {"memory_id": memory_id, "content": "Soirée demain à Rennes"})

        assert set(result) == {"error"}
        assert memory_id in result["error"]
        assert "search_memory" in result["error"]
        assert _stored(agent_db) == []

    async def test_an_unknown_id_leaves_the_real_memory_alone(self, agent_db):
        with agent_db() as session:
            memory = Memory(user_id=OWNER, content="Julie habite à Rennes")
            session.add(memory)
            session.commit()
            real_id = memory.id

        await _call("update_memory", {"memory_id": "julie-soiree-rennes", "content": "Soirée demain"})

        assert [m.content for m in _stored(agent_db)] == ["Julie habite à Rennes"]
        assert (await _call("update_memory", {"memory_id": real_id, "content": "Julie habite à Nantes"}))["id"] == real_id

    async def test_delete_memory_says_the_same(self, agent_db):
        result = await _call("delete_memory", {"memory_id": "julie-soiree-rennes"})

        assert "search_memory" in result["error"]

    def test_the_tool_says_where_the_id_comes_from(self):
        tool = next(t for t in MEMORY_TOOLS if t["name"] == "update_memory")

        assert "search_memory" in tool["description"]
        assert "never invent" in tool["description"].lower()
        assert "search_memory" in tool["input_schema"]["properties"]["memory_id"]["description"]


class TestNoCorrectionWithoutOne:
    @pytest.mark.parametrize(
        "text",
        [
            "J'ai corrigé la mémoire : Julie est ce soir à 19 h.",
            "Je corrige ma mémoire, c'est ce soir.",
            "J'ai mis à jour ton souvenir.",
            "Je mets à jour cette fiche.",
            "J'ai bien mis à jour ma mémoire.",
        ],
    )
    def test_a_correction_announced_is_a_claim(self, text):
        assert claims_learning(text)

    @pytest.mark.parametrize(
        "text",
        [
            "Je corrige : la réunion est à 9 h.",
            "Je corrige l'information : la réunion est à 9 h.",
            "Je me corrige, c'est jeudi.",
            "J'ai mis à jour la liste de courses ? Non.",
            "Je n'ai pas corrigé la mémoire.",
        ],
    )
    def test_a_correction_of_her_own_words_is_not(self, text):
        assert not claims_learning(text)

    def test_a_failed_update_does_not_back_it(self):
        guard = ClaimGuard(MEMORIES)
        guard.record("update_memory", json.dumps({"error": "Memory 'julie-soiree-rennes' not found"}))

        assert guard.review("J'ai corrigé la mémoire : Julie est ce soir à 19 h.") is Verdict.RETRY

    def test_a_successful_one_does(self):
        guard = ClaimGuard(MEMORIES)
        guard.record("update_memory", json.dumps({"id": "a" * 32, "content": "Julie est ce soir"}))

        assert guard.review("J'ai corrigé la mémoire : Julie est ce soir à 19 h.") is Verdict.ACCEPT

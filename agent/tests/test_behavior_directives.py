"""Behaviour preferences reaching the model's system prompt (MAG-22).

Before this, a directive was only ever read once a day by the proaction planning, so
« tutoie-moi » was stored and then ignored by every answer. These tests assert the
other half: that a `behavior` directive is in the blocks the gateways send, that a
`planning` one is not, and that it sits outside the cached prefix — a preference
stated mid-conversation has to hold on the very next message.
"""

from unittest.mock import AsyncMock, MagicMock, patch

from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.llm.directives import behavior_directives_section
from app.llm.gateway import LLMGateway
from app.llm.streaming import StreamingGateway

TUTOIE = "Tutoie-moi"
EMOJIS = "Moins d'emojis"
PLANNING_RULE = "Résume-moi la journée à 9h"


class TestBehaviorDirectivesSection:
    async def test_nothing_stored_is_an_empty_section(self, agent_db):
        assert await behavior_directives_section("user-1") == ""

    async def test_only_planning_rules_is_an_empty_section(self, agent_db):
        """The planning directives have their own reader; repeating them here is noise."""
        await instruction_repo.store("user-1", PLANNING_RULE, kind=InstructionKind.PLANNING)

        assert await behavior_directives_section("user-1") == ""

    async def test_behaviour_preferences_are_listed_oldest_first(self, agent_db):
        await instruction_repo.store("user-1", TUTOIE, kind=InstructionKind.BEHAVIOR)
        await instruction_repo.store("user-1", EMOJIS, kind=InstructionKind.BEHAVIOR)

        section = await behavior_directives_section("user-1")

        # The newest preference last: it is the line a model weighs most, and it is the
        # one that settles a contradiction with an older one.
        assert section.index(TUTOIE) < section.index(EMOJIS)

    async def test_another_user_preferences_stay_out(self, agent_db):
        await instruction_repo.store("user-2", TUTOIE, kind=InstructionKind.BEHAVIOR)

        assert await behavior_directives_section("user-1") == ""

    async def test_a_database_failure_costs_the_preferences_not_the_answer(self):
        with patch.object(instruction_repo, "find_by_user", AsyncMock(side_effect=RuntimeError("boom"))):
            assert await behavior_directives_section("user-1") == ""


def _streaming_gateway() -> StreamingGateway:
    gateway = StreamingGateway()
    gateway.personality = MagicMock()
    gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gateway.agent_memory = MagicMock()
    gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
    return gateway


def _llm_gateway() -> LLMGateway:
    gateway = LLMGateway()
    gateway.personality = MagicMock()
    gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
    gateway.agent_memory = MagicMock()
    gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
    return gateway


class TestChatSystemPrompt:
    """The streamed chat — the path every message the owner types goes through."""

    async def test_the_preference_reaches_the_system_prompt(self, agent_db):
        await instruction_repo.store("user-1", TUTOIE, kind=InstructionKind.BEHAVIOR)

        with (
            patch("app.llm.streaming.context_repo") as contexts,
            patch("app.llm.streaming.skill_index") as skills,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            blocks = await _streaming_gateway()._build_system_prompt("user-1")

        assert TUTOIE in blocks[1]["text"]

    async def test_a_planning_rule_does_not(self, agent_db):
        await instruction_repo.store("user-1", PLANNING_RULE, kind=InstructionKind.PLANNING)

        with (
            patch("app.llm.streaming.context_repo") as contexts,
            patch("app.llm.streaming.skill_index") as skills,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            blocks = await _streaming_gateway()._build_system_prompt("user-1")

        assert PLANNING_RULE not in "".join(block["text"] for block in blocks)

    async def test_it_stays_out_of_the_cached_prefix(self, agent_db):
        """Cached with the personality, « tutoie-moi » would only apply after the cache expired."""
        await instruction_repo.store("user-1", TUTOIE, kind=InstructionKind.BEHAVIOR)

        with (
            patch("app.llm.streaming.context_repo") as contexts,
            patch("app.llm.streaming.skill_index") as skills,
        ):
            contexts.find_active = AsyncMock(return_value=[])
            skills.get_skills_index.return_value = ""
            blocks = await _streaming_gateway()._build_system_prompt("user-1")

        assert blocks[0]["cache_control"] == {"type": "ephemeral"}
        assert TUTOIE not in blocks[0]["text"]


class TestProactionSystemPrompt:
    """A proaction lands in the same conversation, so it owes the same voice."""

    async def test_the_preference_reaches_an_executed_proaction(self, agent_db):
        await instruction_repo.store("user-1", TUTOIE, kind=InstructionKind.BEHAVIOR)

        with patch("app.llm.gateway.skill_index") as skills:
            skills.get_skills_index.return_value = ""
            blocks = await _llm_gateway()._build_system_prompt("user-1", preamble="\n\nTu es en mode proaction.")

        assert TUTOIE in blocks[1]["text"]
        assert "Tu es en mode proaction." in blocks[1]["text"]

    async def test_nothing_stored_leaves_the_prompt_as_it_was(self, agent_db):
        with patch("app.llm.gateway.skill_index") as skills:
            skills.get_skills_index.return_value = ""
            blocks = await _llm_gateway()._build_system_prompt("user-1")

        assert "Préférences de l'utilisateur" not in blocks[1]["text"]

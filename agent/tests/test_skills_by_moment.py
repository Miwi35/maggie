"""A skill learnt for a moment is put in full before Maggie at that moment (MAG-345).

7 Oct.: told « quand un rappel arrive, une notification push et rien dans le chat », Maggie
loaded the skill with get_skill three reminders out of five; the other two she only wrote in
the chat. The index alone was in the prompt, and the content only when the model thought of it.
"""

from unittest.mock import AsyncMock, MagicMock, patch

from app.db.skill_model import Skill
from app.llm.gateway import LLMGateway
from app.llm.prompt_cache import EPHEMERAL
from app.skills.index import MOMENT_SKILLS_MAX_CHARS, SkillIndex

DELIVER = Skill(
    name="delivrer-un-rappel",
    description="Quand un rappel arrive",
    tags=["rappel", "moment:proaction"],
    content="Envoie une notification push avec manage_notifications et n'écris rien dans le chat.",
)
SCHEDULE = Skill(
    name="programmer-un-rappel",
    description="Quand on me demande un rappel",
    tags=["rappel", "moment:chat"],
    content="Programme le rappel avec schedule_proaction puis cite l'heure locale.",
)
PLAN = Skill(
    name="planifier-la-journee",
    description="Chaque matin",
    tags=["moment:planification"],
    content="Commence la journée par l'agenda du jour.",
)
UNTAGGED = Skill(
    name="lien-de-concert",
    description="Quand on ajoute un concert",
    tags=["concert"],
    content="Cherche la page de l'événement et ajoute son lien.",
)


def _index(*skills: Skill) -> SkillIndex:
    repo = MagicMock()
    repo.list_all = AsyncMock(return_value=list(skills))
    return SkillIndex(repo=repo)


def _gateway() -> LLMGateway:
    gateway = LLMGateway()
    gateway.client = MagicMock()
    gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
    gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=""))
    gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]))
    gateway._resolve_proaction_context = AsyncMock(return_value=None)
    return gateway


async def _proaction_system(index: SkillIndex, *, silent: bool) -> list[dict]:
    """The system blocks a proaction run sends to the model."""
    loop = AsyncMock(return_value={"response": "", "tool_calls": []})
    with (
        patch("app.llm.gateway.skill_index", index),
        patch("app.llm.gateway.run_tool_loop", loop),
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
    ):
        contexts.find_active = AsyncMock(return_value=[])
        await _gateway().proaction("Rappel à délivrer : Appeler votre mère", "user-1", silent=silent)
    return loop.await_args.args[0]


def _text(blocks: list[dict]) -> str:
    return "\n".join(b["text"] for b in blocks)


class TestProaction:
    async def test_a_proaction_skill_is_in_the_prompt_in_full_without_get_skill(self):
        blocks = await _proaction_system(_index(DELIVER, UNTAGGED), silent=False)

        text = _text(blocks)
        assert "Compétences à appliquer maintenant" in text
        assert DELIVER.content in text

    async def test_a_skill_without_that_moment_is_only_in_the_index(self):
        blocks = await _proaction_system(_index(DELIVER, UNTAGGED, SCHEDULE), silent=False)

        text = _text(blocks)
        assert "- lien-de-concert: Quand on ajoute un concert" in text
        assert UNTAGGED.content not in text
        assert SCHEDULE.content not in text

    async def test_the_block_keeps_the_index_prefix_cached_and_the_volatile_part_last(self):
        """The index prefix stays the one chat uses, so a proaction does not write a cache of its own."""
        blocks = await _proaction_system(_index(DELIVER), silent=False)

        assert blocks[0]["cache_control"] == EPHEMERAL
        assert DELIVER.content not in blocks[0]["text"]
        assert "delivrer-un-rappel" in blocks[0]["text"]
        assert DELIVER.content in blocks[1]["text"]
        assert blocks[1]["cache_control"] == EPHEMERAL
        assert "Tu es en mode proaction." in blocks[-1]["text"]
        assert "cache_control" not in blocks[-1]

    async def test_no_skill_for_the_moment_leaves_the_prompt_as_it_was(self):
        blocks = await _proaction_system(_index(UNTAGGED), silent=False)

        assert len(blocks) == 2
        assert "Compétences à appliquer maintenant" not in _text(blocks)


class TestPlanning:
    async def test_the_planning_run_gets_the_planning_skills(self):
        blocks = await _proaction_system(_index(DELIVER, PLAN), silent=True)

        text = _text(blocks)
        assert PLAN.content in text
        assert DELIVER.content not in text


class TestChat:
    async def test_the_non_streamed_chat_gets_the_chat_skills(self):
        index = _index(DELIVER, SCHEDULE)
        loop = AsyncMock(return_value={"response": "C'est programmé.", "tool_calls": []})
        with (
            patch("app.llm.gateway.skill_index", index),
            patch("app.llm.gateway.run_tool_loop", loop),
            patch("app.llm.gateway.build_history", AsyncMock(return_value=[{"role": "user", "content": "x"}])),
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
        ):
            contexts.find_active = AsyncMock(return_value=[])
            await _gateway().chat("Rappelle-moi d'appeler ma mère à 20h", "user-1")

        text = _text(loop.await_args.args[0])
        assert SCHEDULE.content in text
        assert DELIVER.content not in text

    async def test_the_streamed_chat_gets_the_chat_skills(self):
        from app.llm.streaming import StreamingGateway

        index = _index(DELIVER, SCHEDULE)
        with (
            patch("app.llm.streaming.skill_index", index),
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.streaming.behavior_directives_section", AsyncMock(return_value="")),
        ):
            contexts.find_active = AsyncMock(return_value=[])
            gateway = StreamingGateway.__new__(StreamingGateway)
            gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
            gateway.agent_memory = MagicMock(get_memory_context=AsyncMock(return_value=""))
            blocks = await gateway._build_system_prompt("user-1")

        text = _text(blocks)
        assert SCHEDULE.content in text
        assert DELIVER.content not in text
        assert blocks[1]["cache_control"] == EPHEMERAL


class TestSizeCap:
    async def test_a_skill_past_the_cap_stays_in_the_index_only(self):
        huge = Skill(
            name="enorme",
            description="Quand un rappel arrive, en long",
            tags=["moment:proaction"],
            content="x" * (MOMENT_SKILLS_MAX_CHARS + 1),
        )
        blocks = await _proaction_system(_index(DELIVER, huge), silent=False)

        text = _text(blocks)
        assert "- enorme: Quand un rappel arrive, en long" in text
        assert huge.content not in text
        assert DELIVER.content in text

    async def test_the_block_never_exceeds_the_cap(self):
        many = [
            Skill(name=f"s{i:02d}", description="d", tags=["moment:chat"], content="y" * 1000) for i in range(30)
        ]
        index = _index(*many)
        await index.refresh()

        block = index.skills_for_moment("chat")

        assert 0 < len(block) <= MOMENT_SKILLS_MAX_CHARS


class TestEntries:
    async def test_a_skill_created_now_is_given_in_full_at_its_moment(self):
        index = _index()
        index.repo.upsert = AsyncMock(return_value=DELIVER)
        index._publish = AsyncMock()

        await index.create(DELIVER.name, DELIVER.description, list(DELIVER.tags), DELIVER.content, "user-1")

        assert DELIVER.content in index.skills_for_moment("proaction")

    async def test_the_moment_tags_stay_among_the_other_tags(self):
        index = _index(DELIVER)
        await index.refresh()

        assert index.list_all()[0].tags == ["rappel", "moment:proaction"]


class TestFakeLlmScenario:
    """85-proaction-skill-applied.yaml answers only once the skill's own text reaches the proaction."""

    E2E_SKILL = Skill(
        name="delivrer-un-rappel-e2e",
        description="Quand un rappel arrive",
        tags=["rappel", "moment:proaction"],
        content="Consigne e2e : une notification push et rien dans le chat.",
    )

    async def _deliver(self, skill: Skill) -> str:
        from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient

        gateway = _gateway()
        gateway.client = FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)
        with (
            patch("app.llm.gateway.skill_index", _index(skill)),
            patch("app.llm.contexts.context_repo") as contexts,
            patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
        ):
            contexts.find_active = AsyncMock(return_value=[])
            result = await gateway.proaction("Rappel e2e à délivrer : appeler votre mère", "user-1")
        return result["response"]

    async def test_the_scenario_needs_the_skill_given_in_full(self):
        assert "[fake-llm]" not in await self._deliver(self.E2E_SKILL)

    async def test_a_skill_left_in_the_index_does_not_reach_it(self):
        untagged = Skill(
            name=self.E2E_SKILL.name,
            description=self.E2E_SKILL.description,
            tags=["rappel"],
            content=self.E2E_SKILL.content,
        )

        assert "[fake-llm]" in await self._deliver(untagged)

"""MAG-340: Maggie said « C'est noté » and stored nothing.

The owner asked « quand je demande un rappel, je veux une notification »; she answered
« C'est noté », then « j'ai enregistré cette préférence via mes directives », and no tool
had been called. The reproduction first, driven by the shipped fake-llm scenarios, then
the guard alone, then the prompt and the tool description that tell the real model.
"""

import json
from unittest.mock import AsyncMock, patch

import pytest

from app.db.skill_model import Skill
from app.llm.claim_guard import (
    LEARNING_NUDGE,
    LEARNING_TOOLS,
    NOT_LEARNED_MESSAGE,
    NOT_SCHEDULED_MESSAGE,
    NUDGE,
    SCHEDULE_TOOL,
    ClaimGuard,
    Verdict,
    claims_learning,
)
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import SKILL_TOOLS, ToolRouter
from app.personality.engine import PersonalityEngine
from app.skills.index import SkillIndex
from tests.test_reminder_guard import stream_turn, the_bubble

RULE = "Quand je demande un rappel, je veux une notification."
RECIPES = "Quand je te demande une recette, donne-moi les quantités pour deux."
TAUGHT = "J'ai créé la compétence « Rappel avec notification » : un rappel demandé arrivera en notification. Elle est visible dans Réglages › Agent › Compétences."

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
    """`run_tool_loop`, the non-streamed path."""

    async def test_a_rule_announced_without_a_tool_is_stored_after_the_relaunch(self, fake_client, skills):
        result, _ = await run_turn(fake_client, RULE)

        assert [call["name"] for call in result["tool_calls"]] == ["create_skill"]
        with skills() as session:
            assert [skill.name for skill in session.query(Skill).all()] == ["rappel-avec-notification"]
        assert result["response"] == TAUGHT

    async def test_two_unbacked_announcements_end_on_the_truth(self, fake_client, skills):
        result, _ = await run_turn(fake_client, RECIPES)

        assert result["response"] == NOT_LEARNED_MESSAGE
        assert not claims_learning(result["response"])
        with skills() as session:
            assert session.query(Skill).count() == 0

    async def test_the_relaunch_names_the_tools_to_choose_from(self, fake_client, skills):
        _, messages = await run_turn(fake_client, RULE)

        assert messages[1]["role"] == "assistant"
        assert messages[2] == {"role": "user", "content": [{"type": "text", "text": LEARNING_NUDGE}]}


class TestTheStreamedPath:
    """`chat_stream`, what the web and the phone talk to."""

    async def test_a_rule_announced_without_a_tool_is_stored_after_the_relaunch(self, fake_client, skills):
        events, saved = await stream_turn(fake_client, RULE, LEARNING_OFFERED)

        assert [e["toolName"] for e in events if e["type"] == "TOOL_CALL_END"] == ["create_skill"]
        with skills() as session:
            assert [skill.name for skill in session.query(Skill).all()] == ["rappel-avec-notification"]
        assert [message["content"] for message in saved] == [TAUGHT]
        assert the_bubble(events) == TAUGHT

    async def test_two_unbacked_announcements_end_on_the_truth(self, fake_client, skills):
        events, saved = await stream_turn(fake_client, RECIPES, LEARNING_OFFERED)

        assert [message["content"] for message in saved] == [NOT_LEARNED_MESSAGE]
        assert the_bubble(events) == NOT_LEARNED_MESSAGE
        assert [event["type"] for event in events].count("TEXT_MESSAGE_END") == 1


NOTED = "C'est noté."
STORED = json.dumps({"id": "x-1", "content": "Rappel en notification"})


class TestTheClaim:
    @pytest.mark.parametrize(
        "text",
        [
            "C'est noté.",
            "C'est bien noté, je te tutoie.",
            "Je le note pour la prochaine fois.",
            "Je la noterai.",
            "Je m'en note pour la suite.",
            "J'en prends note.",
            "Je retiens que tu préfères les notifications.",
            "Je le retiendrai.",
            "Je m'en souviendrai.",
            "J'ai enregistré cette préférence via mes directives.",
            "J'ai bien noté ta préférence.",
            "C'est enregistré.",
            "C’est noté.",
            "C'EST NOTÉ !",
        ],
    )
    def test_an_announced_learning_is_a_claim(self, text):
        assert claims_learning(text)

    @pytest.mark.parametrize(
        "text",
        [
            "",
            NOT_LEARNED_MESSAGE,
            NOT_SCHEDULED_MESSAGE,
            "Je n'ai rien enregistré pour l'instant.",
            "Ce n'est pas noté.",
            "Je ne retiens rien de cette conversation.",
            "Je note que tu as trois rendez-vous demain.",
            "Veux-tu que je l'enregistre comme compétence ?",
            "Veux-tu que je le note ?",
            "Tu veux que je m'en note un ?",
            "Tu veux que je retienne ça ?",
            "J'ai retenu trois recettes pour la semaine.",
            "Tu as trois rendez-vous demain.",
        ],
    )
    def test_anything_else_is_not(self, text):
        assert not claims_learning(text)


class TestTheGuard:
    def test_a_claim_without_a_tool_is_sent_back_once_then_given_up(self):
        guard = ClaimGuard(LEARNING_OFFERED)

        assert guard.review(NOTED) is Verdict.RETRY
        assert guard.nudge()["content"] == [{"type": "text", "text": LEARNING_NUDGE}]
        assert guard.review(NOTED) is Verdict.GIVE_UP
        assert guard.honest_answer() == NOT_LEARNED_MESSAGE

    @pytest.mark.parametrize("tool", LEARNING_TOOLS)
    def test_each_learning_tool_backs_the_claim(self, tool):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record(tool, STORED, {"name": "s", "content": "c", "kind": "behavior"})

        assert guard.review(NOTED) is Verdict.ACCEPT

    def test_a_scheduled_reminder_backs_its_cest_note(self):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record(SCHEDULE_TOOL, json.dumps({"id": "p-1", "status": "pending", "error": None}))

        assert guard.review("C'est noté, je te rappellerai de boire de l'eau à 16h05.") is Verdict.ACCEPT

    @pytest.mark.parametrize(
        ("tool", "answer"),
        [
            ("create_skill", "J'ai créé la compétence « Rappels par notification »."),
            ("add_instruction", "J'ai ajouté une directive : je te tutoie."),
            ("store_memory", "J'ai enregistré dans ma mémoire ton allergie aux fruits à coque."),
        ],
    )
    def test_an_explicit_confirmation_after_the_tool_passes(self, tool, answer):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record(tool, STORED)

        assert guard.review(answer) is Verdict.ACCEPT
        assert not guard.nudged

    def test_a_pending_reminder_listed_backs_its_cest_note(self):
        # « Tu me rappelles bien ? »: the reminder was scheduled in an earlier turn. A learning
        # nudge here would offer schedule_proaction and book it a second time.
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record("list_proactions", json.dumps([{"id": "pro-1", "status": "pending"}]))

        assert guard.review("Oui, c'est noté, je vous rappellerai à 16h05.") is Verdict.ACCEPT

    def test_another_write_backs_its_cest_note(self):
        # « C'est noté : dentiste le 12 mars » after create_event is true; a relaunch would book it twice.
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record("create_event", json.dumps({"event": {"id": "e-1"}}), {"title": "Dentiste"})

        assert guard.review("C'est noté : dentiste le 12 mars à 14h.") is Verdict.ACCEPT

    def test_a_held_call_backs_the_claim(self):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record("add_instruction", json.dumps({"status": "pending_approval", "approval_id": "a-1"}))

        assert guard.review(NOTED) is Verdict.ACCEPT

    def test_an_empty_error_field_is_not_a_failure(self):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record("store_memory", json.dumps({"id": "m-1", "error": None}))

        assert guard.review(NOTED) is Verdict.ACCEPT

    @pytest.mark.parametrize("result", ['{"error": "content is required"}', "not json"])
    @pytest.mark.parametrize("tool", LEARNING_TOOLS)
    def test_a_failed_tool_does_not_count(self, tool, result):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record(tool, result)

        assert guard.review(NOTED) is Verdict.RETRY

    @pytest.mark.parametrize("tool", ["search_memory", "list_skills", "date_time", "get_upcoming_events"])
    def test_a_read_does_not_count(self, tool):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.record(tool, json.dumps({"result": "ok"}))

        assert guard.review(NOTED) is Verdict.RETRY

    def test_a_reminder_claim_is_named_before_its_cest_note(self):
        # « C'est noté, je vous rappellerai » with nothing scheduled is about the reminder.
        guard = ClaimGuard(LEARNING_OFFERED)
        claim = "C'est noté, je vous rappellerai à 16h05."

        assert guard.review(claim) is Verdict.RETRY
        assert guard.nudge()["content"] == [{"type": "text", "text": NUDGE}]
        assert guard.review(claim) is Verdict.GIVE_UP
        assert guard.honest_answer() == NOT_SCHEDULED_MESSAGE

    def test_a_turn_not_offered_a_learning_tool_is_left_alone(self):
        assert ClaimGuard(None).review(NOTED) is Verdict.ACCEPT
        assert ClaimGuard([{"name": SCHEDULE_TOOL}]).review(NOTED) is Verdict.ACCEPT

    def test_silence_after_the_relaunch_is_a_failure(self):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.review(NOTED)

        assert guard.review(" ") is Verdict.GIVE_UP
        assert guard.honest_answer() == NOT_LEARNED_MESSAGE

    def test_silence_once_stored_is_fine(self):
        guard = ClaimGuard(LEARNING_OFFERED)
        guard.review(NOTED)
        guard.record("create_skill", STORED)

        assert guard.review("") is Verdict.ACCEPT


async def shipped_prompt() -> str:
    with patch("app.personality.engine.personality_repo") as repo:
        repo.get = AsyncMock(return_value=None)
        return await PersonalityEngine().get_system_prompt("user-1")


class TestWhatTheModelIsTold:
    async def test_the_prompt_has_one_place_for_each_thing_learned(self):
        prompt = await shipped_prompt()

        assert "« quand je te demande X, fais Y »" in prompt
        for marker in ("create_skill", 'kind="behavior"', 'kind="planning"', "schedule_proaction", "store_memory"):
            assert marker in prompt
        assert "une demande générale sur ta façon d'agir est une compétence" in prompt.lower()

    async def test_the_prompt_says_where_the_user_sees_it(self):
        prompt = await shipped_prompt()

        assert "Réglages › Agent" in prompt
        for tab in ("Instructions", "Compétences", "Proactions"):
            assert tab in prompt
        assert "ne dis jamais qu'aucun endroit" in prompt.lower()

    async def test_the_prompt_asks_for_a_confirmation_that_names_the_action(self):
        prompt = await shipped_prompt()

        assert "une confirmation DIT L'ACTION FAITE" in prompt
        assert "« J'ai créé la compétence « Rappels par notification » »" in prompt
        assert "« C'est noté » seul n'est pas une confirmation" in prompt

    def test_create_skill_carries_the_owners_rule(self):
        description = next(tool["description"] for tool in SKILL_TOOLS if tool["name"] == "create_skill")

        assert "when I ask you X, do Y" in description
        assert "a general request about how Maggie should act is a skill" in description

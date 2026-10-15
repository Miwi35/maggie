"""The recall at four levels (MAG-16): the cartes scored at each turn, bounded, and the profile that stays."""

import asyncio
from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.config import settings
from app.db.memory_index_repository import CardView
from app.memory import recall as recall_module
from app.memory.agent_memory import AgentMemory, render_profile, render_recalled
from app.memory.recall import rank, recall_for_turn, resolve_days
from app.metrics import MEMORY_RECALL_OUTCOMES
from tests.memory.conftest import USER_A, USER_B

TODAY = date(2026, 10, 13)


def carte(
    body: str = "Texte.",
    *,
    resume: str = "",
    evoque: list[str] | None = None,
    alias: list[str] | None = None,
    personnes: list[str] | None = None,
    moments: list[str] | None = None,
) -> str:
    lines = ["---"]
    if resume:
        lines.append(f"resume: {resume}")
    for name, values in (("moments", moments), ("personnes", personnes), ("evoque", evoque), ("alias", alias)):
        if values:
            lines.append(f"{name}: [" + ", ".join(values) + "]")
    return "\n".join([*lines, "---", "", body]) + "\n"


@pytest.fixture()
def memory(env):
    with (
        patch("app.memory.agent_memory.memory_service", env.service),
        patch("app.memory.recall.memory_service", env.service),
    ):
        yield AgentMemory()


async def paths_recalled(text: str, user_id: str = USER_A, today: date = TODAY) -> list[str]:
    return [r.path for r in await recall_module.recall(user_id, text, today)]


class TestLevelTwoFinds:
    async def test_a_bouquet_for_mothers_day_surfaces_the_roses_fiche(self, env):
        await env.service.write(
            USER_A,
            "Famille/Françoise n'aime pas les roses.md",
            carte(resume="Françoise n'aime pas les roses", evoque=["fête des mères", "offrir des fleurs"]),
        )
        await env.service.write(USER_A, "Cuisine/Le gratin se fait au four.md", carte(resume="gratin"))

        assert await paths_recalled("Un bouquet pour la fête des mères") == ["Famille/Françoise n'aime pas les roses.md"]

    async def test_a_hand_dropped_fiche_answers_who_is_francoise(self, env):
        await env.service.write(
            USER_A,
            "Famille/Françoise aime les pivoines.md",
            carte("Françoise aime les tulipes.", resume="Françoise aime les tulipes"),
        )

        found = await recall_module.recall(USER_A, "Que sais-tu sur Françoise ?", TODAY)

        assert [r.path for r in found] == ["Famille/Françoise aime les pivoines.md"]
        assert found[0].resume == "Françoise aime les tulipes"

    async def test_a_dictated_lubus_surfaces_the_ubu_through_its_alias(self, env):
        await env.service.write(
            USER_A, "Lieux/Concerts à l'UBU.md", carte(resume="Salle de concert à Rennes", alias=["l'UBU"])
        )
        await env.service.write(USER_A, "Lieux/Le marché du samedi.md", carte(resume="Marché"))

        assert await paths_recalled("Réserve deux places à Lubus samedi") == ["Lieux/Concerts à l'UBU.md"]

    async def test_the_usual_hour_comes_up_before_acting(self, env):
        await env.service.write(
            USER_A,
            "Habitudes/Les concerts commencent à 19h.md",
            carte(resume="Heure habituelle des concerts, 19h", evoque=["concert", "réserver une sortie"]),
        )

        found = await recall_module.recall(USER_A, "Ajoute le concert de samedi à l'agenda", TODAY)

        assert [r.path for r in found] == ["Habitudes/Les concerts commencent à 19h.md"]
        assert "19h" in render_recalled(found)

    async def test_interesting_concerts_are_oriented_by_the_musical_taste_never_elsewhere(self, env):
        await env.service.write(
            USER_A,
            "Goûts/Meven aime le rock et le metal.md",
            carte(resume="Rock, metal, scène locale", evoque=["concert", "sortie musicale"]),
        )
        await env.service.write(
            USER_A, "Famille/Françoise n'aime pas les roses.md", carte(resume="Roses", evoque=["fête des mères"])
        )

        assert await paths_recalled("Concerts intéressants bientôt ?") == ["Goûts/Meven aime le rock et le metal.md"]

    async def test_a_day_a_person_or_a_place_opens_the_matching_fiche(self, env):
        await env.service.write(
            USER_A,
            "Agenda/Dîner chez Paul.md",
            carte(resume="Dîner", personnes=["Paul"], moments=["2026-10-14 19h — dîner chez Paul"]),
        )
        await env.service.write(USER_A, "Agenda/Le dentiste.md", carte(resume="Dentiste", moments=["2026-11-02 10h"]))

        assert await paths_recalled("Qu'est-ce que j'ai demain ?") == ["Agenda/Dîner chez Paul.md"]
        assert await paths_recalled("Que fait Paul ?") == ["Agenda/Dîner chez Paul.md"]
        assert await paths_recalled("Où est mon rendez-vous du 2 novembre ?") == ["Agenda/Le dentiste.md"]

    async def test_a_message_with_nothing_to_remember_brings_nothing_back(self, env):
        await env.service.write(
            USER_A, "Famille/Françoise n'aime pas les roses.md", carte(resume="Roses", evoque=["fête des mères"])
        )

        assert await paths_recalled("Merci, c'est parfait !") == []
        assert await paths_recalled("   ") == []


class TestLevelTwoBounds:
    async def test_at_most_three_memories_come_back(self, env):
        for index in range(5):
            await env.service.write(
                USER_A, f"Famille/Cadeau {index}.md", carte(resume=f"Idée {index}", evoque=["fête des mères"])
            )

        assert len(await paths_recalled("Un bouquet pour la fête des mères")) == 3

    async def test_a_weak_resemblance_stays_under_the_threshold(self, env):
        await env.service.write(USER_A, "Cuisine/Le gratin.md", carte(resume="Un gratin pour la fête des voisins"))

        assert await paths_recalled("Un bouquet pour la fête des mères") == []

    async def test_a_conversation_weighs_less_than_a_fiche(self, env):
        await env.service.write(
            USER_A,
            "Conversations/2026/10/2026-10-04 18h14 — Bouquet fête des mères.md",
            "# Bouquet\n",
            conversation=True,
        )
        await env.service.write(
            USER_A, "Famille/Cadeau de fête des mères.md", carte(resume="Idée", evoque=["bouquet fête des mères"])
        )

        found = await paths_recalled("Un bouquet pour la fête des mères")

        assert found[0] == "Famille/Cadeau de fête des mères.md"

    async def test_the_thread_being_spoken_in_does_not_come_back_for_itself(self, env, memory):
        own = "Conversations/2026/10/2026-10-13 09h30 — Bouquet fête des mères.md"
        await env.service.write(USER_A, own, "# Bouquet\n", conversation=True)
        text = "Un bouquet pour la fête des mères"

        assert own in [r.path for r in await recall_for_turn(USER_A, text, TODAY, source="chat")]
        with patch("app.memory.recall.conversations.find_file", return_value=own):
            assert await recall_for_turn(USER_A, text, TODAY, source="chat", context_id="ctx-1") == []

    async def test_one_user_never_recalls_the_memory_of_another(self, env):
        await env.service.write(
            USER_B, "Famille/Françoise n'aime pas les roses.md", carte(resume="Roses", evoque=["fête des mères"])
        )

        assert await paths_recalled("Un bouquet pour la fête des mères", USER_A) == []
        assert await paths_recalled("Un bouquet pour la fête des mères", USER_B) != []

    async def test_a_user_id_that_is_not_a_ulid_is_refused(self, env):
        with pytest.raises(ValueError):
            await recall_module.recall("../other", "bouquet", TODAY)


class TestLevelTwoDelay:
    @staticmethod
    def outcome(source: str, outcome: str) -> float:
        return MEMORY_RECALL_OUTCOMES.labels(source=source, outcome=outcome)._value.get()

    async def test_past_its_delay_the_turn_goes_on_without_the_recall(self, env, memory, monkeypatch):
        async def slow(*args, **kwargs):
            await asyncio.sleep(5)

        monkeypatch.setattr(recall_module, "recall", slow)
        monkeypatch.setattr(settings, "memory_recall_timeout_seconds", 0.05)
        before = self.outcome("chat", "timeout")

        started = asyncio.get_running_loop().time()
        found = await recall_for_turn(USER_A, "un bouquet", TODAY, source="chat")

        assert found == []
        assert asyncio.get_running_loop().time() - started < 1
        assert self.outcome("chat", "timeout") == before + 1

    async def test_a_failure_of_the_recall_never_fails_the_turn(self, env, memory, monkeypatch):
        monkeypatch.setattr(recall_module, "recall", AsyncMock(side_effect=RuntimeError("boom")))
        before = self.outcome("proaction", "error")

        assert await recall_for_turn(USER_A, "un bouquet", TODAY, source="proaction") == []
        assert self.outcome("proaction", "error") == before + 1

    async def test_the_outcome_and_the_time_are_measured(self, env, memory):
        await env.service.write(
            USER_A, "Famille/Françoise n'aime pas les roses.md", carte(resume="Roses", evoque=["fête des mères"])
        )
        before = self.outcome("planning", "recalled")

        await recall_for_turn(USER_A, "Un bouquet pour la fête des mères", TODAY, source="planning")

        assert self.outcome("planning", "recalled") == before + 1

    async def test_memory_off_costs_nothing(self, env, memory, monkeypatch):
        monkeypatch.setattr(type(env.service), "enabled", property(lambda self: False))

        assert await recall_for_turn(USER_A, "un bouquet", TODAY, source="chat") == []


class TestDays:
    def test_relative_and_written_days(self):
        assert resolve_days("demain", TODAY) == {"2026-10-14"}
        assert resolve_days("après demain", TODAY) == {"2026-10-15"}
        assert resolve_days("vendredi", TODAY) == {"2026-10-16"}
        assert resolve_days("le 2 novembre", TODAY) == {"2026-11-02"}
        assert resolve_days("le 1er décembre 2027", TODAY) == {"2027-12-01"}
        assert resolve_days("rendez-vous le 2026-11-05", TODAY) == {"2026-11-05"}

    def test_an_impossible_date_is_ignored(self):
        assert resolve_days("le 31 février", TODAY) == set()


class TestProfile:
    @staticmethod
    def view(path: str, kind: str = "fiche", resume: str = "") -> CardView:
        return CardView(path, kind, f"id-{path}", path, resume, [], [], [], [])

    def test_the_profile_lists_the_fiches_and_leaves_the_conversations_out(self):
        text = render_profile(
            [self.view("Famille/Roses.md", resume="Françoise n'aime pas les roses"), self.view("Conv.md", "conversation")],
            {},
            80,
        )

        assert "- Famille/Roses.md — Françoise n'aime pas les roses" in text
        assert "Conv.md" not in text

    def test_no_fiche_no_profile(self):
        assert render_profile([self.view("Conv.md", "conversation")], {}, 80) == ""

    def test_the_most_used_fiches_stay_and_the_rest_is_counted(self):
        cards = [self.view(f"F/{n}.md") for n in "abcd"]

        text = render_profile(cards, {"id-F/c.md": 5, "id-F/d.md": 2}, 2)

        assert "F/c.md" in text and "F/d.md" in text
        assert "F/a.md" not in text
        assert "… et 2 autres : memory_search" in text

    def test_the_text_does_not_depend_on_the_order_of_the_cards(self):
        cards = [self.view(f"F/{n}.md", resume=n) for n in "abc"]

        assert render_profile(cards, {}, 80) == render_profile(list(reversed(cards)), {}, 80)

    async def test_the_prompt_holds_the_profile_and_at_most_three_recalled_memories(self, env, memory):
        for index in range(5):
            await env.service.write(
                USER_A, f"Famille/Cadeau {index}.md", carte(resume=f"Idée {index}", evoque=["fête des mères"])
            )

        blocks = await memory.blocks(USER_A, "Un bouquet pour la fête des mères", today=TODAY)

        assert all(f"Famille/Cadeau {n}.md" in blocks.profile for n in range(5))
        assert blocks.recalled.count("\n- ") == 3
        assert "Famille/Cadeau" not in blocks.notice

    async def test_opening_a_fiche_does_not_rewrite_the_profile_text_of_a_small_folder(self, env, memory):
        await env.service.write(USER_A, "Famille/Roses.md", carte(resume="Roses"))
        first = (await memory.blocks(USER_A, "", today=TODAY)).profile

        await env.service.write(USER_A, "Famille/Roses.md", carte(resume="Roses"))
        second = (await memory.blocks(USER_A, "", today=TODAY)).profile

        assert first == second != ""

    async def test_a_message_that_calls_nothing_back_adds_no_recalled_block(self, env, memory):
        await env.service.write(USER_A, "Famille/Roses.md", carte(resume="Roses", evoque=["fête des mères"]))

        blocks = await memory.blocks(USER_A, "Merci !", today=TODAY)

        assert blocks.recalled == ""
        assert "Famille/Roses.md" in blocks.profile


def test_rank_is_pure_and_orders_by_score_then_path():
    cards = [
        CardView("B.md", "fiche", "b", "B", "", [], [], ["fête des mères"], []),
        CardView("A.md", "fiche", "a", "A", "", [], [], ["fête des mères"], []),
    ]

    assert [r.path for r in rank(cards, "la fête des mères", TODAY)] == ["A.md", "B.md"]

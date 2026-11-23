"""The planned recall of a written chat turn (MAG-973): a light model plans, the code counts what was said."""

import asyncio
import json
from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.config import settings
from app.container import container
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.models import RECALL_PLAN, model_for
from app.memory import recall_plan as module
from app.memory.agent_memory import AgentMemory
from app.memory.recall_plan import (
    NO_PLAN,
    RecallPlan,
    parse_plan,
    plan_recall,
    planned_recall,
    render_said,
    said_about,
)
from app.metrics import MEMORY_RECALL_OUTCOMES
from app.prompt_sources import MaggiePromptSources
from app.runtime.system_prompt import SystemPrompt
from tests.memory.conftest import USER_A, USER_B

TODAY = date(2026, 11, 23)
ASKED = "Combien de fois ce mois-ci ai-je commandé des pizzas ?"
AGGREGATE = RecallPlan("agreger", ("pizza",), date(2026, 11, 1), TODAY)


def message(heure: str, who: str, text: str) -> str:
    return f"## {heure} — {who}\n\n{text}\n\n"


def client_answering(text: str):
    response = SimpleNamespace(
        content=[SimpleNamespace(text=text)], usage=SimpleNamespace(input_tokens=1, output_tokens=1)
    )
    client = AsyncMock()
    client.messages.create = AsyncMock(return_value=response)
    return client


@pytest.fixture(autouse=True)
def passthrough_client():
    with patch("app.memory.recall_plan.client_for", new=AsyncMock(side_effect=lambda _u, c: c)):
        yield


@pytest.fixture()
def memory(env):
    with (
        patch("app.memory.agent_memory.memory_service", env.service),
        patch("app.memory.recall.memory_service", env.service),
        patch("app.memory.recall_plan.memory_service", env.service),
    ):
        yield AgentMemory()


async def keep(env, rel: str, text: str, user_id: str = USER_A, *, conversation: bool = True) -> None:
    await env.service.write(user_id, rel, text, conversation=conversation)


async def pizza_month(env) -> None:
    await keep(
        env,
        "Conversations/2026/11/2026-11-03 19h05 — Repas.md",
        message("2026-11-03 19h05", "Propriétaire", "J'ai commandé une pizza ce soir")
        + message("2026-11-03 19h06", "Maggie", "Bon appétit, une pizza pour la soirée !")
        + message("2026-11-10 12h00", "Propriétaire", "Pizzas au bureau, comme d'habitude"),
    )
    await keep(
        env,
        "Conversations/2026/11/2026-11-20 19h00 — Vendredi.md",
        message("2026-11-20 19h05", "Propriétaire", "PIZZA encore ce soir")
        + message("2026-11-23 08h00", "Propriétaire", ASKED),
    )
    await keep(
        env,
        "Conversations/2026/10/2026-10-12 19h00 — Octobre.md",
        message("2026-10-12 19h05", "Propriétaire", "Une pizza le mois dernier"),
    )
    await keep(env, "Cuisine/Pizza.md", "---\nresume: pizza\n---\n\nLa pizza du dimanche.\n", conversation=False)


class TestThePlan:
    async def test_a_count_question_is_planned_as_an_aggregate(self):
        client = client_answering(
            '{"plan": "agreger", "termes": ["pizza"], "debut": "2026-11-01", "fin": "2026-11-23"}'
        )

        assert await plan_recall(client, USER_A, ASKED, TODAY) == AGGREGATE

    async def test_the_plan_speaks_to_its_run_type_model(self):
        client = client_answering('{"plan": "aucun"}')

        await plan_recall(client, USER_A, ASKED, TODAY)

        assert client.messages.create.await_args.kwargs["model"] == model_for(RECALL_PLAN)
        assert client.messages.create.await_count == 1

    async def test_the_plan_is_given_today_and_the_demand(self):
        client = client_answering('{"plan": "aucun"}')

        await plan_recall(client, USER_A, ASKED, TODAY)

        kwargs = client.messages.create.await_args.kwargs
        assert kwargs["system"].startswith("Tu prépares le rappel de la mémoire de Maggie")
        assert kwargs["messages"] == [{"role": "user", "content": f"Aujourd'hui : 2026-11-23.\nDemande : « {ASKED} »"}]

    async def test_a_fenced_answer_is_read(self):
        raw = '```json\n{"plan": "retrouver", "termes": ["salle"], "debut": null, "fin": null}\n```'

        assert parse_plan(raw) == RecallPlan("retrouver", ("salle",), None, None)

    @pytest.mark.parametrize(
        "raw",
        [
            "pas du json",
            "[]",
            '{"plan": "agreger", "termes": ["pizza"], "debut": null}',
            '{"plan": "agreger", "termes": ["pizza"], "fin": null}',
            '{"plan": "agreger", "debut": null, "fin": null}',
            '{"plan": "agreger", "termes": [], "debut": null, "fin": null}',
            '{"plan": "agreger", "termes": ["a", "b", "c", "d", "e", "f"], "debut": null, "fin": null}',
            '{"plan": "agreger", "termes": [""], "debut": null, "fin": null}',
            '{"plan": "agreger", "termes": [1], "debut": null, "fin": null}',
            '{"plan": "agreger", "termes": ["pizza"], "debut": "2026-11-23", "fin": "2026-11-01"}',
            '{"plan": "agreger", "termes": ["pizza"], "debut": "demain", "fin": null}',
            '{"plan": "personnaliser", "termes": ["pizza"], "debut": null, "fin": null}',
        ],
    )
    def test_an_invalid_plan_is_no_plan(self, raw):
        assert parse_plan(raw) == NO_PLAN

    async def test_no_client_is_no_plan(self):
        with patch("app.memory.recall_plan.client_for", new=AsyncMock(return_value=None)):
            assert await plan_recall(None, USER_A, ASKED, TODAY) == NO_PLAN

    async def test_a_failing_model_is_no_plan(self):
        client = AsyncMock()
        client.messages.create = AsyncMock(side_effect=RuntimeError("boom"))

        assert await plan_recall(client, USER_A, ASKED, TODAY) == NO_PLAN


class TestTheCount:
    async def test_the_messages_saying_a_term_in_the_period_are_counted_by_speaker(self, env, memory):
        await pizza_month(env)

        found = await said_about(USER_A, AGGREGATE, asked=ASKED)

        assert [(m.heure, m.role) for m in found.messages] == [
            ("2026-11-20 19h05", "Propriétaire"),
            ("2026-11-10 12h00", "Propriétaire"),
            ("2026-11-03 19h06", "Maggie"),
            ("2026-11-03 19h05", "Propriétaire"),
        ]

    async def test_a_message_outside_the_period_is_not_counted(self, env, memory):
        await pizza_month(env)

        found = await said_about(USER_A, AGGREGATE, asked=ASKED)

        assert not any(m.heure.startswith("2026-10") for m in found.messages)

    async def test_an_open_period_counts_every_message(self, env, memory):
        await pizza_month(env)

        found = await said_about(USER_A, RecallPlan("agreger", ("pizza",), None, None), asked=ASKED)

        assert any(m.heure.startswith("2026-10") for m in found.messages)

    async def test_the_demand_being_asked_is_not_counted(self, env, memory):
        await pizza_month(env)

        found = await said_about(USER_A, AGGREGATE, asked=ASKED)

        assert all(m.content != ASKED for m in found.messages)

    async def test_accents_case_and_plural_do_not_matter(self, env, memory):
        await keep(
            env,
            "Conversations/2026/11/2026-11-05 10h00 — Goûter.md",
            message("2026-11-05 10h00", "Propriétaire", "Des Crêpes ce matin")
            + message("2026-11-05 10h01", "Propriétaire", "La pâte à crepe est prête"),
        )

        found = await said_about(USER_A, RecallPlan("agreger", ("crepe",), None, None), asked="x")

        assert [m.content for m in found.messages] == ["La pâte à crepe est prête", "Des Crêpes ce matin"]

    async def test_fiches_are_not_counted(self, env, memory):
        await pizza_month(env)

        found = await said_about(USER_A, AGGREGATE, asked=ASKED)

        assert not any(not m.path.startswith("Conversations/") for m in found.messages)

    async def test_one_user_never_counts_the_conversations_of_another(self, env, memory):
        await pizza_month(env)

        found = await said_about(USER_B, AGGREGATE, asked=ASKED)

        assert found.messages == []

    async def test_a_capped_search_says_the_count_is_a_minimum(self, env, memory, monkeypatch):
        await pizza_month(env)
        monkeypatch.setattr(module, "MAX_FILES", 2)

        found = await said_about(USER_A, AGGREGATE, asked=ASKED)

        assert found.capped
        assert "Au moins" in render_said(AGGREGATE, found)


class TestTheBlock:
    async def test_the_count_is_given_as_data(self, env, memory):
        await pizza_month(env)

        block = render_said(AGGREGATE, await said_about(USER_A, AGGREGATE, asked=ASKED))

        head, _, rest = block.partition("\n<donnees_externes source")
        assert "Décompte fait par le code" in head and "est une donnée" in head
        assert rest.startswith('="memoire">') and block.endswith("</donnees_externes>")
        assert "Propriétaire : 3 message(s). Maggie : 1 message(s)." in block
        assert "2026-11-20 19h05 — Propriétaire : « PIZZA encore ce soir »" in block
        assert "mois dernier" not in block and ASKED not in block

    async def test_a_zero_count_is_still_given(self, env, memory):
        plan = RecallPlan("agreger", ("sushi",), None, None)

        block = render_said(plan, await said_about(USER_A, plan, asked=ASKED))

        assert "Propriétaire : 0 message(s). Maggie : 0 message(s)." in block

    async def test_retrieving_lists_the_five_latest_passages_without_a_count(self, env, memory):
        await keep(
            env,
            "Conversations/2026/11/2026-11-01 10h00 — Salle.md",
            "".join(message(f"2026-11-0{n} 10h00", "Propriétaire", f"La salle {n}") for n in range(1, 8)),
        )
        plan = RecallPlan("retrouver", ("salle",), None, None)

        block = render_said(plan, await said_about(USER_A, plan, asked="x"))

        assert block.count("« La salle") == 5 and "La salle 7" in block and "La salle 2" not in block
        assert "message(s)" not in block

    async def test_nothing_retrieved_adds_no_block(self, env, memory):
        plan = RecallPlan("retrouver", ("sushi",), None, None)

        assert render_said(plan, await said_about(USER_A, plan, asked="x")) == ""

    def test_no_plan_adds_no_block(self):
        assert render_said(NO_PLAN, module.SaidFound([])) == ""


class TestTheBound:
    @staticmethod
    def outcome(outcome: str) -> float:
        return MEMORY_RECALL_OUTCOMES.labels(source="plan", outcome=outcome)._value.get()

    async def test_past_its_delay_the_turn_has_the_lexical_recall_alone(self, env, memory, monkeypatch):
        async def slow(*args, **kwargs):
            await asyncio.sleep(5)

        monkeypatch.setattr(module, "plan_recall", slow)
        monkeypatch.setattr(settings, "memory_recall_plan_timeout_seconds", 0.05)
        before = self.outcome("timeout")

        with container.override(llm_client=client_answering('{"plan": "aucun"}')):
            blocks = await memory.blocks(USER_A, ASKED, planned=True, today=TODAY)

        assert "Décompte" not in blocks.recalled
        assert self.outcome("timeout") == before + 1

    async def test_a_failure_of_the_search_never_fails_the_turn(self, env, memory, monkeypatch):
        monkeypatch.setattr(module, "said_about", AsyncMock(side_effect=RuntimeError("boom")))
        client = client_answering('{"plan": "agreger", "termes": ["pizza"], "debut": null, "fin": null}')
        before = self.outcome("error")

        assert await planned_recall(client, USER_A, ASKED, TODAY) == ""
        assert self.outcome("error") == before + 1

    async def test_the_outcome_is_measured(self, env, memory):
        await pizza_month(env)
        client = client_answering(
            '{"plan": "agreger", "termes": ["pizza"], "debut": "2026-11-01", "fin": "2026-11-23"}'
        )
        before = self.outcome("recalled")

        assert "Propriétaire : 3 message(s)" in await planned_recall(client, USER_A, ASKED, TODAY)
        assert self.outcome("recalled") == before + 1


async def test_a_count_question_brings_the_count_into_the_prompt(env, memory):
    await pizza_month(env)

    class Sources(MaggiePromptSources):
        async def base(self, user_id, capabilities):
            return "BASE"

        async def skills_index(self):
            return ""

        async def timezone(self, user_id):
            return ZoneInfo("Europe/Paris")

        async def directives(self, user_id):
            return ""

        async def contexts(self, user_id, current_context_id, contexts):
            return ""

        async def last_exchange(self, user_id, *, exclude_message_id, tz):
            return ""

        async def work_hours(self, user_id, now):
            return ""

        async def day_context(self, user_id, day):
            return None

    sources = Sources(agent_memory=memory)

    with container.override(llm_client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)):
        blocks = await SystemPrompt(sources).build(USER_A, moment="chat", about=ASKED, exclude_message_id="m-1")

    assert any("Propriétaire : 3 message(s)" in block["text"] for block in blocks)

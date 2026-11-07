"""One model per kind of run (MAG-731): judgement on Sonnet, mechanical work on Haiku."""

import re
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.bilan import BILAN_SUMMARY
from app.config import settings
from app.db.journal_repository import journal_repo
from app.llm import models
from app.llm.gateway import LLMGateway
from app.llm.models import MODEL_BY_RUN, model_for
from app.memory.agent_memory import MemoryBlocks
from app.trips import REPLAN_SUMMARY

APP = Path(__file__).resolve().parents[1] / "app"
SONNET, HAIKU = settings.anthropic_model, settings.anthropic_fast_model


def test_the_two_models_differ():
    assert SONNET != HAIKU


@pytest.mark.parametrize(
    ("run", "expected"),
    [
        (models.CHAT, SONNET),
        (models.PLANNING, SONNET),
        (models.DIGEST, SONNET),
        (models.TRIP, HAIKU),
        (models.NIGHT, HAIKU),
        (models.PROACTION, HAIKU),
        (models.UTILITY, HAIKU),
    ],
)
def test_each_kind_of_run_has_its_model(run, expected):
    assert model_for(run) == expected


def test_every_kind_of_run_is_in_the_table():
    kinds = {models.CHAT, models.PLANNING, models.DIGEST, models.TRIP, models.NIGHT, models.PROACTION, models.UTILITY}
    assert kinds == set(MODEL_BY_RUN)


def test_no_call_reads_a_model_setting_directly():
    offenders = [
        str(path.relative_to(APP))
        for path in APP.rglob("*.py")
        if path.name not in {"config.py", "models.py"}
        and re.search(r"settings\.anthropic_(fast_|opus_)?model\b", path.read_text())
    ]
    assert offenders == []


def _gateway() -> LLMGateway:
    gateway = LLMGateway()
    gateway.client = MagicMock()
    gateway.personality = MagicMock(get_system_prompt=AsyncMock(return_value="Tu es Maggie."))
    gateway.agent_memory = MagicMock(blocks=AsyncMock(return_value=MemoryBlocks()))
    gateway.tool_router = MagicMock(get_tool_definitions=AsyncMock(return_value=[]))
    gateway._resolve_proaction_context = AsyncMock(return_value=None)
    return gateway


async def _proaction_model(prompt: str, **options) -> tuple[str, dict]:
    loop = AsyncMock(return_value={"response": "", "tool_calls": []})
    with (
        patch("app.llm.gateway.run_tool_loop", loop),
        patch("app.llm.contexts.context_repo") as contexts,
        patch("app.llm.gateway.behavior_directives_section", AsyncMock(return_value="")),
    ):
        contexts.find_active = AsyncMock(return_value=[])
        result = await _gateway().proaction(prompt, "user-1", **options)
    return loop.await_args.kwargs["model"], result


class TestProactionRuns:
    async def test_the_morning_planning_uses_sonnet(self):
        model, result = await _proaction_model("Planifie la journée", silent=True)
        assert model == SONNET
        assert result["model"] == SONNET

    async def test_a_digest_uses_sonnet(self):
        model, _ = await _proaction_model("Fais-moi le digest de ma journée", summary="Digest du matin")
        assert model == SONNET

    async def test_the_evening_review_uses_sonnet(self):
        model, _ = await _proaction_model("Fais le bilan de la journée", summary=BILAN_SUMMARY)
        assert model == SONNET

    async def test_a_trip_replan_uses_haiku(self):
        model, result = await _proaction_model("Replanifie les trajets", summary=REPLAN_SUMMARY)
        assert model == HAIKU
        assert result["model"] == HAIKU

    async def test_an_ordinary_proaction_uses_haiku(self):
        model, _ = await _proaction_model("Rappel : appeler votre mère", summary="Appeler maman")
        assert model == HAIKU


async def test_the_night_asks_haiku():
    from app.memory.night import NightKeeper

    client = MagicMock()
    client.messages.create = AsyncMock(return_value=MagicMock(content=[], usage=MagicMock()))
    keeper = NightKeeper.__new__(NightKeeper)
    keeper.client = client
    with patch("app.memory.night.note_provider_success", AsyncMock()), patch("app.memory.night.record_llm_usage"):
        await keeper.ask("system", "request")
    assert client.messages.create.await_args.kwargs["model"] == HAIKU


async def test_a_journal_run_keeps_its_model(journal_db):
    run = await journal_repo.record_proaction(
        "user-1", "p-1", "prompt", None, "2026-11-07", datetime.now(UTC), 10,
        result={"response": "ok", "tool_calls": [], "model": HAIKU},
    )
    assert run.to_dict()["model"] == HAIKU

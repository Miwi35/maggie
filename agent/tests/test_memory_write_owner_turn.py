"""Fiches and missed recalls are written only in a turn the owner started (MAG-972)."""

import json
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.llm.claim_guard import ClaimGuard, Verdict
from app.llm.tools import POLICY_DENIED_MESSAGE, ToolRouter
from app.native_tools import build_native_registry

FICHE = {
    "titre": "Le concert commence à 19 heures",
    "affirmations": [
        {
            "texte": "Le concert commence à 19 heures",
            "provenance": "dit",
            "source": "Conversations/x.md",
            "heure": "2026-11-20 10h00",
            "citation": "le concert commence à 19 heures",
        }
    ],
}
MISS = {"demande": "Quel est le nom de la salle ?", "souvenir": "la salle du concert"}
DENIED = json.dumps({"error": POLICY_DENIED_MESSAGE})


@pytest.fixture()
def write_fiche():
    with (
        patch("app.memory.tools.fiches.write_fiche", AsyncMock(return_value={"chemin": "x.md"})) as write,
        patch("app.memory.tools._unavailable", return_value=None),
        patch("app.memory.tools.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
    ):
        yield write


def _router() -> ToolRouter:
    return ToolRouter(natives=build_native_registry())


@pytest.mark.asyncio
async def test_a_proaction_cannot_write_a_fiche(write_fiche):
    result = await _router().call_tool("memory_write_fiche", FICHE, user_id="user-1", source="proaction")

    assert result == DENIED
    write_fiche.assert_not_awaited()


@pytest.mark.asyncio
async def test_a_sub_agent_cannot_log_a_miss(write_fiche):
    with patch("app.memory.tools.misses.record", AsyncMock()) as record:
        result = await _router().call_tool(
            "memory_log_miss", MISS, user_id="user-1", source="subagent:researcher"
        )

    assert result == DENIED
    record.assert_not_awaited()


@pytest.mark.asyncio
async def test_the_owners_streamed_turn_writes_the_fiche(write_fiche):
    result = await _router().call_tool("memory_write_fiche", FICHE, user_id="user-1", source="chat_stream")

    write_fiche.assert_awaited_once()
    assert json.loads(result)["enregistre"] is True


def test_a_refused_write_does_not_back_cest_note():
    guard = ClaimGuard([{"name": "memory_write_fiche"}])
    guard.record("memory_write_fiche", DENIED, FICHE)

    assert guard.review("C'est noté, monsieur.") is Verdict.RETRY

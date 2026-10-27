"""The fake-LLM scenarios the memory journey relies on (MAG-17), driven through the real tool loop and tools."""

import re
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.llm.fake import FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.memory import conversations, tools
from app.memory.tools import MEMORY_TOOLS
from tests.memory.conftest import USER_A
from tests.memory.test_night import wired  # noqa: F401  (the night's wiring, used by name below)

SHIPPED = Path(__file__).resolve().parents[2] / "fixtures" / "fake-llm"
PARIS = ZoneInfo("Europe/Paris")
ANECDOTE = "Les concerts commencent à 19 heures et la salle s'appelle l'UBU."
FICHE = "Sorties/Les concerts commencent à 19 heures à l'UBU.md"


@pytest.fixture(autouse=True)
def _wired(env):
    conversations._files.clear()
    conversations._locks.clear()
    with (
        patch.object(tools, "memory_service", env.service),
        patch.object(tools, "resolve_user_timezone", AsyncMock(return_value=PARIS)),
        patch("app.llm.runner.record_llm_usage"),
    ):
        yield
    conversations._files.clear()
    conversations._locks.clear()


async def turn(message: str, system: str = "Tu tiens ta mémoire."):
    return await run_tool_loop(
        system,
        [{"role": "user", "content": message}],
        MEMORY_TOOLS,
        client=FakeAnthropicClient(fixtures_dir=SHIPPED),
        tool_router=ToolRouter(),
        user_id=USER_A,
        model="fake",
    )


async def test_the_anecdote_is_answered_without_a_tool_and_the_fiche_cites_its_conversation_then_comes_back(env):
    first = await turn(ANECDOTE)
    assert first["tool_calls"] == []

    exchange = [
        SimpleNamespace(
            id="m1", role="user", content=ANECDOTE, created_at=datetime(2026, 10, 13, 17, 5, tzinfo=UTC), blocks=None,
            interrupted=False,
        ),
        SimpleNamespace(
            id="m2", role="assistant", content=first["response"], created_at=datetime(2026, 10, 13, 17, 5, tzinfo=UTC),
            blocks=None, interrupted=False,
        ),
    ]
    file = await conversations.append_messages(USER_A, "ctx1", "Conversation e2e", exchange, tz=PARIS, service=env.service)
    assert ANECDOTE in env.local(file)
    minute = re.findall(r"^## (\S+ \S+) — ", env.local(file), re.MULTILINE)[0]

    second = await turn(f"Retiens ce que je t'ai dit sur la salle : conversation « {file} » à {minute}")
    assert [call["name"] for call in second["tool_calls"]] == ["memory_write_fiche"]
    assert f"— dit · {file} · {minute}" in env.local(FICHE)

    listed = f"- {FICHE} — L'heure des concerts"
    third = await turn("Que sais-tu de la salle de concert ?", system=f"Tes fiches :\n{listed}")
    assert [call["name"] for call in third["tool_calls"]] == ["memory_open"]
    assert "l'UBU" in third["response"]


async def test_a_carte_brought_back_by_the_recall_is_answered_from_without_a_search(env):
    from app.memory.agent_memory import render_recalled
    from app.memory.recall import Recalled

    recalled = render_recalled(
        [
            Recalled(
                "Famille/Françoise et les roses.md", "fiche", "id", "Françoise et les roses",
                "Françoise n'aime pas les roses", [], [], 3.0,
            )
        ]
    )

    answered = await turn("Un bouquet pour la fête des mères", system=f"Tu tiens ta mémoire.\n\n{recalled}")
    unanswered = await turn("Un bouquet pour la fête des mères", system="Tu tiens ta mémoire.")

    assert answered["tool_calls"] == []
    assert "Évite les roses" in answered["response"]
    assert "aucun scénario" in unanswered["response"]


MISS_SAID = (
    "Je t'avais demandé un bouquet pour la fête des mères, tu aurais dû t'en souvenir : Françoise n'aime pas les roses."
)
ROSES = "Famille/Françoise n'aime pas les roses.md"


@pytest.mark.usefixtures("wired")
async def test_a_missed_recall_is_logged_by_the_shipped_scenario_and_the_night_then_enriches_the_fiche(env):
    from datetime import date

    from app.memory import carte as carte_mod
    from app.memory import misses, recall
    from tests.memory.conftest import fiche
    from tests.memory.test_night import NOW, keeper

    await env.service.write(USER_A, ROSES, fiche("Françoise n'aime pas les roses.", resume="Françoise n'aime pas les roses"))

    said = await turn(MISS_SAID)

    assert [call["name"] for call in said["tool_calls"]] == ["memory_log_miss"]
    assert "Cette nuit" in said["response"]
    [logged] = await misses.cases(env.service, USER_A)
    assert logged.attendue == ROSES and logged.demande == "Un bouquet pour la fête des mères"
    assert recall.rank(await env.service.cards(USER_A), logged.demande, date.today()) == []

    night = keeper(env, FakeAnthropicClient(fixtures_dir=SHIPPED))
    run_ = await night.run(USER_A, NOW, force=True)

    assert run_ is not None and run_.report["rappels_verts"] == [logged.stem]
    assert carte_mod.parse(env.local(ROSES)).carte.evoque == ["fête des mères", "offrir des fleurs"]
    assert (await misses.cases(env.service, USER_A))[0].statut == misses.GREEN

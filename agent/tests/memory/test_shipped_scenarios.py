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

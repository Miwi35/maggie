"""Maggie reads, creates and ticks routines through the shipped scenarios (MAG-513).

What the model would decide is judged on the real model; what this proves is the run itself:
the tools reached and their arguments, the tick made with the id the list returned, and an
answer that counts what was done without ever speaking of a streak.
"""

import json
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter

USER = "user-1"
WATER_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7W"
TRIAL_ID = "01K6ZQ8R1V3J9F0C2M4N5P6T7X"
OFFERED = [{"name": name, "input_schema": {}} for name in ("list_routines", "save_routine", "check_routine")]


def routine(identifier: str, title: str, month: int, total: int) -> dict:
    return {
        "id": identifier,
        "title": title,
        "anchor": "wake",
        "anchorTime": None,
        "monthCount": month,
        "totalCount": total,
    }


class FakeRoutineApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        if name == "list_routines":
            routines = [
                routine(TRIAL_ID, "Grand verre d'eau (essai 1)", 1, 1),
                routine(WATER_ID, "Grand verre d'eau", 12, 40),
            ]
            return json.dumps({"routines": routines})
        return json.dumps({"success": True})


@pytest.fixture()
def api():
    fake = FakeRoutineApi()
    with patch("app.llm.tools.mcp_client", fake):
        yield fake


async def ask(text: str) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": text}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id=USER,
            model="fake",
            source="chat",
        )


def called(result: dict) -> list[str]:
    return [call["name"] for call in result["tool_calls"]]


class TestTickingARoutine:
    async def test_it_lists_the_day_then_ticks_the_routine_by_its_own_id(self, api):
        result = await ask("J'ai bu mon grand verre d'eau")

        assert called(result) == ["list_routines", "check_routine"]
        assert [name for name, _ in api.calls] == ["list_routines", "check_routine"]
        assert api.calls[0][1]["date"]
        assert api.calls[1] == ("check_routine", {"id": WATER_ID})

    async def test_it_notes_it_and_stops_there(self, api):
        result = await ask("J'ai bu mon grand verre d'eau")

        assert result["response"] == "C'est noté, ton grand verre d'eau est coché."


class TestAddingARoutine:
    async def test_the_sentence_gives_a_title_a_moment_and_a_rhythm(self, api):
        result = await ask("Ajoute une routine : sortir les poubelles le mardi après le dîner")

        assert called(result) == ["save_routine"]
        assert api.calls == [
            (
                "save_routine",
                {
                    "title": "Sortir les poubelles",
                    "anchor": "afterDinner",
                    "recurrence": {"kind": "weekly", "weekday": 2},
                },
            )
        ]
        assert result["response"] == "Routine ajoutée : sortir les poubelles, le mardi après le dîner."

    async def test_the_bin_night_proaction_scenario_keeps_its_own_messages(self, api):
        result = await ask("Rappelle-moi les poubelles")

        assert "save_routine" not in called(result)


class TestTheStatus:
    async def test_it_reads_every_routine_and_gives_the_month_and_the_total(self, api):
        result = await ask("Où en sont mes routines ?")

        assert called(result) == ["list_routines"]
        assert api.calls == [("list_routines", {})]
        assert "1 fois ce mois-ci" in result["response"]
        assert "1 fois depuis sa création" in result["response"]

    async def test_the_answer_never_speaks_of_a_streak(self, api):
        result = await ask("Où en sont mes routines ?")

        answer = result["response"].lower()
        assert not any(word in answer for word in ("série", "d'affilée", "consécutif", "manqué", "streak"))

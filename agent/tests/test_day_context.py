"""The line « Contexte du jour » for today and tomorrow (MAG-811)."""

import json
from datetime import date
from unittest.mock import AsyncMock, patch

from app.day_context import day_context_line, resolve_day_context

# 2026-11-11 is a Wednesday.
WEDNESDAY = {
    "date": "2026-11-11",
    "weekend": False,
    "periods": [
        {"id": "1", "name": "Férié 11 novembre", "agenda": {"id": "a", "name": "Perso"}, "allDay": True, "start": "2026-11-11", "end": "2026-11-11"},
        {"id": "2", "name": "Garde", "agenda": {"id": "b", "name": "Famille"}, "allDay": False, "start": "2026-11-06T18:00:00+01:00", "end": "2026-11-13T18:00:00+01:00"},
    ],
}
THURSDAY = {"date": "2026-11-12", "weekend": False, "periods": [WEDNESDAY["periods"][1]]}


def test_periods_are_listed_with_their_end():
    line = day_context_line(WEDNESDAY, THURSDAY)

    assert line == (
        "Contexte du jour : aujourd'hui mercredi 11 novembre ; périodes : Férié 11 novembre, "
        "Garde (jusqu'au vendredi 13 novembre 18:00). Demain jeudi 12 novembre : période : Garde."
    )


def test_an_ordinary_weekday_says_only_the_day():
    tuesday = {"date": "2026-11-10", "weekend": False, "periods": []}
    wednesday = {"date": "2026-11-11", "weekend": False, "periods": []}

    assert day_context_line(tuesday, wednesday) == "Contexte du jour : aujourd'hui mardi 10 novembre. Demain mercredi 11 novembre."


def test_the_weekend_is_said():
    saturday = {"date": "2026-11-14", "weekend": True, "periods": []}
    sunday = {"date": "2026-11-15", "weekend": True, "periods": []}

    assert day_context_line(saturday, sunday) == "Contexte du jour : aujourd'hui samedi 14 novembre (week-end). Demain dimanche 15 novembre (week-end)."


def test_tomorrow_is_on_the_same_line():
    assert "\n" not in day_context_line(WEDNESDAY, THURSDAY)


def test_tomorrow_unknown_leaves_today_alone():
    assert day_context_line(WEDNESDAY, None).endswith("(jusqu'au vendredi 13 novembre 18:00).")


async def test_the_line_is_read_from_the_tool_for_both_days():
    answers = [json.dumps(WEDNESDAY), json.dumps(THURSDAY)]
    call = AsyncMock(side_effect=answers)

    with patch("app.day_context.mcp_client.call_tool", call):
        line = await resolve_day_context("user-1", date(2026, 11, 11))

    assert line.startswith("Contexte du jour : aujourd'hui mercredi 11 novembre")
    assert [c.args[1]["date"] for c in call.await_args_list] == ["2026-11-11", "2026-11-12"]


async def test_an_api_error_gives_no_line():
    with patch("app.day_context.mcp_client.call_tool", AsyncMock(side_effect=RuntimeError("down"))):
        assert await resolve_day_context("user-1", date(2026, 11, 11)) == ""


async def test_a_refused_read_gives_no_line():
    with patch("app.day_context.mcp_client.call_tool", AsyncMock(return_value=json.dumps({"error": "no user"}))):
        assert await resolve_day_context("user-1", date(2026, 11, 11)) == ""

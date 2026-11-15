"""The context of today and tomorrow (MAG-811): weekend, and the periods covering each day.

The API says what the day is (`get_day_context`); Maggie reads the periods' names herself — the code knows no
kind of period. The line goes into the volatile part of the prompt, next to the date, so it never reaches the
cached prefix.
"""

import asyncio
import json
import logging
from datetime import date, datetime, timedelta

from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

DAY_CONTEXT_TOOL = "get_day_context"
LINE_PREFIX = "Contexte du jour :"

WEEKDAYS = ("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
MONTHS = (
    "janvier",
    "février",
    "mars",
    "avril",
    "mai",
    "juin",
    "juillet",
    "août",
    "septembre",
    "octobre",
    "novembre",
    "décembre",
)


def _day_label(day: date) -> str:
    return f"{WEEKDAYS[day.weekday()]} {day.day} {MONTHS[day.month - 1]}"


def _until(period: dict, today: date) -> str:
    """` (jusqu'au vendredi 13 novembre 18:00)` when the period outlasts today, its end otherwise."""
    end = period.get("end")
    if not end:
        return ""
    try:
        if period.get("allDay"):
            last, at = date.fromisoformat(end), ""
        else:
            moment = datetime.fromisoformat(end)
            last, at = moment.date(), f" {moment:%H:%M}"
    except ValueError:
        return ""
    if last > today:
        return f" (jusqu'au {_day_label(last)}{at})"
    return f" (jusqu'à {at.strip()})" if at else ""


def format_day(ctx: dict, label: str, *, with_end: bool, sep: str) -> str:
    """One day: `label`, then the weekend and, after `sep`, the periods. `with_end` adds each period's end."""
    day = date.fromisoformat(ctx["date"])
    text = f"{label} {_day_label(day)}"
    if ctx.get("weekend"):
        text += " (week-end)"
    periods = ctx.get("periods") or []
    if periods:
        names = ", ".join(f"{p['name']}{_until(p, day) if with_end else ''}" for p in periods)
        text += f"{sep}{'périodes' if len(periods) > 1 else 'période'} : {names}"
    return text


def day_context_line(today_ctx: dict | None, tomorrow_ctx: dict | None) -> str:
    """The `Contexte du jour :` line; empty when today is unknown, today alone when tomorrow is."""
    if not today_ctx:
        return ""
    try:
        line = f"{LINE_PREFIX} {format_day(today_ctx, "aujourd'hui", with_end=True, sep=' ; ')}."
        if tomorrow_ctx:
            line += f" {format_day(tomorrow_ctx, 'Demain', with_end=False, sep=' : ')}."
    except (KeyError, ValueError, TypeError) as e:
        logger.warning(f"Day context unreadable: {e!r}")
        return ""
    return line


async def _read(user_id: str, day: date) -> dict | None:
    try:
        answer = json.loads(await mcp_client.call_tool(DAY_CONTEXT_TOOL, {"date": day.isoformat()}, user_id=user_id))
    except Exception as e:
        logger.warning(f"Day context of user {user_id} for {day} unreadable: {e!r}")
        return None
    if not isinstance(answer, dict) or "error" in answer:
        logger.warning(f"Day context of user {user_id} for {day} refused: {answer!r}")
        return None
    return answer


async def resolve_day_context(user_id: str, today: date) -> str:
    """The line for `today` and the day after, read in parallel; `""` when the API does not answer."""
    today_ctx, tomorrow_ctx = await asyncio.gather(_read(user_id, today), _read(user_id, today + timedelta(days=1)))
    return day_context_line(today_ctx, tomorrow_ctx)

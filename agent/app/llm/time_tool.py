"""The date_time tool: date and time arithmetic done by zoneinfo, never by the model (MAG-161).

Models misjudge « what time is it in Fort-de-France », « what day are we in 45 days » and above all the
nights daylight saving time changes. Everything here comes from the tz database: no offset is written down.

Two kinds of duration, because they are not the same thing across a clock change:
- calendar units (years, months, weeks, days) move the wall clock: 15:00 + 1 day is 15:00 the next day,
  even when that day has 23 or 25 hours;
- clock units (hours, minutes, seconds) move the instant: 01:30 + 2 hours is two real hours later.
"""

import calendar
import json
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from app.llm.time_places import PlaceError, resolve_place
from app.personality.engine import DAYS_FR
from app.user_timezone import resolve_user_timezone

MONTHS_FR = [
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
]
ACTIONS = ["now", "convert", "add", "diff", "weekday"]
CALENDAR_UNITS = ("years", "months", "weeks", "days")
CLOCK_UNITS = ("hours", "minutes", "seconds")

DATE_TIME_TOOLS = [
    {
        "name": "date_time",
        "description": (
            "Exact date and time calculations — USE IT instead of computing in your head whenever a timezone, a "
            "daylight saving change, a weekday or a duration is involved. Never write an offset yourself. "
            "Actions: 'now' (current time in a place), 'convert' (an instant from one place to another), "
            "'add' (instant + duration, negative to go back), 'diff' (time between two instants), "
            "'weekday' (day of the week of a date). "
            "Places ('timezone', 'to_timezone', 'end_timezone') are a city, a region, a country or an IANA name "
            "('Fort-de-France', 'New York', 'Europe/Paris'); omit them for the user's own timezone. "
            "A 'datetime' without offset is read as local time in 'timezone'; omit it for the current instant. "
            "Every result carries an 'iso' value with its offset, ready to pass as 'scheduled_at' to "
            "schedule_proaction or to build an event. This tool only computes: it schedules nothing, so a reminder "
            "still needs the schedule_proaction call. Calendar units (years, months, weeks, days) keep the wall-clock "
            "time across a clock change; clock units (hours, minutes, seconds) are real elapsed time."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ACTIONS},
                "datetime": {
                    "type": "string",
                    "description": "ISO 8601 date or datetime, e.g. 2026-03-28 or 2026-03-28T15:00. Default: now.",
                },
                "timezone": {
                    "type": "string",
                    "description": "Place of 'datetime' (source place for convert). Default: the user's timezone.",
                },
                "to_timezone": {"type": "string", "description": "Target place (convert only)."},
                "end": {"type": "string", "description": "ISO 8601 end instant (diff only)."},
                "end_timezone": {"type": "string", "description": "Place of 'end'. Default: same as 'timezone'."},
                "years": {"type": "integer"},
                "months": {"type": "integer"},
                "weeks": {"type": "integer"},
                "days": {"type": "integer"},
                "hours": {"type": "number"},
                "minutes": {"type": "number"},
                "seconds": {"type": "number"},
            },
            "required": ["action"],
        },
    },
]


class _InputError(Exception):
    pass


def _offset_text(local: datetime) -> str:
    offset = local.utcoffset() or timedelta(0)
    minutes = int(offset.total_seconds() // 60)
    sign = "+" if minutes >= 0 else "-"
    hours, rest = divmod(abs(minutes), 60)
    return f"{sign}{hours:02d}:{rest:02d}"


def _describe(local: datetime) -> dict:
    spoken_time = f"{local.hour}h{local.minute:02d}"
    return {
        "iso": local.isoformat(timespec="seconds" if local.second else "minutes"),
        "local": f"{DAYS_FR[local.weekday()]} {local.day} {MONTHS_FR[local.month - 1]} {local.year}, {spoken_time}",
        "weekday": DAYS_FR[local.weekday()],
        "timezone": getattr(local.tzinfo, "key", None) or str(local.tzinfo),
        "utc_offset": _offset_text(local),
        "abbreviation": local.tzname(),
        "dst": bool(local.dst()),
        "utc": local.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def spoken_local_time(instant: datetime, tz: ZoneInfo) -> str:
    """An instant as the user says it in their own timezone: « mercredi 7 octobre 2026, 16h05 »."""
    return _describe(instant.astimezone(tz))["local"]


def _hours(later: timedelta | None, earlier: timedelta | None) -> float:
    return ((later or timedelta(0)) - (earlier or timedelta(0))).total_seconds() / 3600


def _notes(notes: list[str]) -> dict:
    return {"notes": notes} if notes else {}


def _zone(arguments: dict, key: str, here: ZoneInfo, default: ZoneInfo | None = None) -> ZoneInfo:
    value = arguments.get(key)
    if value in (None, ""):
        return default or here
    if not isinstance(value, str):
        raise _InputError(f"'{key}' must be a string")
    return resolve_place(value, here)


def _instant(arguments: dict, key: str, tz: ZoneInfo, now: datetime, notes: list[str]) -> datetime:
    """The instant given under `key`, in `tz`. No value means now. A naive value is local time in `tz`.

    A local time that does not exist or exists twice is explained in `notes`.
    """
    value = arguments.get(key)
    if value in (None, ""):
        return now.astimezone(tz)
    if not isinstance(value, str):
        raise _InputError(f"'{key}' must be an ISO 8601 string")
    try:
        parsed = datetime.fromisoformat(value.strip())
    except ValueError as e:
        raise _InputError(f"'{key}' is not an ISO 8601 date or datetime: {value!r}") from e
    if parsed.tzinfo is None:
        if note := _wall_note(parsed, tz):
            notes.append(note)
        # Through UTC: astimezone() to the very same zone is a no-op and would keep a time in a clock gap.
        parsed = parsed.replace(tzinfo=tz).astimezone(UTC)
    return parsed.astimezone(tz)


def _wall_note(wall: datetime, tz: ZoneInfo) -> str | None:
    """A local time that does not exist (clock jumped forward) or exists twice (clock fell back)."""
    first = wall.replace(tzinfo=tz, fold=0)
    if first.astimezone(UTC).astimezone(tz).replace(tzinfo=None) != wall:
        return "This local time does not exist (the clocks jump forward): the result is shifted to the next valid time."
    if tz.utcoffset(first) != tz.utcoffset(wall.replace(tzinfo=tz, fold=1)):
        return "This local time happens twice (the clocks go back): the first occurrence is used."
    return None


def _add_months(wall: datetime, months: int) -> datetime:
    year, month = divmod(wall.year * 12 + wall.month - 1 + months, 12)
    month += 1
    day = min(wall.day, calendar.monthrange(year, month)[1])
    return wall.replace(year=year, month=month, day=day)


def _durations(arguments: dict) -> tuple[dict[str, int], dict[str, float]]:
    calendar_part: dict[str, int] = {}
    clock_part: dict[str, float] = {}
    for unit in CALENDAR_UNITS + CLOCK_UNITS:
        value = arguments.get(unit)
        if value in (None, ""):
            continue
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise _InputError(f"'{unit}' must be a number")
        if unit in CALENDAR_UNITS:
            if int(value) != value:
                raise _InputError(f"'{unit}' must be a whole number")
            calendar_part[unit] = int(value)
        else:
            clock_part[unit] = float(value)
    if not calendar_part and not clock_part:
        raise _InputError("'add' needs a duration: years, months, weeks, days, hours, minutes or seconds")
    return calendar_part, clock_part


def _action_now(arguments: dict, here: ZoneInfo, now: datetime) -> dict:
    tz = _zone(arguments, "timezone", here)
    return {"now": _describe(now.astimezone(tz))}


def _action_convert(arguments: dict, here: ZoneInfo, now: datetime) -> dict:
    if not arguments.get("to_timezone"):
        raise _InputError("'to_timezone' is required for convert")
    source_tz = _zone(arguments, "timezone", here)
    target_tz = _zone(arguments, "to_timezone", here)
    notes: list[str] = []
    source = _instant(arguments, "datetime", source_tz, now, notes)
    target = source.astimezone(target_tz)
    result = {
        "source": _describe(source),
        "target": _describe(target),
        "target_minus_source_hours": _hours(target.utcoffset(), source.utcoffset()),
    }
    return result | _notes(notes)


def _action_add(arguments: dict, here: ZoneInfo, now: datetime) -> dict:
    tz = _zone(arguments, "timezone", here)
    notes: list[str] = []
    base = _instant(arguments, "datetime", tz, now, notes)
    calendar_part, clock_part = _durations(arguments)

    moved = base
    if any(calendar_part.values()):
        months = calendar_part.get("years", 0) * 12 + calendar_part.get("months", 0)
        wall = _add_months(base.replace(tzinfo=None), months)
        wall += timedelta(days=calendar_part.get("days", 0) + 7 * calendar_part.get("weeks", 0))
        if note := _wall_note(wall, tz):
            notes.append(note)
        moved = wall.replace(tzinfo=tz).astimezone(UTC).astimezone(tz)
    if clock_part:
        moved = (moved.astimezone(UTC) + timedelta(**clock_part)).astimezone(tz)

    return {"start": _describe(base), "result": _describe(moved)} | _notes(notes)


def _action_diff(arguments: dict, here: ZoneInfo, now: datetime) -> dict:
    if not arguments.get("end"):
        raise _InputError("'end' is required for diff")
    start_tz = _zone(arguments, "timezone", here)
    end_tz = _zone(arguments, "end_timezone", here, default=start_tz)
    notes: list[str] = []
    start = _instant(arguments, "datetime", start_tz, now, notes)
    end = _instant(arguments, "end", end_tz, now, notes)

    delta = end.astimezone(UTC) - start.astimezone(UTC)
    seconds = int(abs(delta.total_seconds()))
    days, rest = divmod(seconds, 86400)
    hours, rest = divmod(rest, 3600)
    minutes, secs = divmod(rest, 60)
    calendar_days = (end.astimezone(start_tz).date() - start.date()).days
    return {
        "start": _describe(start),
        "end": _describe(end),
        "direction": "future" if delta > timedelta(0) else "past" if delta < timedelta(0) else "same",
        "elapsed": {
            "days": days,
            "hours": hours,
            "minutes": minutes,
            "seconds": secs,
            "total_hours": round(seconds / 3600, 4),
            "total_seconds": seconds,
        },
        "calendar_days": calendar_days,
        "clock_change_hours": _hours(end.astimezone(start_tz).utcoffset(), start.utcoffset()),
    } | _notes(notes)


def _action_weekday(arguments: dict, here: ZoneInfo, now: datetime) -> dict:
    tz = _zone(arguments, "timezone", here)
    moment = _instant(arguments, "datetime", tz, now, [])
    return {
        "date": moment.date().isoformat(),
        "weekday": DAYS_FR[moment.weekday()],
        "iso_weekday": moment.isoweekday(),
        "iso_week": moment.isocalendar().week,
        "day_of_year": moment.timetuple().tm_yday,
    }


_ACTIONS = {
    "now": _action_now,
    "convert": _action_convert,
    "add": _action_add,
    "diff": _action_diff,
    "weekday": _action_weekday,
}


def run_date_time(arguments: dict, here: ZoneInfo, now: datetime | None = None) -> dict:
    """Run one date_time call. `here` is the user's timezone, `now` the clock (injectable for tests)."""
    now = (now or datetime.now(UTC)).replace(microsecond=0)
    action = arguments.get("action")
    if action not in _ACTIONS:
        return {"error": f"'action' must be one of {ACTIONS}"}
    try:
        return _ACTIONS[action](arguments, here, now)
    except PlaceError as e:
        result: dict = {"error": str(e)}
        if e.candidates:
            result["candidates"] = list(e.candidates)
        return result
    except (_InputError, ValueError, OverflowError) as e:
        return {"error": str(e)}


async def handle_date_time(arguments: dict, user_id: str) -> str:
    here = await resolve_user_timezone(user_id)
    return json.dumps(run_date_time(arguments, here), ensure_ascii=False)

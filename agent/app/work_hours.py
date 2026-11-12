"""Whether the user is at work right now (MAG-790), from the hours set in Réglages > Maggie.

The working hours decide the tone — professional during them, warmer outside — and are not the « Travail »
activity, which says the user is concentrating. The line goes into the volatile part of the prompt, next
to the date: it changes during the day, so it never reaches the cached prefix.
"""

from collections.abc import Mapping
from datetime import datetime

DEFAULT_START = "09:00"
DEFAULT_END = "18:00"
DEFAULT_DAYS = (1, 2, 3, 4, 5)

IN_WORK_HOURS_LINE = "Horaires de travail : en cours."
OUT_OF_WORK_HOURS_LINE = "Horaires de travail : hors horaires de travail."


def in_work_hours(prefs: Mapping, now: datetime) -> bool:
    """Whether `now`, already in the user's timezone, falls on a working day between start (included) and
    end (excluded). Missing keys take the defaults: 09:00 to 18:00, Monday to Friday."""
    start = prefs.get("workStartTime") or DEFAULT_START
    end = prefs.get("workEndTime") or DEFAULT_END
    days = prefs.get("workDays")
    if days is None:
        days = DEFAULT_DAYS
    return now.isoweekday() in days and start <= now.strftime("%H:%M") < end


def work_hours_line(prefs: Mapping, now: datetime) -> str:
    return IN_WORK_HOURS_LINE if in_work_hours(prefs, now) else OUT_OF_WORK_HOURS_LINE

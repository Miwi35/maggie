"""The evening review on the agent's side (MAG-749): the proactions the morning planning lays down for it,
and the one rule the code keeps — no second nudge once the user has answered. The conversation itself is the
skill `bilan-soir` (`app/skills/bilan-soir.md`); the state is the API's `activity-status/bilan` (MAG-747)."""

import json
import logging
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

BILAN_SUMMARY = "Bilan de la journée"
BILAN_RELANCE_SUMMARY = "Bilan de la journée · relance"
DEFAULT_BEDTIME = "21:30"
RELANCE_DELAY_MINUTES = 30
STATUS_TOOL = "get_activity_status"


def is_bilan_relance(summary: str | None) -> bool:
    return summary == BILAN_RELANCE_SUMMARY


def planning_bilan_rules(day: str) -> str:
    """The evening step, appended to the daily planning prompt of every non-technical account."""
    return (
        f"6. Bilan du soir du {day}. Cherche son heure de coucher habituelle dans ta mémoire "
        "(memory_search « heure de coucher »). "
        f"Sans heure apprise, prends {DEFAULT_BEDTIME} (heure de Paris) ; une heure apprise la remplace. "
        f"Si list_proactions ne montre pas déjà ce jour-là une proaction de résumé « {BILAN_SUMMARY} », pose-en deux "
        "avec schedule_proaction : "
        f"a) à l'heure de coucher, résumé exactement « {BILAN_SUMMARY} », prompt : « Fais le bilan de la journée avec "
        "l'utilisateur (compétence bilan-soir, ouverture par le rendez-vous du soir). » ; "
        f"b) {RELANCE_DELAY_MINUTES} minutes plus tard, résumé exactement « {BILAN_RELANCE_SUMMARY} », même prompt "
        "(relance unique : elle ne part pas si l'utilisateur a déjà répondu). "
        "Si l'heure de coucher est déjà passée, ne pose rien."
    )


def relance_review_day(scheduled_at: str | None, fallback: datetime, tz: ZoneInfo) -> str:
    """The day (YYYY-MM-DD) of the review a nudge follows: its own time minus the delay, so one that
    falls after midnight still asks about the evening it belongs to."""
    try:
        moment = datetime.fromisoformat(scheduled_at) - timedelta(minutes=RELANCE_DELAY_MINUTES)
    except (TypeError, ValueError):
        moment = fallback
    return moment.astimezone(tz).date().isoformat()


async def bilan_answered(user_id: str, day: str) -> bool:
    """Whether the user has already taken up the review of `day`: a status of the bilan exists for that day.

    The skill writes the status at the user's first answer, not when it opens the review, so a status is an answer.
    An API that cannot be read counts as not answered: one nudge too many beats a missed evening.
    """
    try:
        status = json.loads(await mcp_client.call_tool(STATUS_TOOL, {"activity": "bilan"}, user_id=user_id))
    except Exception as e:
        logger.warning(f"Could not read the bilan status of user {user_id}: {e!r}")
        return False
    if not isinstance(status, dict) or "error" in status:
        return False
    origin = status.get("origin")
    return isinstance(origin, dict) and origin.get("id") == day

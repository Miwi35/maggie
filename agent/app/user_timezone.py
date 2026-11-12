import json
import logging
import time
from zoneinfo import ZoneInfo

from app.auth import TECHNICAL_ACCOUNT_EMAILS
from app.config import settings
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

USER_TIMEZONE_TOOL = "get_user_timezone"

# A chat turn reads the timezone from several places (prompt, web search, memory, time tool); the MCP
# round trip is paid once per window instead of once per place.
TIMEZONE_FRESH_SECONDS = 60

known_timezones: dict[str, ZoneInfo] = {}
read_at: dict[str, float] = {}
# The working hours (MAG-790) come with the timezone, in the same answer: start, end, ISO days.
WORK_HOURS_KEYS = ("workStartTime", "workEndTime", "workDays")
known_work_hours: dict[str, dict] = {}


async def resolve_user_timezone(user_id: str) -> ZoneInfo:
    """The timezone stored in the user's preferences.

    A stored name that is not a timezone means Europe/Paris. A preference that cannot be read right now
    (API restarting) keeps the last timezone read for the user: falling back to Paris for one cycle would
    skip a New York user's planning moment for the whole day.

    A timezone read less than `TIMEZONE_FRESH_SECONDS` ago is served without calling the MCP server again;
    a failed read is never remembered as fresh.
    """
    known = known_timezones.get(user_id)
    if known is not None and time.monotonic() - read_at.get(user_id, float("-inf")) < TIMEZONE_FRESH_SECONDS:
        return known

    try:
        raw = await mcp_client.call_tool(USER_TIMEZONE_TOOL, {}, user_id=user_id)
        answer = json.loads(raw)
        name = answer["timezone"]
    except Exception as e:
        fallback = known or ZoneInfo(settings.planning_timezone)
        logger.warning(f"Timezone of user {user_id} unreadable, keeping {fallback.key}: {e!r}")
        return fallback

    read_at[user_id] = time.monotonic()
    known_work_hours[user_id] = {key: answer[key] for key in WORK_HOURS_KEYS if key in answer}

    try:
        known_timezones[user_id] = ZoneInfo(name)
    except Exception as e:
        logger.warning(f"Invalid timezone {name!r} for user {user_id}, planning on {settings.planning_timezone}: {e!r}")
        known_timezones[user_id] = ZoneInfo(settings.planning_timezone)
    return known_timezones[user_id]


async def resolve_work_hours(user_id: str) -> dict:
    """The working hours read with the timezone (`workStartTime`, `workEndTime`, `workDays`), from the same
    fresh read. Never read: an empty dict, which `app.work_hours` takes as the defaults."""
    await resolve_user_timezone(user_id)
    return known_work_hours.get(user_id, {})


async def resolve_user_email(user_id: str) -> str | None:
    """The account email the API reports next to the timezone; None when it cannot be read right now."""
    try:
        raw = await mcp_client.call_tool(USER_TIMEZONE_TOOL, {}, user_id=user_id)
        email = json.loads(raw).get("email")
    except Exception as e:
        logger.warning(f"Email of user {user_id} unreadable: {e!r}")
        return None
    return email.strip().lower() if isinstance(email, str) and email.strip() else None


# An account's email never changes, so a read answer is kept for good; an unreadable one is not.
_technical: dict[str, bool] = {}


async def is_technical_account(user_id: str) -> bool:
    """Whether the user is the smoke or the recette account. Unreadable email: not technical, and asked again."""
    known = _technical.get(user_id)
    if known is not None:
        return known
    email = await resolve_user_email(user_id)
    if email is None:
        return False
    _technical[user_id] = email in TECHNICAL_ACCOUNT_EMAILS
    return _technical[user_id]

import json
import logging
import time
from zoneinfo import ZoneInfo

from app.config import settings
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

USER_TIMEZONE_TOOL = "get_user_timezone"

# A chat turn reads the timezone from several places (prompt, web search, memory, time tool); the MCP
# round trip is paid once per window instead of once per place.
TIMEZONE_FRESH_SECONDS = 60

known_timezones: dict[str, ZoneInfo] = {}
read_at: dict[str, float] = {}


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
        name = json.loads(raw)["timezone"]
    except Exception as e:
        fallback = known or ZoneInfo(settings.planning_timezone)
        logger.warning(f"Timezone of user {user_id} unreadable, keeping {fallback.key}: {e!r}")
        return fallback

    read_at[user_id] = time.monotonic()

    try:
        known_timezones[user_id] = ZoneInfo(name)
    except Exception as e:
        logger.warning(f"Invalid timezone {name!r} for user {user_id}, planning on {settings.planning_timezone}: {e!r}")
        known_timezones[user_id] = ZoneInfo(settings.planning_timezone)
    return known_timezones[user_id]

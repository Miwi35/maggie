import json
import logging
from zoneinfo import ZoneInfo

from app.config import settings
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

USER_TIMEZONE_TOOL = "get_user_timezone"

known_timezones: dict[str, ZoneInfo] = {}


async def resolve_user_timezone(user_id: str) -> ZoneInfo:
    """The timezone stored in the user's preferences.

    A stored name that is not a timezone means Europe/Paris. A preference that cannot be read right now
    (API restarting) keeps the last timezone read for the user: falling back to Paris for one cycle would
    skip a New York user's planning moment for the whole day.
    """
    try:
        raw = await mcp_client.call_tool(USER_TIMEZONE_TOOL, {}, user_id=user_id)
        name = json.loads(raw)["timezone"]
    except Exception as e:
        known = known_timezones.get(user_id) or ZoneInfo(settings.planning_timezone)
        logger.warning(f"Timezone of user {user_id} unreadable, keeping {known.key}: {e!r}")
        return known

    try:
        known_timezones[user_id] = ZoneInfo(name)
    except Exception as e:
        logger.warning(f"Invalid timezone {name!r} for user {user_id}, planning on {settings.planning_timezone}: {e!r}")
        known_timezones[user_id] = ZoneInfo(settings.planning_timezone)
    return known_timezones[user_id]

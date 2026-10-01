import asyncio
import json
import logging
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.proaction_repository import proaction_repo
from app.llm.gateway import LLMGateway
from app.mcp.client import mcp_client
from app.queue.proaction_publisher import publish_proaction

logger = logging.getLogger(__name__)

EXECUTION_INTERVAL = 60  # seconds
PLANNING_REFRESH = 900  # seconds: how often new users and changed timezones are picked up
USER_TIMEZONE_TOOL = "get_user_timezone"

DAILY_PLANNING_PROMPT = (
    "C'est le début de la journée. "
    '1. Appelle list_instructions avec kind="planning" pour lire les directives de planification. '
    "2. Appelle list_proactions pour voir les proactions déjà planifiées. "
    "3. Planifie de nouvelles proactions avec schedule_proaction en te basant sur "
    "les instructions de l'utilisateur, son calendrier et ses habitudes. "
    "Respecte les contraintes horaires indiquées dans les instructions."
)

_tasks: list[asyncio.Task] = []


async def _execution_loop() -> None:
    """Poll for due proactions every 60s and publish them to RabbitMQ."""
    while True:
        try:
            due = await proaction_repo.find_due()
            if due:
                logger.info(f"Found {len(due)} due proactions")
            for proaction in due:
                await publish_proaction(proaction.id)
        except Exception as e:
            logger.error(f"Execution loop error: {e}")

        await asyncio.sleep(EXECUTION_INTERVAL)


def _utcnow() -> datetime:
    return datetime.now(UTC)


def next_planning_time(now: datetime, tz: ZoneInfo | None = None) -> datetime:
    """Next daily planning moment (UTC): the configured hour on the wall clock of `tz`.

    `tz` is the user's timezone; the default one (`planning_timezone`) stands in when it is unknown.
    """
    tz = tz or ZoneInfo(settings.planning_timezone)
    local_now = now.astimezone(tz)
    day = local_now.date()
    target = datetime.combine(day, time(settings.daily_planning_hour), tzinfo=tz)
    if target <= local_now:
        target = datetime.combine(day + timedelta(days=1), time(settings.daily_planning_hour), tzinfo=tz)
    return target.astimezone(UTC)


async def resolve_user_timezone(user_id: str) -> ZoneInfo:
    """The timezone stored in the user's preferences, the default one when it cannot be read or is invalid."""
    try:
        raw = await mcp_client.call_tool(USER_TIMEZONE_TOOL, {}, user_id=user_id)
        return ZoneInfo(json.loads(raw)["timezone"])
    except Exception as e:
        logger.warning(f"No usable timezone for user {user_id}, planning on {settings.planning_timezone}: {e!r}")
        return ZoneInfo(settings.planning_timezone)


async def plan_user(gateway: LLMGateway, user_id: str) -> None:
    try:
        logger.info(f"Running daily proaction planning for user {user_id}")
        result = await gateway.proaction(DAILY_PLANNING_PROMPT, user_id, silent=True)
        logger.info(f"Daily planning complete for user {user_id}: {result['response'][:200]}")
    except Exception as e:
        logger.error(f"Daily planning failed for user {user_id}: {e}")


async def plan_all_users(gateway: LLMGateway) -> None:
    """Run the planning prompt once for every user who has stored a planning directive.

    Behaviour preferences are deliberately not a reason to plan: « tutoie-moi » says
    nothing about when to act, and planning on it alone would wake the model up for a
    user who never asked for a single proaction (MAG-22).
    """
    for user_id in await instruction_repo.find_user_ids(kind=InstructionKind.PLANNING):
        await plan_user(gateway, user_id)


async def planning_schedule(since: datetime) -> dict[str, datetime]:
    """When each user with a planning directive is next planned after `since`, on their own wall clock."""
    user_ids = await instruction_repo.find_user_ids(kind=InstructionKind.PLANNING)
    return {user_id: next_planning_time(since, await resolve_user_timezone(user_id)) for user_id in user_ids}


async def run_planning_cycle(gateway: LLMGateway, since: datetime) -> datetime:
    """Sleep until the next user is due (PLANNING_REFRESH at most), then plan the due ones.

    Returns the instant up to which planning moments have been handled: the next cycle starts there,
    so a moment that falls while the model is busy with another user is not lost.
    """
    schedule = await planning_schedule(since)
    now = _utcnow()
    wake = min([*schedule.values(), now + timedelta(seconds=PLANNING_REFRESH)])
    logger.info(f"Daily planning: {len(schedule)} user(s), next check at {wake.isoformat()}")
    await asyncio.sleep(max(0.0, (wake - now).total_seconds()))

    checked_until = _utcnow()
    for user_id in [user_id for user_id, target in schedule.items() if target <= checked_until]:
        await plan_user(gateway, user_id)
    return checked_until


async def _daily_planning_loop() -> None:
    """Every day at the planning hour of each user's own timezone, ask Maggie to plan/manage their proactions."""
    gateway = LLMGateway()
    since = _utcnow()

    while True:
        try:
            since = await run_planning_cycle(gateway, since)
        except Exception as e:
            logger.error(f"Daily planning loop error: {e}")
            await asyncio.sleep(EXECUTION_INTERVAL)


async def start_scheduler() -> None:
    """Start both scheduler loops as background tasks."""
    _tasks.append(asyncio.create_task(_execution_loop()))
    _tasks.append(asyncio.create_task(_daily_planning_loop()))
    logger.info("Proaction scheduler started")

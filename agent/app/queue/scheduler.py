import asyncio
import json
import logging
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.context_model import ContextStatus, ConversationContext
from app.db.context_repository import context_repo
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.proaction_repository import proaction_repo
from app.llm.context_summary import context_summarizer
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
_known_timezones: dict[str, ZoneInfo] = {}


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
    """The timezone stored in the user's preferences.

    A stored name that is not a timezone means Europe/Paris. A preference that cannot be read right now
    (API restarting) keeps the last timezone read for the user: falling back to Paris for one cycle would
    skip a New York user's planning moment for the whole day.
    """
    try:
        raw = await mcp_client.call_tool(USER_TIMEZONE_TOOL, {}, user_id=user_id)
        name = json.loads(raw)["timezone"]
    except Exception as e:
        known = _known_timezones.get(user_id) or ZoneInfo(settings.planning_timezone)
        logger.warning(f"Timezone of user {user_id} unreadable, keeping {known.key}: {e!r}")
        return known

    try:
        _known_timezones[user_id] = ZoneInfo(name)
    except Exception as e:
        logger.warning(f"Invalid timezone {name!r} for user {user_id}, planning on {settings.planning_timezone}: {e!r}")
        _known_timezones[user_id] = ZoneInfo(settings.planning_timezone)
    return _known_timezones[user_id]


async def plan_user(gateway: LLMGateway, user_id: str) -> None:
    try:
        logger.info(f"Running daily proaction planning for user {user_id}")
        result = await gateway.proaction(DAILY_PLANNING_PROMPT, user_id, silent=True)
        logger.info(f"Daily planning complete for user {user_id}: {result['response'][:200]}")
    except Exception as e:
        logger.error(f"Daily planning failed for user {user_id}: {e}")


async def planning_schedule(since: datetime) -> dict[str, datetime]:
    """When each user with a planning directive is next planned after `since`, on their own wall clock.

    Behaviour preferences are deliberately not a reason to plan: « tutoie-moi » says
    nothing about when to act, and planning on it alone would wake the model up for a
    user who never asked for a single proaction (MAG-22).
    """
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


async def _retire_context(ctx: ConversationContext, status: ContextStatus, idle_before: datetime) -> bool:
    """Write the thread's last summary, then move it to `status`. False if it was spoken in meanwhile."""
    context_id = str(ctx.id)
    try:
        # Never raises, and does nothing when no message came in since the last summary.
        await context_summarizer.summarize(context_id)
        return await context_repo.update_status(context_id, status, idle_before=idle_before) is not None
    except Exception as e:
        logger.error(f"Could not move context {context_id} to {status.value}: {e}")
        return False


async def run_context_lifecycle(now: datetime | None = None) -> dict[str, int]:
    """Active → dormant after N quiet hours, → closed after M quiet days, a summary before each (MAG-12).

    Closing goes first and takes dormant contexts as well as active ones: a thread quiet
    for weeks (the agent was down) is closed in one step, with one summary.
    """
    now = now or _utcnow()
    close_before = now - timedelta(days=settings.context_close_after_days)
    dormant_before = now - timedelta(hours=settings.context_dormant_after_hours)

    closed = 0
    for ctx in await context_repo.find_idle([ContextStatus.ACTIVE, ContextStatus.DORMANT], close_before):
        closed += await _retire_context(ctx, ContextStatus.CLOSED, close_before)

    dormant = 0
    for ctx in await context_repo.find_idle([ContextStatus.ACTIVE], dormant_before):
        dormant += await _retire_context(ctx, ContextStatus.DORMANT, dormant_before)

    if closed or dormant:
        logger.info(f"Context lifecycle: {dormant} went dormant, {closed} closed")
    return {"dormant": dormant, "closed": closed}


async def _context_lifecycle_loop() -> None:
    """Every few minutes, put quiet conversation contexts to sleep and close the long-forgotten ones."""
    while True:
        try:
            await run_context_lifecycle()
        except Exception as e:
            logger.error(f"Context lifecycle loop error: {e}")

        await asyncio.sleep(settings.context_lifecycle_interval_seconds)


async def start_scheduler() -> None:
    """Start the scheduler loops as background tasks."""
    _tasks.append(asyncio.create_task(_execution_loop()))
    _tasks.append(asyncio.create_task(_daily_planning_loop()))
    _tasks.append(asyncio.create_task(_context_lifecycle_loop()))
    logger.info("Proaction scheduler started")

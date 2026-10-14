import asyncio
import logging
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.context_model import ContextStatus, ConversationContext
from app.db.context_repository import context_repo
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.message_repository import message_repo
from app.db.pending_action_repository import pending_action_repo
from app.llm.context_summary import context_summarizer
from app.llm.gateway import LLMGateway
from app.llm.style_synthesis import style_synthesizer
from app.proactions import api as proactions_api
from app.proactions.legacy import migrate_legacy_proactions
from app.queue.proaction_publisher import publish_proaction
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

EXECUTION_INTERVAL = 60  # seconds
PLANNING_REFRESH = 900  # seconds: how often new users and changed timezones are picked up


def daily_planning_prompt(today: date) -> str:
    """What the daily planning is asked: today's proactions, from the planning instructions and the agenda.

    Named by date, so a second run the same day (a restart, the manual trigger) reads what the first
    one scheduled and leaves it be (MAG-327).
    """
    day = today.isoformat()
    return (
        f"C'est le début de la journée du {day}. "
        '1. Appelle list_instructions avec kind="planning" pour lire tes consignes de planification. '
        f"2. Lis l'agenda de l'utilisateur pour ce jour (get_events_by_date, date={day}) : "
        "une consigne qui dépend de l'agenda (« les jours où j'ai Ael… ») ne vaut que les jours où il le dit. "
        f'3. Appelle list_proactions avec from="{day}" et to="{day}" pour voir les proactions déjà posées ce jour-là. '
        "4. Pose avec schedule_proaction les proactions du jour que demandent les consignes, et seulement celles-là, "
        "sans recréer celles qui existent déjà ce jour-là. "
        "Respecte les contraintes horaires indiquées dans les consignes."
    )


_tasks: list[asyncio.Task] = []


async def dispatch_due_proactions() -> int:
    """Claim the due proactions on the API and queue them for execution; how many were queued.

    The API marks them running as it hands them over (MAG-326), so a proaction deleted from the
    agenda before its time is never claimed, and none is claimed twice. One that cannot be queued
    is recorded failed: left running, it would never run nor say why. An API that does not answer
    raises — the loop logs it and asks again a minute later.
    """
    due = await proactions_api.claim_due()
    if due:
        logger.info(f"Claimed {len(due)} due proactions")
    for proaction in due:
        try:
            await publish_proaction(proaction)
        except Exception as e:
            logger.error(f"Could not queue proaction {proaction.id}: {e}")
            try:
                await proactions_api.record(proaction, "failed", error=f"Could not queue the run: {e}")
            except Exception as record_error:
                logger.error(f"Could not record proaction {proaction.id} as failed: {record_error}")
    return len(due)


async def run_execution_tick() -> None:
    """One minute of the execution loop; never raises, so the next minute always comes."""
    try:
        # Until the old table is gone, its pending proactions are moved first (MAG-327).
        await migrate_legacy_proactions()
    except Exception as e:
        logger.error(f"Could not move the legacy proactions: {e}")

    try:
        await dispatch_due_proactions()
    except Exception as e:
        logger.error(f"Execution loop error: {e}")

    try:
        await pending_action_repo.expire_overdue()
    except Exception as e:
        logger.error(f"Could not expire overdue pending actions: {e}")


async def _execution_loop() -> None:
    """Claim the due proactions every 60s, queue them on RabbitMQ, and retire stale approvals.

    The two are unrelated but share a minute-grained clock, and an action nobody answered
    in 24 h is retired here rather than on read: the card has to leave the user's screen
    on its own (MAG-4).
    """
    while True:
        await run_execution_tick()
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


async def generate_proactions(
    gateway: LLMGateway, user_id: str, *, dry_run: bool = False, now: datetime | None = None
) -> dict:
    """Run the daily planning for one user and return what came out — the planner's and the manual trigger's one path.

    With `dry_run` the run plans nothing for real: `schedule_proaction` is simulated and the result lists it.
    """
    logger.info(f"Running daily proaction planning for user {user_id}")
    today = (now or datetime.now(UTC)).astimezone(await resolve_user_timezone(user_id)).date()
    prompt = daily_planning_prompt(today)
    if dry_run:
        return await gateway.proaction(prompt, user_id, silent=True, dry_run=True)
    return await gateway.proaction(prompt, user_id, silent=True)


async def plan_user(gateway: LLMGateway, user_id: str) -> None:
    try:
        result = await generate_proactions(gateway, user_id)
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


async def run_style_synthesis() -> int:
    """Read every user's new thumbs into style proposals (MAG-23); returns how many were proposed."""
    proposed = 0
    for user_id in await message_repo.find_rated_user_ids():
        proposed += len(await style_synthesizer.synthesize(user_id))
    return proposed


async def _style_synthesis_loop() -> None:
    """Once a day, turn the owner's thumbs into proposed directives. First pass at boot: a deploy must not delay it."""
    while True:
        try:
            await run_style_synthesis()
        except Exception as e:
            logger.error(f"Style synthesis loop error: {e}")

        await asyncio.sleep(settings.style_synthesis_interval_hours * 3600)


async def start_scheduler() -> None:
    """Start the scheduler loops as background tasks."""
    _tasks.append(asyncio.create_task(_execution_loop()))
    _tasks.append(asyncio.create_task(_daily_planning_loop()))
    _tasks.append(asyncio.create_task(_context_lifecycle_loop()))
    _tasks.append(asyncio.create_task(_style_synthesis_loop()))
    logger.info("Proaction scheduler started")

import asyncio
import logging
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.proaction_repository import proaction_repo
from app.llm.gateway import LLMGateway
from app.queue.proaction_publisher import publish_proaction

logger = logging.getLogger(__name__)

EXECUTION_INTERVAL = 60  # seconds

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


def next_planning_time(now: datetime) -> datetime:
    """Next daily planning moment (UTC): the configured hour on the wall clock of the planning timezone."""
    tz = ZoneInfo(settings.planning_timezone)
    local_now = now.astimezone(tz)
    day = local_now.date()
    target = datetime.combine(day, time(settings.daily_planning_hour), tzinfo=tz)
    if target <= local_now:
        target = datetime.combine(day + timedelta(days=1), time(settings.daily_planning_hour), tzinfo=tz)
    return target.astimezone(UTC)


async def plan_all_users(gateway: LLMGateway) -> None:
    """Run the planning prompt once for every user who has stored a planning directive.

    Behaviour preferences are deliberately not a reason to plan: « tutoie-moi » says
    nothing about when to act, and planning on it alone would wake the model up for a
    user who never asked for a single proaction (MAG-22).
    """
    for user_id in await instruction_repo.find_user_ids(kind=InstructionKind.PLANNING):
        try:
            logger.info(f"Running daily proaction planning for user {user_id}")
            result = await gateway.proaction(DAILY_PLANNING_PROMPT, user_id, silent=True)
            logger.info(f"Daily planning complete for user {user_id}: {result['response'][:200]}")
        except Exception as e:
            logger.error(f"Daily planning failed for user {user_id}: {e}")


async def _daily_planning_loop() -> None:
    """Every day, early in the morning, ask Maggie to plan/manage proactions for each user."""
    gateway = LLMGateway()

    while True:
        try:
            now = datetime.now(UTC)
            target = next_planning_time(now)
            wait_seconds = (target - now).total_seconds()
            logger.info(f"Daily planning scheduled in {wait_seconds:.0f}s (at {target.isoformat()})")
            await asyncio.sleep(wait_seconds)

            await plan_all_users(gateway)
        except Exception as e:
            logger.error(f"Daily planning loop error: {e}")
            await asyncio.sleep(EXECUTION_INTERVAL)


async def start_scheduler() -> None:
    """Start both scheduler loops as background tasks."""
    _tasks.append(asyncio.create_task(_execution_loop()))
    _tasks.append(asyncio.create_task(_daily_planning_loop()))
    logger.info("Proaction scheduler started")

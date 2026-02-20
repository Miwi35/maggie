import asyncio
import logging
from datetime import UTC, datetime

from app.db.proaction_repository import proaction_repo
from app.llm.gateway import LLMGateway
from app.queue.proaction_publisher import publish_proaction

logger = logging.getLogger(__name__)

EXECUTION_INTERVAL = 60  # seconds
DAILY_PLANNING_HOUR = 6  # 06:00 UTC

DAILY_PLANNING_PROMPT = (
    "C'est le début de la journée. Revois les proactions planifiées et planifie "
    "les tâches autonomes pour aujourd'hui. Utilise l'outil list_proactions pour voir "
    "ce qui est déjà planifié, puis utilise schedule_proaction pour planifier de "
    "nouvelles tâches si nécessaire. Prends en compte le calendrier de l'utilisateur "
    "et ses habitudes."
)

# Default user for autonomous planning — the single user of this personal assistant
PLANNING_USER_ID = "default"

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


async def _daily_planning_loop() -> None:
    """Once a day at 06:00 UTC, ask Maggie to plan/manage proactions."""
    gateway = LLMGateway()

    while True:
        now = datetime.now(UTC)
        # Calculate seconds until next 06:00 UTC
        target = now.replace(hour=DAILY_PLANNING_HOUR, minute=0, second=0, microsecond=0)
        if now >= target:
            # Already past 06:00 today, schedule for tomorrow
            target = target.replace(day=target.day + 1)
        wait_seconds = (target - now).total_seconds()

        logger.info(f"Daily planning scheduled in {wait_seconds:.0f}s (at {target.isoformat()})")
        await asyncio.sleep(wait_seconds)

        try:
            logger.info("Running daily proaction planning")
            result = await gateway.proaction(DAILY_PLANNING_PROMPT, PLANNING_USER_ID)
            logger.info(f"Daily planning complete: {result['response'][:200]}")
        except Exception as e:
            logger.error(f"Daily planning failed: {e}")


async def start_scheduler() -> None:
    """Start both scheduler loops as background tasks."""
    _tasks.append(asyncio.create_task(_execution_loop()))
    _tasks.append(asyncio.create_task(_daily_planning_loop()))
    logger.info("Proaction scheduler started")

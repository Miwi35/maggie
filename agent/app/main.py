import contextlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from starlette.responses import Response

from app.a2a import setup_a2a
from app.agents.registry import subagent_registry
from app.api.routes import router, streaming_gateway
from app.db.context_model import ConversationContext  # noqa: F401 — register model with AgentBase before create_all
from app.db.context_repository import context_repo
from app.db.instruction_model import Instruction  # noqa: F401 — register model with AgentBase before create_all
from app.db.memory_model import Memory  # noqa: F401 — register model with AgentBase before create_all
from app.db.models import Message  # noqa: F401 — register model with AgentBase before create_all
from app.db.pending_action_model import PendingAction  # noqa: F401 — register model with AgentBase before create_all
from app.db.personality_model import PersonalityConfig  # noqa: F401 — register model with AgentBase before create_all
from app.db.proaction_repository import proaction_repo
from app.db.skill_model import Skill  # noqa: F401 — register model with AgentBase before create_all
from app.db.user_setting_model import UserSetting  # noqa: F401 — register model with AgentBase before create_all
from app.e2e import setup_e2e
from app.e2e_coverage import setup_journey_coverage
from app.error_tracking import init_error_tracking
from app.llm.turns import turn_runner
from app.mcp.client import mcp_client
from app.queue import connection as queue_connection
from app.queue.proaction_consumer import start_consumer
from app.queue.scheduler import start_scheduler
from app.skills.index import skill_index

# Configure logging so app messages are visible
logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s: %(message)s")
logger = logging.getLogger(__name__)

# Before the app exists, so the FastAPI integration wraps it. No DSN, no-op.
init_error_tracking()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: init DB, MCP, RabbitMQ, scheduler, consumer."""
    logger.info("Starting Maggie Agent Hub...")

    # Ensure agent DB tables exist (messages, proactions, memory, contexts, etc.)
    try:
        await proaction_repo.ensure_table()
        logger.info("Agent database tables ready")
    except Exception as e:
        logger.warning(f"Could not create agent database tables: {e}")

    # Run migrations for new columns on existing tables
    try:
        await context_repo.run_migrations()
        logger.info("Agent database migrations applied")
    except Exception as e:
        logger.warning(f"Could not run agent database migrations: {e}")

    # Move skill files left in the old container directory into the database, then build the index from it
    try:
        imported = await skill_index.import_legacy_files()
        if imported:
            logger.info(f"Imported {imported} legacy skill files into the agent database")
    except Exception as e:
        logger.warning(f"Could not import legacy skill files: {e}")

    try:
        await skill_index.rebuild()
        logger.info(f"Skill index built: {len(skill_index.entries)} skills")
    except Exception as e:
        logger.warning(f"Could not build skill index: {e}")

    try:
        loaded = subagent_registry.load()
        logger.info(f"Sub-agent registry loaded: {loaded} agents")
    except Exception as e:
        logger.warning(f"Could not load sub-agents: {e}")

    # Connect to MCP server (best-effort; tools will be lazy-loaded if this fails)
    try:
        await mcp_client.connect()
    except Exception as e:
        logger.warning(f"Could not connect to MCP server at startup: {e}")

    # Connect to RabbitMQ
    try:
        await queue_connection.connect()
    except Exception as e:
        logger.warning(f"Could not connect to RabbitMQ at startup: {e}")

    # Start proaction consumer and scheduler (requires RabbitMQ)
    try:
        await start_consumer()
        await start_scheduler()
    except Exception as e:
        logger.warning(f"Could not start proaction consumer/scheduler: {e}")

    # A turn lost with the previous process is answered (or given up on) now, and again
    # every half-minute for the ones a process shutting down hands over later.
    turn_runner.start_sweeper(streaming_gateway)

    yield

    # Shutdown
    with contextlib.suppress(Exception):
        await turn_runner.shutdown()
    with contextlib.suppress(Exception):
        await queue_connection.disconnect()
    with contextlib.suppress(Exception):
        await mcp_client.disconnect()


app = FastAPI(
    title="Maggie Agent Hub",
    description="Personal AI Agent with MCP tool integration",
    version="0.1.0",
    root_path="/agent",
    lifespan=lifespan,
)

app.include_router(router)
setup_a2a(app)
setup_e2e(app)
# The nightly's coverage per journey (E2E_COVERAGE=1 only): nothing is installed otherwise.
setup_journey_coverage(app)


@app.get("/metrics", include_in_schema=False)
async def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

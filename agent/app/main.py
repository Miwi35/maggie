import contextlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.a2a import setup_a2a
from app.api.routes import router
from app.db.message_repository import message_repo
from app.db.proaction_repository import proaction_repo
from app.mcp.client import mcp_client
from app.queue import connection as queue_connection
from app.queue.proaction_consumer import start_consumer
from app.queue.scheduler import start_scheduler

# Configure logging so app messages are visible
logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: init DB, MCP, RabbitMQ, scheduler, consumer."""
    logger.info("Starting Maggie Agent Hub...")

    # Ensure shared DB table (agent_message) exists
    try:
        await message_repo.ensure_table()
        logger.info("Shared database table ready")
    except Exception as e:
        logger.warning(f"Could not create shared database table: {e}")

    # Ensure agent DB table (proaction) exists
    try:
        await proaction_repo.ensure_table()
        logger.info("Agent database table ready")
    except Exception as e:
        logger.warning(f"Could not create agent database table: {e}")

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

    yield

    # Shutdown
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

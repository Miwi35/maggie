import contextlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.mcp.client import mcp_client

# Configure logging so app messages are visible
logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: connect to MCP server on startup, disconnect on shutdown."""
    logger.info("Starting Maggie Agent Hub...")

    # Connect to MCP server (best-effort; tools will be lazy-loaded if this fails)
    try:
        await mcp_client.connect()
    except Exception as e:
        logger.warning(f"Could not connect to MCP server at startup: {e}")

    yield

    # Disconnect from MCP server
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

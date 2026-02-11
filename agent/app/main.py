import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: connect to MCP server on startup, disconnect on shutdown."""
    logger.info("Starting Maggie Agent Hub...")

    # Connect to MCP server
    try:
        await mcp_client.connect()
        logger.info("Connected to MCP server")
    except Exception as e:
        logger.warning(f"Could not connect to MCP server: {e}")

    yield

    # Disconnect from MCP server
    try:
        await mcp_client.disconnect()
        logger.info("Disconnected from MCP server")
    except Exception:
        pass


app = FastAPI(
    title="Maggie Agent Hub",
    description="Personal AI Agent with MCP tool integration",
    version="0.1.0",
    root_path="/agent",
    lifespan=lifespan,
)

app.include_router(router)

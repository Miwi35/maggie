import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class MercureSubscriber:
    """Subscribes to Mercure SSE topics to receive real-time business events.
    Phase 1: log-only — just listen and log events for debugging."""

    def __init__(self, topics: list[str] | None = None):
        self.hub_url = settings.mercure_public_url
        self.topics = topics or [
            "/api/calendars/{id}",
            "/api/events/{id}",
        ]

    async def listen(self) -> None:
        """Subscribe to Mercure SSE and log events. Runs indefinitely."""
        url = f"{self.hub_url}?{'&'.join(f'topic={t}' for t in self.topics)}"

        logger.info(f"Subscribing to Mercure topics: {self.topics}")

        async with httpx.AsyncClient(timeout=None) as client:
            try:
                async with client.stream("GET", url) as response:
                    async for line in response.aiter_lines():
                        if line.startswith("data:"):
                            data = line[5:].strip()
                            logger.info(f"Mercure event received: {data[:200]}")
            except Exception as e:
                logger.error(f"Mercure subscription error: {e}")

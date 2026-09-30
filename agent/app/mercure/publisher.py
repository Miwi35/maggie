import json
import logging

import httpx
import jwt

from app.config import settings

logger = logging.getLogger(__name__)


class MercurePublisher:
    """Publishes to the Mercure hub — privately: a public update ignores the subscriber's token claims."""

    def __init__(self):
        self.hub_url = settings.mercure_url
        self.jwt_secret = settings.mercure_jwt_secret

    def _generate_jwt(self, topics: list[str]) -> str:
        """Generate a Mercure publisher JWT."""
        payload = {"mercure": {"publish": topics}}
        return jwt.encode(payload, self.jwt_secret, algorithm="HS256")

    async def publish(self, topic: str, data: dict) -> None:
        """Publish a message to a Mercure topic."""
        token = self._generate_jwt([topic])

        async with httpx.AsyncClient() as client:
            response = await client.post(
                self.hub_url,
                data={"topic": topic, "data": json.dumps(data), "private": "on"},
                headers={"Authorization": f"Bearer {token}"},
            )

            if response.status_code == 200:
                logger.debug(f"Published to {topic}")
            else:
                logger.warning(f"Mercure publish failed ({response.status_code}): {response.text}")

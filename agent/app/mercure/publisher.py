import json
import logging
import time
import uuid

import httpx
import jwt

from app.config import settings

logger = logging.getLogger(__name__)

# Must equal MercureAccessToken::ISSUER (API) and the hub's MERCURE_TRUSTED_ISSUERS.
ISSUER = "maggie"
DETAIL_TYPE = "https://mercure.rocks/authorization-detail"
TOKEN_TTL_SECONDS = 300


class MercurePublisher:
    """Publishes to the Mercure hub — privately: a public update ignores the subscriber's token claims."""

    def __init__(self):
        self.hub_url = settings.mercure_url
        self.jwt_secret = settings.mercure_jwt_secret
        self.audience = settings.mercure_public_url

    def _generate_jwt(self, topics: list[str]) -> str:
        """Mint the OAuth 2.0 access token a Mercure 1.0 hub expects (RFC 9068, RFC 9396).

        `aud` is the hub's pinned `resource_identifier`, i.e. MERCURE_PUBLIC_URL.
        """
        now = int(time.time())
        payload = {
            "iss": ISSUER,
            "sub": "maggie-agent",
            "client_id": ISSUER,
            "aud": self.audience,
            "iat": now,
            "exp": now + TOKEN_TTL_SECONDS,
            "jti": f"urn:uuid:{uuid.uuid4()}",
            "authorization_details": [
                {
                    "type": DETAIL_TYPE,
                    "actions": ["publish"],
                    "topics": [{"match": topic} for topic in topics],
                }
            ],
        }
        return jwt.encode(payload, self.jwt_secret, algorithm="HS256", headers={"typ": "at+jwt"})

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

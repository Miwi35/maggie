"""When the model's provider is the one failing, not Maggie (MAG-448).

13-14 Oct.: the Anthropic credit ran out at 23:40. Every turn answered with the same
apology as a bug of ours, so the production smoke test rolled back a healthy release and
froze production all night. An error of the provider — no credit, a refused key, an
overload, a quota still exceeded after the SDK's own retries, no connection — is told
apart, so `/chat` can say so and the smoke test can warn instead of failing.
"""

import json
import logging
from datetime import datetime

import anthropic

from app.mcp.client import mcp_client
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

PROVIDER_UNAVAILABLE = "provider_unavailable"

# What only the owner can fix. An overload, a quota or a lost connection clears by itself:
# a push for each would be noise the owner can do nothing about.
ALERT_TITLES = {
    "credit": "Maggie ne peut plus répondre : crédit Anthropic épuisé",
    "auth": "Maggie ne peut plus répondre : clé Anthropic refusée",
}
ALERT_BODY = "Les fonctions hors Maggie (agenda, courses, finances…) continuent de marcher."


def provider_reason(error: BaseException) -> str | None:
    """`credit`, `auth`, `overloaded`, `rate_limited` or `connection`; None for an error of ours."""
    # APITimeoutError is a connection error too.
    if isinstance(error, anthropic.APIConnectionError):
        return "connection"
    if not isinstance(error, anthropic.APIStatusError):
        return None

    status = error.status_code
    detail = _error_detail(error)
    # An empty credit answers 400 invalid_request_error, told apart by its wording only.
    if status == 402 or detail["type"] == "billing_error" or "credit balance" in detail["message"]:
        return "credit"
    if status in (401, 403):
        return "auth"
    if status == 529 or detail["type"] == "overloaded_error":
        return "overloaded"
    if status == 429:
        return "rate_limited"
    return None


def _error_detail(error: anthropic.APIStatusError) -> dict[str, str]:
    body = error.body if isinstance(error.body, dict) else {}
    inner = body.get("error") if isinstance(body.get("error"), dict) else {}
    return {
        "type": str(inner.get("type") or ""),
        "message": f"{inner.get('message') or ''} {error.message}".lower(),
    }


async def alert_owner(user_id: str, reason: str, *, now: datetime | None = None) -> None:
    """Push « Maggie ne peut plus répondre » to the user whose turn failed, once a day at most.

    Through the existing channel, `manage_notifications`: it stores the notification and
    pushes it to the user's devices. What was already sent is read back from the same
    list, so every uvicorn worker agrees without a store of its own. Never raises: the turn
    has already failed, the alert must not make it worse.
    """
    title = ALERT_TITLES.get(reason)
    if title is None:
        return

    try:
        tz = await resolve_user_timezone(user_id)
        today = (now or datetime.now(tz)).astimezone(tz).date()

        listed = json.loads(
            await mcp_client.call_tool("manage_notifications", {"action": "list", "limit": 50}, user_id=user_id)
        )
        for notification in listed.get("notifications", []) if isinstance(listed, dict) else []:
            if notification.get("title") != title:
                continue
            sent = datetime.fromisoformat(notification.get("createdAt", ""))
            if sent.astimezone(tz).date() == today:
                return

        await mcp_client.call_tool(
            "manage_notifications",
            {"action": "create", "type": "proaction", "title": title, "body": ALERT_BODY},
            user_id=user_id,
        )
        logger.warning(f"Provider unavailable ({reason}): user {user_id} notified")
    except Exception as e:
        logger.error(f"Provider unavailable ({reason}), and notifying user {user_id} failed: {e!r}")

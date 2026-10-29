"""A notification for a Maggie message that arrives outside a conversation (MAG-687).

The message is already in the chat; the notification is what reaches the user who is not looking at it.
"""

import logging

from app.mcp.client import mcp_client
from app.tts.text import strip_delivery_tags

logger = logging.getLogger(__name__)

PREVIEW_LENGTH = 200
VOICE_PREVIEW = "Message vocal"


def message_preview(content: str, *, has_audio: bool = False) -> str:
    """The text without voice delivery tags, Markdown as is, cut at ~200 characters."""
    text = strip_delivery_tags(content or "")
    if not text:
        return VOICE_PREVIEW if has_audio else ""
    if len(text) > PREVIEW_LENGTH:
        return text[: PREVIEW_LENGTH - 1].rstrip() + "…"
    return text


async def notify_message(user_id: str, message_id: str, content: str, *, has_audio: bool = False) -> None:
    """Create the `message` notification pointing at the chat message. Logged on failure, never raised."""
    try:
        await mcp_client.call_tool(
            "manage_notifications",
            {
                "action": "create",
                "type": "message",
                "title": "Maggie",
                "body": message_preview(content, has_audio=has_audio),
                "relatedEntityIri": f"/chat?message={message_id}",
            },
            user_id=user_id,
        )
    except Exception as e:
        logger.warning(f"Could not notify user {user_id} of message {message_id}: {e!r}")

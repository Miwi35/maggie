import logging
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.db.chat_read_state_repository import UnknownMessage, chat_read_state_repo
from app.db.message_repository import message_repo
from app.domain.conversation.service import message_service
from app.mercure import topics as mercure_topics
from app.mercure.publisher import MercurePublisher
from app.tts.voice_message import VoiceMessageError, signed_audio_url

logger = logging.getLogger(__name__)

router = APIRouter()


read_publisher = MercurePublisher()


class ReadRequest(BaseModel):
    messageId: str = Field(min_length=1)


MAX_FEEDBACK_COMMENT_CHARS = 1000


class FeedbackRequest(BaseModel):
    """The owner's thumb on one of Maggie's answers (MAG-23)."""

    rating: Literal["up", "down"]
    comment: str | None = Field(default=None, max_length=MAX_FEEDBACK_COMMENT_CHARS)


@router.post("/messages/{message_id}/digest/opened")
async def digest_opened(message_id: str, user_id: str = Depends(get_current_user_id)):
    """The owner opened the digest screen: `openedAt` is set on the first call, later ones change nothing (MAG-808)."""
    status = await message_service.mark_digest_opened(user_id, message_id)
    if status == "unknown":
        raise HTTPException(status_code=404, detail="Digest not found")
    return {"status": status}


@router.get("/messages")
async def get_messages(
    user_id: str = Depends(get_current_user_id),
    after: str | None = Query(default=None, description="ISO timestamp for incremental sync"),
    before: str | None = Query(default=None, description="Message ID for cursor pagination (older messages)"),
    limit: int = Query(default=20, ge=1, le=50, description="Number of messages to return"),
    screen: str | None = Query(default=None, description="Only the messages of this screen, newest last (MAG-773)"),
    with_audio: bool = Query(default=False, alias="withAudio", description="Only the voice messages (MAG-773)"),
):
    """Get conversation messages for a user with cursor-based pagination."""
    if screen is not None or with_audio:
        messages = await message_repo.find_recent_filtered(user_id, screen=screen, with_audio=with_audio, limit=limit)
    elif before:
        messages = await message_repo.find_before(user_id, before_id=before, limit=limit)
    elif after:
        messages = await message_repo.find_after(user_id, after=after)
    else:
        messages = await message_repo.find_recent(user_id, limit=limit)

    return [msg.to_dict() for msg in messages]


@router.get("/chat/unread")
async def get_chat_unread(user_id: str = Depends(get_current_user_id)):
    """Where the user stopped reading and how many of Maggie's messages came after (MAG-687)."""
    return await chat_read_state_repo.get(user_id)


@router.put("/chat/read")
async def mark_chat_read(data: ReadRequest, user_id: str = Depends(get_current_user_id)):
    """Move the shared read position forward (never back), and tell the user's other screens."""
    try:
        state = await chat_read_state_repo.mark_read(user_id, data.messageId)
    except UnknownMessage:
        raise HTTPException(status_code=404, detail="Message not found") from None
    try:
        await read_publisher.publish(mercure_topics.for_user(mercure_topics.CHAT_READ, user_id), state)
    except Exception as e:
        logger.warning(f"Failed to publish chat read position: {e}")
    return state


@router.delete("/messages/{message_id}", status_code=204)
async def delete_message(message_id: str, user_id: str = Depends(get_current_user_id)):
    """Delete one of the caller's messages (MAG-342). 404 for another user's, like an unknown one."""
    if not await message_service.delete_for_user(message_id, user_id):
        raise HTTPException(status_code=404, detail="Message not found")


@router.get("/messages/{message_id}/audio")
async def get_message_audio(message_id: str, user_id: str = Depends(get_current_user_id)):
    """A short signed URL to play a voice message's audio (MAG-685), for the message's owner alone.

    403 with another user's token: the audio of one user is never readable by another.
    """
    message = await message_repo.get(message_id)
    if message is None:
        raise HTTPException(status_code=404, detail="Message not found")
    if message.user_id != user_id:
        raise HTTPException(status_code=403, detail="Not your message")
    if not message.audio:
        raise HTTPException(status_code=404, detail="This message has no audio")
    try:
        url, expires_at = await signed_audio_url(message)
    except VoiceMessageError as e:
        # Returned, not raised: a bucket that is off or down is not a bug for the error tracker.
        return JSONResponse(status_code=503, content={"detail": str(e)})
    return {**message.audio_attachment(), "url": url, "expiresAt": expires_at.isoformat()}


@router.put("/messages/{message_id}/feedback")
async def rate_message(message_id: str, data: FeedbackRequest, user_id: str = Depends(get_current_user_id)):
    """Thumb up or down on one of Maggie's answers, with an optional word (MAG-23). Rating again replaces it."""
    message = await message_repo.set_feedback(message_id, user_id, data.rating, (data.comment or "").strip())
    if message is None:
        raise HTTPException(status_code=404, detail="Answer not found")
    return message.to_dict()


@router.delete("/messages/{message_id}/feedback")
async def unrate_message(message_id: str, user_id: str = Depends(get_current_user_id)):
    """Take the thumb back off an answer."""
    message = await message_repo.set_feedback(message_id, user_id, None)
    if message is None:
        raise HTTPException(status_code=404, detail="Answer not found")
    return message.to_dict()


@router.get("/messages/search")
async def search_messages(
    user_id: str = Depends(get_current_user_id),
    q: str = Query(..., min_length=1, description="Search query"),
    limit: int = Query(default=20, ge=1, le=50),
):
    """Search messages by content."""
    messages = await message_repo.search(user_id, q, limit=limit)
    return [msg.to_dict() for msg in messages]


@router.get("/messages/context")
async def get_message_context(
    user_id: str = Depends(get_current_user_id),
    around: str = Query(..., description="Message ID to load context around"),
):
    """Load messages around a specific message (for search result navigation)."""
    result = await message_repo.find_around(user_id, around)
    return {
        "messages": [msg.to_dict() for msg in result["messages"]],
        "targetIndex": result["targetIndex"],
    }

import logging
from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from app.api.chat_routes import _store_reply
from app.auth import get_current_user_id
from app.container import container
from app.db.message_repository import message_repo
from app.db.pending_action_repository import pending_action_repo
from app.domain.approvals.errors import (
    ApprovalError,
    ApprovalExpired,
    ApprovalNotFound,
    ApprovalNotPending,
    UnknownChannel,
)
from app.llm.questions import choices_message

logger = logging.getLogger(__name__)

router = APIRouter()


class QuestionAnswer(BaseModel):
    choices: list[str] = Field(min_length=1)


async def _resume_after_answer(user_id: str, said: str, user_msg_id: str) -> None:
    try:
        result = await container.llm_gateway.chat(said, user_id, exclude_message_id=user_msg_id)
        await _store_reply(user_id, result)
    except Exception as e:
        logger.error(f"Turn resumed after the answer {user_msg_id} failed: {e}")


@router.post("/questions/{question_id}/answer")
async def answer_question(
    question_id: str,
    answer: QuestionAnswer,
    background_tasks: BackgroundTasks,
    user_id: str = Depends(get_current_user_id),
):
    """The owner ticks the boxes of a question Maggie asked, in the chat or from an interruption (MAG-717).

    The question is marked answered under a row lock, so two devices answering at once get a 409 for the
    second. The answer reads « A · B » in the thread as a message of the owner's, and Maggie's turn resumes
    from it in the background.
    """
    status, question_msg, block = await message_repo.answer_question(user_id, question_id, answer.choices)
    if status == "unknown":
        raise HTTPException(status_code=404, detail="Question not found")
    if status == "answered":
        raise HTTPException(status_code=409, detail="Question already answered")
    if status == "invalid":
        raise HTTPException(status_code=400, detail="Choices must be distinct options of the question")

    said = choices_message(block["answered"])
    user_msg = await message_repo.create(user_id=user_id, role="user", content=said, context_id=question_msg.context_id)
    background_tasks.add_task(_resume_after_answer, user_id, said, user_msg.id)
    return block


@router.get("/approvals")
async def get_approvals(
    status: Literal["pending", "decided"] = Query(default="pending"),
    since: str | None = Query(default=None, description="ISO date; with status=decided, the oldest decision served"),
    user_id: str = Depends(get_current_user_id),
):
    """The authenticated user's actions waiting for an answer (oldest first), or their recent decisions."""
    if status == "pending":
        actions = await pending_action_repo.find_pending(user_id)
        return [a.to_dict() for a in actions]
    try:
        since_at = datetime.fromisoformat(since.replace("Z", "+00:00")) if since else None
    except ValueError:
        since_at = None
    if since_at is None:
        raise HTTPException(status_code=400, detail="`since` must be an ISO 8601 date")
    if since_at.tzinfo is None:
        since_at = since_at.replace(tzinfo=UTC)
    actions = await pending_action_repo.find_decided(user_id, since_at)
    return [a.to_dict() for a in actions]


_APPROVAL_STATUS = {ApprovalNotFound: 404, ApprovalNotPending: 409, ApprovalExpired: 410, UnknownChannel: 400}


def _http_error(e: ApprovalError) -> HTTPException:
    return HTTPException(status_code=_APPROVAL_STATUS[type(e)], detail=str(e))


@router.post("/approvals/{approval_id}/approve")
async def approve_action(
    approval_id: str,
    background_tasks: BackgroundTasks,
    channel: str = Query(default="chat", description="Where the user answered: chat, notification or voice"),
    user_id: str = Depends(get_current_user_id),
):
    """Run the held call with its frozen arguments, record the outcome, and announce it in the background."""
    try:
        approved = await container.approvals.approve(approval_id, user_id=user_id, channel=channel)
    except ApprovalError as e:
        raise _http_error(e) from e
    background_tasks.add_task(container.approvals.announce, approved)
    return approved.action.to_dict()


@router.post("/approvals/{approval_id}/deny")
async def deny_action(
    approval_id: str,
    channel: str = Query(default="chat", description="Where the user answered: chat, notification or voice"),
    user_id: str = Depends(get_current_user_id),
):
    """Refuse the held call: nothing runs and the model is not called."""
    try:
        denied = await container.approvals.deny(approval_id, user_id=user_id, channel=channel)
    except ApprovalError as e:
        raise _http_error(e) from e
    return denied.to_dict()

import logging

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel

from app.auth import get_current_user_id
from app.db.message_repository import message_repo
from app.db.proaction_repository import proaction_repo
from app.llm.gateway import LLMGateway

logger = logging.getLogger(__name__)

router = APIRouter()

llm_gateway = LLMGateway()


class ChatRequest(BaseModel):
    message: str


class ChatResponse(BaseModel):
    response: str
    tool_calls: list[dict] = []
    messages: list[dict] = []


@router.get("/health")
async def health():
    return {"status": "ok", "service": "maggie-agent-hub"}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Send a message to the AI agent and get a response."""
    logger.info(f"Chat request from user {user_id}: {request.message[:100]}")

    user_msg = await message_repo.create(
        user_id=user_id, role="user", content=request.message
    )

    result = await llm_gateway.chat(request.message, user_id)

    assistant_msg = await message_repo.create(
        user_id=user_id, role="assistant", content=result["response"]
    )

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[user_msg.to_dict(), assistant_msg.to_dict()],
    )


@router.post("/proaction", response_model=ChatResponse)
async def proaction(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Execute a proaction prompt autonomously (no conversation memory)."""
    logger.info(f"Proaction request for user {user_id}: {request.message[:100]}")

    result = await llm_gateway.proaction(request.message, user_id)

    assistant_msg = await message_repo.create(
        user_id=user_id, role="assistant", content=result["response"]
    )

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[assistant_msg.to_dict()],
    )


@router.get("/messages")
async def get_messages(
    user_id: str = Depends(get_current_user_id),
    after: str | None = Query(default=None, description="ISO timestamp for incremental sync"),
):
    """Get conversation messages for a user, optionally filtered by timestamp."""
    if after:
        messages = await message_repo.find_after(user_id, after=after)
    else:
        messages = await message_repo.find_recent(user_id)

    return [msg.to_dict() for msg in messages]


@router.get("/proactions")
async def get_proactions(user_id: str = Depends(get_current_user_id)):
    """Get all proactions for the authenticated user."""
    proactions = await proaction_repo.find_by_user(user_id)
    return [p.to_dict() for p in proactions]

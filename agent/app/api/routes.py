import logging

from fastapi import APIRouter, Query
from pydantic import BaseModel

from app.db.message_repository import message_repo
from app.llm.gateway import LLMGateway

logger = logging.getLogger(__name__)

router = APIRouter()

llm_gateway = LLMGateway()


class ChatRequest(BaseModel):
    message: str
    user_id: str = "default"


class ChatResponse(BaseModel):
    response: str
    tool_calls: list[dict] = []
    messages: list[dict] = []


class ProactionRequest(BaseModel):
    user_id: str
    prompt: str


@router.get("/health")
async def health():
    return {"status": "ok", "service": "maggie-agent-hub"}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """Send a message to the AI agent and get a response."""
    logger.info(f"Chat request from user {request.user_id}: {request.message[:100]}")

    user_msg = await message_repo.create(
        user_id=request.user_id, role="user", content=request.message
    )

    result = await llm_gateway.chat(request.message, request.user_id)

    assistant_msg = await message_repo.create(
        user_id=request.user_id, role="assistant", content=result["response"]
    )

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[user_msg.to_dict(), assistant_msg.to_dict()],
    )


@router.post("/proaction", response_model=ChatResponse)
async def proaction(request: ProactionRequest):
    """Execute a proaction prompt autonomously (no conversation memory)."""
    logger.info(f"Proaction request for user {request.user_id}: {request.prompt[:100]}")

    result = await llm_gateway.proaction(request.prompt, request.user_id)

    assistant_msg = await message_repo.create(
        user_id=request.user_id, role="assistant", content=result["response"]
    )

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[assistant_msg.to_dict()],
    )


@router.get("/messages")
async def get_messages(
    user_id: str = Query(default="default"),
    after: str | None = Query(default=None, description="ISO timestamp for incremental sync"),
):
    """Get conversation messages for a user, optionally filtered by timestamp."""
    if after:
        messages = await message_repo.find_after(user_id, after=after)
    else:
        messages = await message_repo.find_recent(user_id)

    return [msg.to_dict() for msg in messages]

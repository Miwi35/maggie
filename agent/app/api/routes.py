import logging

from fastapi import APIRouter
from pydantic import BaseModel

from app.llm.gateway import LLMGateway
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)

router = APIRouter()

llm_gateway = LLMGateway()
mercure_publisher = MercurePublisher()


class ChatRequest(BaseModel):
    message: str
    user_id: str = "default"


class ChatResponse(BaseModel):
    response: str
    tool_calls: list[dict] = []


@router.get("/health")
async def health():
    return {"status": "ok", "service": "maggie-agent-hub"}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """Send a message to the AI agent and get a response."""
    logger.info(f"Chat request from user {request.user_id}: {request.message[:100]}")

    result = await llm_gateway.chat(request.message, request.user_id)

    # Publish response to Mercure for real-time delivery
    try:
        await mercure_publisher.publish(
            topic=f"/agent/chat/{request.user_id}",
            data={"response": result["response"], "tool_calls": result.get("tool_calls", [])},
        )
    except Exception as e:
        logger.warning(f"Failed to publish to Mercure: {e}")

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
    )

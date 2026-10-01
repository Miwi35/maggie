import json
import logging

import anthropic
import openai
from fastapi import APIRouter, Depends, HTTPException, Query, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.db.context_repository import context_repo
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.message_repository import message_repo
from app.db.proaction_repository import proaction_repo
from app.db.user_setting_repository import user_setting_repo
from app.llm.context_summary import context_summarizer
from app.llm.gateway import LLMGateway
from app.llm.streaming import StreamingGateway
from app.llm.transcription import transcribe_audio
from app.skills.index import render_markdown, skill_index
from app.tts.synthesis import DEFAULT_VOICE, VOICE_IDS, get_voices, synthesize_speech

logger = logging.getLogger(__name__)

router = APIRouter()

llm_gateway = LLMGateway()
streaming_gateway = StreamingGateway()


class ChatRequest(BaseModel):
    # Non-empty: an empty message has nothing to route, nothing to answer, and the model
    # refuses a conversation whose only turn is an empty string — a 422 naming the field
    # beats « Désolé, une erreur est survenue » (MAG-13).
    message: str = Field(min_length=1)


class ChatResponse(BaseModel):
    response: str
    tool_calls: list[dict] = []
    messages: list[dict] = []


class PersonalityUpdate(BaseModel):
    name: str | None = None
    language: str | None = None
    backstory: str | None = None


class InstructionCreate(BaseModel):
    content: str
    # Defaulted rather than required: every directive stored before MAG-22 was a
    # planning rule, and so is everything the admin's form sent until now.
    kind: InstructionKind = InstructionKind.PLANNING


class SkillCreate(BaseModel):
    name: str = Field(max_length=200)
    description: str
    tags: list[str]
    content: str


class SkillUpdate(BaseModel):
    description: str | None = None
    tags: list[str] | None = None
    content: str | None = None


@router.get("/health")
async def health():
    return {"status": "ok", "service": "maggie-agent-hub"}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Send a message to the AI agent and get a response."""
    logger.info(f"Chat request from user {user_id}: {request.message[:100]}")

    user_msg = await message_repo.create(user_id=user_id, role="user", content=request.message)

    result = await llm_gateway.chat(request.message, user_id, exclude_message_id=user_msg.id)

    # Both halves in the thread the question was routed into (MAG-13), and the thread
    # re-summarized — the same two sinks as `POST /agent/proaction` below. Before this,
    # nothing on this path was ever tagged: the exchange existed outside every thread, so
    # the Mind panel never saw it and no summary could be written from it.
    context_id = result.get("context_id")
    assistant_msg = await message_repo.create(
        user_id=user_id, role="assistant", content=result["response"], context_id=context_id
    )
    # Not on a turn the model never answered: the summary would be a second call, as
    # doomed as the first, with the user still waiting on this request — and it would read
    # an apology as if it were the conversation.
    if context_id and not result.get("error"):
        await context_summarizer.maybe_summarize(context_id)

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[user_msg.to_dict(), assistant_msg.to_dict()],
    )


@router.post("/chat/stream")
async def chat_stream(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Stream a chat response using AG-UI protocol (Server-Sent Events)."""
    logger.info(f"Stream chat request from user {user_id}: {request.message[:100]}")

    user_msg = await message_repo.create(user_id=user_id, role="user", content=request.message, publish=False)

    async def generate():
        async for event in streaming_gateway.chat_stream(request.message, user_id, user_msg.id):
            yield f"data: {json.dumps(event)}\n\n"

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/contexts")
async def get_contexts(user_id: str = Depends(get_current_user_id)):
    """Get active and dormant contexts for the Mind Panel."""
    contexts = await context_repo.find_active(user_id)
    return [c.to_dict() for c in contexts]


@router.post("/proaction", response_model=ChatResponse)
async def proaction(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Execute a proaction prompt autonomously, reading the open threads but no message history."""
    logger.info(f"Proaction request for user {user_id}: {request.message[:100]}")

    result = await llm_gateway.proaction(request.message, user_id)

    # Stored in the thread the gateway routed it to, so a reply to it stays in the same
    # one — and that thread is re-summarized, since the reply will be routed against its
    # summary. Both are what `proaction_consumer` does with a scheduled proaction
    # (MAG-14): a proaction has two sinks, and they must leave a thread in the same
    # state. Where they still differ is the empty answer, which the consumer drops and
    # this route stores, because `ChatResponse` owes its caller a message either way.
    # Awaited, not spawned: nothing is streaming, and `maybe_summarize` never raises.
    context_id = result.get("context_id")
    assistant_msg = await message_repo.create(
        user_id=user_id,
        role="assistant",
        content=result["response"],
        context_id=context_id,
    )
    if context_id:
        await context_summarizer.maybe_summarize(context_id)

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[assistant_msg.to_dict()],
    )


@router.get("/messages")
async def get_messages(
    user_id: str = Depends(get_current_user_id),
    after: str | None = Query(default=None, description="ISO timestamp for incremental sync"),
    before: str | None = Query(default=None, description="Message ID for cursor pagination (older messages)"),
    limit: int = Query(default=20, ge=1, le=50, description="Number of messages to return"),
):
    """Get conversation messages for a user with cursor-based pagination."""
    if before:
        messages = await message_repo.find_before(user_id, before_id=before, limit=limit)
    elif after:
        messages = await message_repo.find_after(user_id, after=after)
    else:
        messages = await message_repo.find_recent(user_id, limit=limit)

    return [msg.to_dict() for msg in messages]


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


@router.get("/proactions")
async def get_proactions(user_id: str = Depends(get_current_user_id)):
    """Get all proactions for the authenticated user."""
    proactions = await proaction_repo.find_by_user(user_id)
    return [p.to_dict() for p in proactions]


@router.get("/personality")
async def get_personality(user_id: str = Depends(get_current_user_id)):
    """Return current personality configuration."""
    return await llm_gateway.personality.get_config(user_id)


@router.put("/personality")
async def update_personality(data: PersonalityUpdate, user_id: str = Depends(get_current_user_id)):
    """Update personality configuration and reload."""
    updates = data.model_dump(exclude_none=True)
    return await llm_gateway.personality.update_config(user_id, updates)


MAX_AUDIO_SIZE = 25 * 1024 * 1024  # 25 MB (Whisper limit)


@router.post("/transcribe")
async def transcribe(audio: UploadFile, _user_id: str = Depends(get_current_user_id)):
    """Transcribe audio via Whisper STT + Claude cleanup."""
    contents = await audio.read()
    if not contents:
        raise HTTPException(status_code=400, detail="Empty audio file")
    if len(contents) > MAX_AUDIO_SIZE:
        raise HTTPException(status_code=400, detail="Audio file exceeds 25 MB limit")

    try:
        result = await transcribe_audio(contents, audio.filename or "audio.webm")
    except openai.RateLimitError as exc:
        logger.warning("Whisper quota exhausted: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Quota OpenAI dépassé — recharge le compte pour réactiver la transcription.",
        ) from exc
    except anthropic.RateLimitError as exc:
        logger.warning("Anthropic rate-limited during transcription cleanup: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Anthropic est temporairement rate-limité, réessaie dans quelques instants.",
        ) from exc
    except openai.AuthenticationError as exc:
        logger.error("OpenAI authentication failed: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Clé API OpenAI invalide — vérifie la configuration côté agent.",
        ) from exc
    except (openai.APIError, anthropic.APIError) as exc:
        logger.exception("LLM provider error during transcription")
        raise HTTPException(
            status_code=502,
            detail=f"Le service de transcription a échoué : {exc.__class__.__name__}",
        ) from exc

    return result


# --- TTS ---

MAX_TTS_CHARS = 5000


class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TTS_CHARS)
    voice: str = DEFAULT_VOICE


@router.get("/tts/voices")
async def tts_voices(_user_id: str = Depends(get_current_user_id)):
    """Return curated list of available TTS voices."""
    return get_voices()


@router.post("/tts/synthesize")
async def tts_synthesize(request: TtsRequest, _user_id: str = Depends(get_current_user_id)):
    """Synthesize text to MP3 audio via Edge TTS."""
    if request.voice not in VOICE_IDS:
        raise HTTPException(status_code=400, detail=f"Unknown voice: {request.voice}")

    return StreamingResponse(
        synthesize_speech(request.text, request.voice),
        media_type="audio/mpeg",
    )


@router.get("/tts/voice")
async def get_tts_voice(user_id: str = Depends(get_current_user_id)):
    """Get the user's preferred TTS voice."""
    setting = await user_setting_repo.get(user_id)
    return {"voice": setting.tts_voice if setting and setting.tts_voice else DEFAULT_VOICE}


@router.put("/tts/voice")
async def set_tts_voice(request: dict, user_id: str = Depends(get_current_user_id)):
    """Set the user's preferred TTS voice."""
    voice = request.get("voice", "")
    if voice not in VOICE_IDS:
        raise HTTPException(status_code=400, detail=f"Unknown voice: {voice}")
    await user_setting_repo.set_tts_voice(user_id, voice)
    return {"voice": voice}


# --- Instructions ---


@router.get("/instructions")
async def get_instructions(
    kind: InstructionKind | None = None,
    user_id: str = Depends(get_current_user_id),
):
    """List the authenticated user's instructions, of one kind or of all kinds."""
    instructions = await instruction_repo.find_by_user(user_id, kind=kind)
    return [i.to_dict() for i in instructions]


@router.post("/instructions", status_code=201)
async def create_instruction(data: InstructionCreate, user_id: str = Depends(get_current_user_id)):
    """Create a new instruction."""
    instruction = await instruction_repo.store(user_id, data.content, kind=data.kind)
    return instruction.to_dict()


@router.delete("/instructions/{instruction_id}")
async def delete_instruction(instruction_id: str, user_id: str = Depends(get_current_user_id)):
    """Delete an instruction of the authenticated user."""
    deleted = await instruction_repo.delete(user_id, instruction_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Instruction not found")
    return {"deleted": True}


# --- Skills ---


@router.get("/skills")
async def get_skills(_user_id: str = Depends(get_current_user_id)):
    """List all skills (name, description, tags)."""
    entries = skill_index.list_all()
    return [{"name": e.name, "description": e.description, "tags": e.tags} for e in entries]


@router.get("/skills/{name}")
async def get_skill_detail(name: str, _user_id: str = Depends(get_current_user_id)):
    """Get full skill content by name."""
    skill = await skill_index.get_skill(name)
    if skill is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {
        "name": skill.name,
        "description": skill.description,
        "tags": list(skill.tags or []),
        "content": render_markdown(skill.name, skill.description, list(skill.tags or []), skill.content),
    }


@router.post("/skills", status_code=201)
async def create_skill_endpoint(data: SkillCreate, user_id: str = Depends(get_current_user_id)):
    """Create a new skill."""
    entry = await skill_index.create(data.name, data.description, data.tags, data.content, user_id)
    return {"name": entry.name, "description": entry.description, "tags": entry.tags}


@router.put("/skills/{name}")
async def update_skill_endpoint(name: str, data: SkillUpdate, user_id: str = Depends(get_current_user_id)):
    """Update an existing skill."""
    entry = await skill_index.update(
        name,
        description=data.description,
        tags=data.tags,
        content=data.content,
        user_id=user_id,
    )
    if entry is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {"name": entry.name, "description": entry.description, "tags": entry.tags}


@router.delete("/skills/{name}")
async def delete_skill_endpoint(name: str, user_id: str = Depends(get_current_user_id)):
    """Delete a skill."""
    deleted = await skill_index.delete(name, user_id=user_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {"deleted": True}

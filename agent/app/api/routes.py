import logging

from fastapi import APIRouter, Depends, HTTPException, Query, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.db.instruction_repository import instruction_repo
from app.db.message_repository import message_repo
from app.db.proaction_repository import proaction_repo
from app.db.user_setting_repository import user_setting_repo
from app.llm.gateway import LLMGateway
from app.llm.transcription import transcribe_audio
from app.skills.index import skill_index
from app.tts.synthesis import DEFAULT_VOICE, VOICE_IDS, get_voices, synthesize_speech

logger = logging.getLogger(__name__)

router = APIRouter()

llm_gateway = LLMGateway()


class ChatRequest(BaseModel):
    message: str


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


class SkillCreate(BaseModel):
    name: str
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

    result = await llm_gateway.chat(request.message, user_id)

    assistant_msg = await message_repo.create(user_id=user_id, role="assistant", content=result["response"])

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

    assistant_msg = await message_repo.create(user_id=user_id, role="assistant", content=result["response"])

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

    result = await transcribe_audio(contents, audio.filename or "audio.webm")
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
async def get_instructions(user_id: str = Depends(get_current_user_id)):
    """List all instructions for the authenticated user."""
    instructions = await instruction_repo.find_by_user(user_id)
    return [i.to_dict() for i in instructions]


@router.post("/instructions", status_code=201)
async def create_instruction(data: InstructionCreate, user_id: str = Depends(get_current_user_id)):
    """Create a new instruction."""
    instruction = await instruction_repo.store(user_id, data.content)
    return instruction.to_dict()


@router.delete("/instructions/{instruction_id}")
async def delete_instruction(instruction_id: str, _user_id: str = Depends(get_current_user_id)):
    """Delete an instruction."""
    deleted = await instruction_repo.delete(instruction_id)
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
    content = skill_index.get(name)
    if content is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    entry = next((e for e in skill_index.entries if e.name == name), None)
    return {
        "name": name,
        "description": entry.description if entry else "",
        "tags": entry.tags if entry else [],
        "content": content,
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

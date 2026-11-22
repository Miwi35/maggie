import logging
from typing import Literal

import anthropic
import openai
from fastapi import APIRouter, Depends, Form, HTTPException, Query, UploadFile
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.db.user_setting_repository import user_setting_repo
from app.llm.transcription import CLEANUP_MODES, transcribe_audio
from app.memory.bucket import FakeBucket, ObjectNotFound
from app.memory.service import memory_service
from app.tts.synthesis import DEFAULT_VOICE, VOICE_IDS, SpeechUnavailableError, get_voices, synthesize_speech
from app.tts.voice_message import AUDIO_PREFIX

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/fake-bucket/{key:path}", include_in_schema=False)
async def read_fake_bucket(key: str, expires: int = Query(...), signature: str = Query(...)):
    """What a signed URL of the in-memory bucket points at (dev, e2e): S3's presigned GET, played by the agent."""
    bucket = memory_service.bucket
    # Only the voice messages' audio is ever signed: nothing else of the bucket is served here.
    if not isinstance(bucket, FakeBucket) or not key.startswith(AUDIO_PREFIX):
        raise HTTPException(status_code=404, detail="Not found")
    if not bucket.check_signature(key, expires, signature):
        raise HTTPException(status_code=403, detail="Invalid or expired signature")
    try:
        obj = await bucket.get(key)
    except ObjectNotFound as e:
        raise HTTPException(status_code=404, detail="Not found") from e
    return Response(content=obj.body, media_type=bucket.content_types.get(key, "application/octet-stream"))


MAX_AUDIO_SIZE = 25 * 1024 * 1024  # 25 MB (Whisper limit)


@router.post("/transcribe")
async def transcribe(
    audio: UploadFile,
    cleanup: str = Form(default="auto"),
    _user_id: str = Depends(get_current_user_id),
):
    """Transcribe audio via Whisper, and tidy it only if asked and only if needed.

    `cleanup=none` never reaches the model — what a conversation with Maggie sends,
    since she reads through a hesitation herself (MAG-222). `cleanup=auto`, the
    default, calls the fast model for a text destined to be written as is, and only
    when `needs_cleanup` says it would gain from it.
    """
    if cleanup not in CLEANUP_MODES:
        raise HTTPException(status_code=400, detail=f"cleanup must be one of {', '.join(CLEANUP_MODES)}")

    contents = await audio.read()
    if not contents:
        raise HTTPException(status_code=400, detail="Empty audio file")
    if len(contents) > MAX_AUDIO_SIZE:
        raise HTTPException(status_code=400, detail="Audio file exceeds 25 MB limit")

    try:
        result = await transcribe_audio(contents, audio.filename or "audio.webm", cleanup=cleanup)
    except openai.RateLimitError as exc:
        logger.warning("Whisper quota exhausted: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Quota OpenAI dépassé — rechargez le compte pour réactiver la transcription.",
        ) from exc
    except anthropic.RateLimitError as exc:
        logger.warning("Anthropic rate-limited during transcription cleanup: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Anthropic est temporairement rate-limité, réessayez dans quelques instants.",
        ) from exc
    except openai.AuthenticationError as exc:
        logger.error("OpenAI authentication failed: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Clé API OpenAI invalide — vérifiez la configuration côté agent.",
        ) from exc
    except (openai.APIError, anthropic.APIError) as exc:
        logger.exception("LLM provider error during transcription")
        raise HTTPException(
            status_code=502,
            detail=f"Le service de transcription a échoué : {exc.__class__.__name__}",
        ) from exc

    return result


MAX_TTS_CHARS = 5000


class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TTS_CHARS)
    voice: str = DEFAULT_VOICE
    # `live` streams to the phone as Maggie answers; `file` is a voice message made ahead of time.
    usage: Literal["live", "file"] = "live"


@router.get("/tts/voices")
async def tts_voices(_user_id: str = Depends(get_current_user_id)):
    """Return curated list of available TTS voices."""
    return get_voices()


@router.post("/tts/synthesize")
async def tts_synthesize(request: TtsRequest, _user_id: str = Depends(get_current_user_id)):
    """Synthesize text to MP3 audio in the chosen voice (Claire by ElevenLabs, Edge as the safety net)."""
    if request.voice not in VOICE_IDS:
        raise HTTPException(status_code=400, detail=f"Unknown voice: {request.voice}")

    audio = synthesize_speech(request.text, request.voice, usage=request.usage)
    # Pull the first chunk before answering: once StreamingResponse has sent its 200,
    # a failing provider can only cut the body short.
    try:
        first = await anext(audio, b"")
    except SpeechUnavailableError:
        # Returned, not raised: the error tracker reports every raised 5xx HTTPException, and
        # a provider outage the client already handles is not an issue to fix (MAG-429).
        return JSONResponse(status_code=502, content={"detail": "Le service de synthèse vocale est indisponible."})

    async def body():
        if first:
            yield first
        async for chunk in audio:
            yield chunk

    return StreamingResponse(body(), media_type="audio/mpeg")


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

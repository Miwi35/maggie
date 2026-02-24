import logging
import time

import anthropic
import openai

from app.config import settings
from app.metrics import record_llm_usage

logger = logging.getLogger(__name__)

CLEANUP_PROMPT = (
    "Tu es un assistant de transcription. L'utilisateur a dicté le texte suivant. "
    "Nettoie-le : supprime les hésitations (euh, hmm, ben...), corrige la grammaire et la ponctuation, "
    "mais garde le sens original et la langue utilisée. "
    "Retourne UNIQUEMENT le texte nettoyé, sans commentaire ni explication."
)


async def _whisper_transcribe(audio_bytes: bytes, filename: str) -> str:
    """Transcribe audio using OpenAI Whisper."""
    client = openai.AsyncOpenAI(api_key=settings.openai_api_key)
    transcript = await client.audio.transcriptions.create(
        model="whisper-1",
        file=(filename, audio_bytes),
        response_format="text",
    )
    return transcript.strip()


async def _cleanup_with_llm(raw_text: str) -> str:
    """Clean up raw transcript using Claude."""
    client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
    model = settings.anthropic_model
    t0 = time.monotonic()
    try:
        response = await client.messages.create(
            model=model,
            max_tokens=1024,
            messages=[
                {"role": "user", "content": f"{CLEANUP_PROMPT}\n\nTexte dicté :\n{raw_text}"},
            ],
        )
        duration = time.monotonic() - t0
        record_llm_usage(
            model=model,
            call_type="transcription_cleanup",
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            duration_seconds=duration,
        )
    except Exception:
        duration = time.monotonic() - t0
        record_llm_usage(
            model=model,
            call_type="transcription_cleanup",
            input_tokens=0,
            output_tokens=0,
            duration_seconds=duration,
            status="error",
        )
        raise
    return response.content[0].text.strip()


async def transcribe_audio(audio_bytes: bytes, filename: str) -> dict[str, str]:
    """Full pipeline: Whisper STT → Claude cleanup. Returns {raw, clean}."""
    raw = await _whisper_transcribe(audio_bytes, filename)
    if not raw:
        return {"raw": "", "clean": ""}

    clean = await _cleanup_with_llm(raw)
    return {"raw": raw, "clean": clean}

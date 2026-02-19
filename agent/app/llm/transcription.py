import logging

import anthropic
import openai

from app.config import settings

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
    response = await client.messages.create(
        model=settings.anthropic_model,
        max_tokens=1024,
        messages=[
            {"role": "user", "content": f"{CLEANUP_PROMPT}\n\nTexte dicté :\n{raw_text}"},
        ],
    )
    return response.content[0].text.strip()


async def transcribe_audio(audio_bytes: bytes, filename: str) -> dict[str, str]:
    """Full pipeline: Whisper STT → Claude cleanup. Returns {raw, clean}."""
    raw = await _whisper_transcribe(audio_bytes, filename)
    if not raw:
        return {"raw": "", "clean": ""}

    clean = await _cleanup_with_llm(raw)
    return {"raw": raw, "clean": clean}

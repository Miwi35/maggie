"""Edge TTS synthesis — streams MP3 audio from Microsoft neural voices."""

from collections.abc import AsyncGenerator

import edge_tts

# Curated French voices (fr-FR, fr-BE, fr-CA)
VOICES = [
    {"id": "fr-FR-DeniseNeural", "name": "Denise", "gender": "female", "locale": "fr-FR"},
    {"id": "fr-FR-EloiseNeural", "name": "Eloise", "gender": "female", "locale": "fr-FR"},
    {"id": "fr-FR-HenriNeural", "name": "Henri", "gender": "male", "locale": "fr-FR"},
    {"id": "fr-BE-CharlineNeural", "name": "Charline", "gender": "female", "locale": "fr-BE"},
    {"id": "fr-BE-GerardNeural", "name": "Gerard", "gender": "male", "locale": "fr-BE"},
    {"id": "fr-CA-SylvieNeural", "name": "Sylvie", "gender": "female", "locale": "fr-CA"},
    {"id": "fr-CA-AntoineNeural", "name": "Antoine", "gender": "male", "locale": "fr-CA"},
]

DEFAULT_VOICE = "fr-FR-DeniseNeural"

VOICE_IDS = {v["id"] for v in VOICES}


def get_voices() -> list[dict]:
    return VOICES


async def synthesize_speech(text: str, voice: str = DEFAULT_VOICE) -> AsyncGenerator[bytes, None]:
    """Stream MP3 chunks from Edge TTS."""
    communicate = edge_tts.Communicate(text, voice)
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            yield chunk["data"]

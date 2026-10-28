"""Speech synthesis (ADR-014): one voice for Maggie, Edge as the safety net.

`synthesize_speech` streams MP3 for the live voice; `synthesize_file` returns it whole for a
voice message. The provider follows the voice: Claire is ElevenLabs, the others are Edge. When
ElevenLabs fails to start a sentence — error, timeout, quota — that sentence is spoken by Edge,
with a log and a GlitchTip signal: Maggie is never mute.
"""

import logging
from collections.abc import AsyncGenerator

from app.config import settings
from app.error_tracking import TTS_FALLBACK, capture_signal
from app.metrics import TTS_FALLBACKS
from app.tts.providers import (
    SILENT_MP3_FRAME,
    EdgeProvider,
    ElevenLabsProvider,
    FakeProvider,
    ProviderFailedError,
    SpeechUnavailableError,
    TtsProvider,
    Usage,
)
from app.tts.text import prepare_text_for_tts

__all__ = [
    "DEFAULT_VOICE",
    "SILENT_MP3_FRAME",
    "VOICE_IDS",
    "SpeechUnavailableError",
    "fake_synthesis_requests",
    "get_voices",
    "reset_fake_synthesis_requests",
    "synthesize_file",
    "synthesize_speech",
]

logger = logging.getLogger(__name__)

CLAIRE_VOICE = "6vTyAgAT8PncODBcLjRf"
EDGE_FALLBACK_VOICE = "fr-FR-DeniseNeural"
DEFAULT_VOICE = CLAIRE_VOICE

# Claire first, then the curated Edge French voices (fr-FR, fr-BE, fr-CA).
VOICES = [
    {"id": CLAIRE_VOICE, "name": "Claire", "gender": "female", "locale": "fr-FR", "provider": "elevenlabs"},
    {"id": "fr-FR-DeniseNeural", "name": "Denise", "gender": "female", "locale": "fr-FR", "provider": "edge"},
    {"id": "fr-FR-EloiseNeural", "name": "Eloise", "gender": "female", "locale": "fr-FR", "provider": "edge"},
    {"id": "fr-FR-HenriNeural", "name": "Henri", "gender": "male", "locale": "fr-FR", "provider": "edge"},
    {"id": "fr-BE-CharlineNeural", "name": "Charline", "gender": "female", "locale": "fr-BE", "provider": "edge"},
    {"id": "fr-BE-GerardNeural", "name": "Gerard", "gender": "male", "locale": "fr-BE", "provider": "edge"},
    {"id": "fr-CA-SylvieNeural", "name": "Sylvie", "gender": "female", "locale": "fr-CA", "provider": "edge"},
    {"id": "fr-CA-AntoineNeural", "name": "Antoine", "gender": "male", "locale": "fr-CA", "provider": "edge"},
]

VOICE_IDS = {v["id"] for v in VOICES}
_PROVIDER_OF_VOICE = {v["id"]: v["provider"] for v in VOICES}

_edge = EdgeProvider()
_elevenlabs = ElevenLabsProvider()
_fake = FakeProvider()


def fake_synthesis_requests() -> int:
    return FakeProvider.requests


def reset_fake_synthesis_requests() -> None:
    FakeProvider.requests = 0


def get_voices() -> list[dict]:
    return VOICES


def _provider_for(voice: str) -> TtsProvider:
    """`TTS_PROVIDER` can force Edge or the fake one; otherwise the voice says who speaks it."""
    if settings.tts_provider == "fake":
        return _fake
    if settings.tts_provider == "edge" or _PROVIDER_OF_VOICE.get(voice) != "elevenlabs":
        return _edge
    return _elevenlabs


def _edge_voice(voice: str) -> str:
    return voice if _PROVIDER_OF_VOICE.get(voice) == "edge" else EDGE_FALLBACK_VOICE


async def synthesize_speech(
    text: str, voice: str = DEFAULT_VOICE, *, usage: Usage = "live"
) -> AsyncGenerator[bytes, None]:
    """Stream MP3 chunks of `text` in `voice`. Edge speaks the sentence if the chosen provider cannot start it."""
    text = prepare_text_for_tts(text)
    provider = _provider_for(voice)
    if provider is _edge:
        voice = _edge_voice(voice)

    if provider is not _elevenlabs:
        async for chunk in provider.stream(text, voice, usage):
            yield chunk
        return

    started = False
    try:
        async for chunk in provider.stream(text, voice, usage):
            started = True
            yield chunk
        return
    except Exception as exc:
        # Mid-stream the audio is already on its way: the sentence cannot be started over.
        if started:
            raise
        reason = exc.reason if isinstance(exc, ProviderFailedError) else "error"
        logger.warning("TTS provider %s failed (%s): Edge speaks this sentence", provider.name, exc)
    TTS_FALLBACKS.labels(provider=provider.name, reason=reason).inc()
    capture_signal(TTS_FALLBACK, provider=provider.name, reason=reason)

    async for chunk in _edge.stream(text, _edge_voice(voice), usage):
        yield chunk


async def synthesize_file(text: str, voice: str = DEFAULT_VOICE) -> bytes:
    """A whole MP3, at the quality tier of a voice message (the digest's voice tool, the car)."""
    return b"".join([chunk async for chunk in synthesize_speech(text, voice, usage="file")])

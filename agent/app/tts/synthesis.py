"""Speech synthesis — Edge TTS in real life, a fixed clip under e2e."""

import asyncio
import logging
import re
from collections.abc import AsyncGenerator

import edge_tts
from aiohttp import WSServerHandshakeError

from app.config import settings

logger = logging.getLogger(__name__)

# A valid, silent MP3 frame. The e2e provider streams this instead of calling
# Microsoft: journeys assert that audio arrived and that the player handles it,
# never what it sounds like, so a real voice would only add latency and a
# dependency on a service that can be down.
SILENT_MP3_FRAME = bytes.fromhex("fffb90c4" + "00" * 100)

# How many syntheses the fake provider was asked for since the last reset. The e2e
# journeys read it to prove an absence (« opening the voice overlay speaks nothing »,
# MAG-205) — a durable server-side record, where a screen label lives a few hundred
# milliseconds. Only ever moved under the fake provider, so it stays 0 in dev and prod.
_fake_synthesis_requests = 0


def fake_synthesis_requests() -> int:
    return _fake_synthesis_requests


def reset_fake_synthesis_requests() -> None:
    global _fake_synthesis_requests
    _fake_synthesis_requests = 0


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


# Microsoft's endpoint refuses the WebSocket upgrade now and then (MAG-401). One retry
# rides out a blip; a second refusal means the service is down, not that we were unlucky.
HANDSHAKE_ATTEMPTS = 2
HANDSHAKE_RETRY_DELAY_SECONDS = 0.5


class SpeechUnavailableError(Exception):
    """Edge TTS refused to start a synthesis: nothing was streamed yet."""


# ---------------------------------------------------------------------------
# Emoji → natural interjection mapping (French)
# The TTS will *act out* these emojis instead of ignoring or describing them.
# ---------------------------------------------------------------------------
EMOJI_INTERJECTIONS: dict[str, str] = {
    # Laughter / joy
    "😂": " ha ha ha ! ",
    "🤣": " ah ah ah ! ",
    "😆": " hé hé ! ",
    "😄": " ha ha ! ",
    "😁": " hé hé ! ",
    "😅": " hé hé, bon… ",
    "🙂": "",
    "😊": "",
    "☺️": "",
    # Surprise / shock
    "😮": " oh ! ",
    "😲": " oh là là ! ",
    "😱": " aah ! ",
    "🤯": " woh ! ",
    "😳": " oh ! ",
    # Sadness
    "😢": " … ",
    "😭": " ooh… ",
    "🥺": " … ",
    "😞": " pfff… ",
    "😔": " … ",
    # Thinking / doubt
    "🤔": " hmm… ",
    "🧐": " hmm… ",
    # Love / affection
    "❤️": "",
    "🥰": "",
    "😍": " oh ! ",
    "💕": "",
    "💖": "",
    "😘": "",
    # Anger / frustration
    "😡": " roh ! ",
    "😤": " pfff ! ",
    "🤬": "",
    # Relief / cool
    "😌": " aah… ",
    "😎": "",
    "🤗": "",
    # Disgust / meh
    "🤢": " beurk ! ",
    "🤮": " beurk ! ",
    "😒": " mouais… ",
    "🙄": " pfff… ",
    # Greeting / celebration
    "👋": "",
    "🎉": " yeah ! ",
    "🥳": " ouais ! ",
    "🎊": "",
    # Misc
    "🤷": " bof… ",
    "👍": "",
    "👎": "",
    "🤞": "",
    "💪": "",
    "🔥": "",
    "✨": "",
}

# Regex to strip any remaining emojis not in the map
_EMOJI_RE = re.compile(
    "[\U0001f600-\U0001f64f"  # emoticons
    "\U0001f300-\U0001f5ff"  # symbols & pictographs
    "\U0001f680-\U0001f6ff"  # transport & map
    "\U0001f900-\U0001f9ff"  # supplemental symbols
    "\U0001fa00-\U0001fa6f"  # chess symbols
    "\U0001fa70-\U0001faff"  # symbols extended-A
    "\U00002702-\U000027b0"  # dingbats
    "\U0000fe00-\U0000fe0f"  # variation selectors
    "\U0000200d"  # ZWJ
    "\U000020e3"  # combining enclosing keycap
    "]+",
    flags=re.UNICODE,
)


def prepare_text_for_tts(text: str) -> str:
    """Replace emojis with natural interjections, strip the rest."""
    for emoji, interjection in EMOJI_INTERJECTIONS.items():
        text = text.replace(emoji, interjection)

    # Remove any leftover emojis
    text = _EMOJI_RE.sub("", text)

    # Collapse multiple spaces / clean up
    text = re.sub(r"  +", " ", text).strip()

    return text


def get_voices() -> list[dict]:
    return VOICES


async def _synthesize_fake(text: str) -> AsyncGenerator[bytes, None]:
    """Stream a silent clip, one chunk per 50 characters of prepared text.

    Chunked rather than returned whole so the streaming path — the part that
    actually breaks — is the same one the journeys exercise.
    """
    chunks = max(1, (len(text) + 49) // 50)
    for _ in range(chunks):
        yield SILENT_MP3_FRAME


async def synthesize_speech(text: str, voice: str = DEFAULT_VOICE) -> AsyncGenerator[bytes, None]:
    """Stream MP3 chunks: from Edge TTS, or from the fake provider under e2e."""
    text = prepare_text_for_tts(text)

    if settings.tts_provider == "fake":
        global _fake_synthesis_requests
        _fake_synthesis_requests += 1
        async for chunk in _synthesize_fake(text):
            yield chunk
        return

    for attempt in range(1, HANDSHAKE_ATTEMPTS + 1):
        communicate = edge_tts.Communicate(text, voice)
        started = False
        try:
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    started = True
                    yield chunk["data"]
            return
        except WSServerHandshakeError as exc:
            # Mid-stream the response is already on its way: nothing to retry or to report.
            if started:
                raise
            logger.warning("Edge TTS refused the handshake (status %s), attempt %d", exc.status, attempt)
            if attempt == HANDSHAKE_ATTEMPTS:
                raise SpeechUnavailableError from exc
            await asyncio.sleep(HANDSHAKE_RETRY_DELAY_SECONDS)

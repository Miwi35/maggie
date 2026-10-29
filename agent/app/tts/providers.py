"""The speech providers behind `synthesis.py`: Edge, ElevenLabs, and the silent one e2e runs on."""

import asyncio
import logging
from collections.abc import AsyncGenerator
from typing import Literal, Protocol

import edge_tts
import httpx
from aiohttp import WSServerHandshakeError

from app.config import settings
from app.metrics import ELEVENLABS_CREDITS
from app.tts.text import strip_delivery_tags

logger = logging.getLogger(__name__)

# `live`: the voice answering in real time, where latency counts. `file`: a voice message
# produced ahead of time, where quality does.
Usage = Literal["live", "file"]

# A valid, silent MP3 frame. The e2e provider streams this instead of calling a real
# service: journeys assert that audio arrived and that the player handles it, never what it
# sounds like, so a real voice would only add latency and a dependency on a service that can be down.
SILENT_MP3_FRAME = bytes.fromhex("fffb90c4" + "00" * 413)
# A voice message needs a clip a browser can actually play for a moment: ~3 s of the same frame.
SILENT_MP3_CLIP = SILENT_MP3_FRAME * 115

# Microsoft's endpoint refuses the WebSocket upgrade now and then (MAG-401). One retry
# rides out a blip; a second refusal means the service is down, not that we were unlucky.
HANDSHAKE_ATTEMPTS = 2
HANDSHAKE_RETRY_DELAY_SECONDS = 0.5

ELEVENLABS_OUTPUT_FORMAT = "mp3_44100_128"
ELEVENLABS_LANGUAGE = "fr"


class SpeechUnavailableError(Exception):
    """Edge TTS refused to start a synthesis: nothing was streamed yet."""


class ProviderFailedError(Exception):
    """A provider could not start a synthesis. `reason` is a short label: quota, timeout, server…"""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason = reason


class TtsProvider(Protocol):
    """Streams MP3 chunks for a text. `file` and `live` only differ for providers with two quality tiers."""

    name: str

    def stream(self, text: str, voice: str, usage: Usage) -> AsyncGenerator[bytes, None]: ...


class FakeProvider:
    name = "fake"

    # How many syntheses were asked for since the last reset. The e2e journeys read it to prove
    # an absence (« opening the voice overlay speaks nothing », MAG-205) — a durable server-side
    # record, where a screen label lives a few hundred milliseconds. Only ever moved under the
    # fake provider, so it stays 0 in dev and prod.
    requests = 0

    async def stream(self, text: str, voice: str, usage: Usage) -> AsyncGenerator[bytes, None]:
        """Stream a silent clip, one chunk per 50 characters of prepared text.

        Chunked rather than returned whole so the streaming path — the part that
        actually breaks — is the same one the journeys exercise.
        """
        FakeProvider.requests += 1
        if usage == "file":
            yield SILENT_MP3_CLIP
            return
        for _ in range(max(1, (len(text) + 49) // 50)):
            yield SILENT_MP3_FRAME


class EdgeProvider:
    name = "edge"

    async def stream(self, text: str, voice: str, usage: Usage) -> AsyncGenerator[bytes, None]:
        text = strip_delivery_tags(text)
        if not text.strip():
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


def _failure_reason(status: int) -> str:
    if status == 429:
        return "quota"
    if status in (401, 402, 403):
        return "unauthorized"
    if status >= 500:
        return "server"
    return "error"


class ElevenLabsProvider:
    name = "elevenlabs"

    async def stream(self, text: str, voice: str, usage: Usage) -> AsyncGenerator[bytes, None]:
        if not settings.elevenlabs_api_key:
            raise ProviderFailedError("not_configured")
        model = settings.elevenlabs_model_file if usage == "file" else settings.elevenlabs_model_live
        timeout = httpx.Timeout(settings.elevenlabs_timeout_seconds)
        url = f"{settings.elevenlabs_base_url.rstrip('/')}/v1/text-to-speech/{voice}/stream"
        started = False
        try:
            async with (
                httpx.AsyncClient(timeout=timeout) as client,
                client.stream(
                    "POST",
                    url,
                    params={"output_format": ELEVENLABS_OUTPUT_FORMAT},
                    headers={"xi-api-key": settings.elevenlabs_api_key},
                    json={"text": text, "model_id": model, "language_code": ELEVENLABS_LANGUAGE},
                ) as response,
            ):
                if response.status_code != 200:
                    await response.aread()
                    raise ProviderFailedError(_failure_reason(response.status_code), f"status {response.status_code}")
                self._count_credits(response, text, model)
                async for chunk in response.aiter_bytes():
                    if chunk:
                        started = True
                        yield chunk
                if not started:
                    raise ProviderFailedError("empty", "no audio in a 200 response")
        except httpx.TimeoutException as exc:
            raise ProviderFailedError("timeout") from exc
        except httpx.HTTPError as exc:
            if started:
                raise
            raise ProviderFailedError("error", type(exc).__name__) from exc

    @staticmethod
    def _count_credits(response: httpx.Response, text: str, model: str) -> None:
        """What this sentence costs on the subscription: the API's own figure, the length of the text otherwise."""
        try:
            credits = int(response.headers["character-cost"])
        except (KeyError, ValueError):
            credits = len(text)
        ELEVENLABS_CREDITS.labels(model=model).inc(credits)
        logger.info("ElevenLabs synthesis: %d credits (%s, %d characters)", credits, model, len(text))

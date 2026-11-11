"""The speech providers behind `synthesis.py`: Edge, ElevenLabs, and the silent one e2e runs on."""

import asyncio
import base64
import json
import logging
import re
from collections.abc import AsyncGenerator
from typing import Literal, Protocol

import aiohttp
import edge_tts
import httpx
import lameenc
from aiohttp import WSServerHandshakeError

from app.config import settings
from app.metrics import ELEVENLABS_CREDITS, GRADIUM_CREDITS
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

GRADIUM_SAMPLE_RATE = 48000
GRADIUM_MP3_BITRATE = 128
# Gradium's free plan refuses a session longer than this.
GRADIUM_MAX_SESSION_CHARACTERS = 1500


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


def split_sentences(text: str, limit: int) -> list[str]:
    """Cut `text` into pieces of at most `limit` characters, at sentence ends (a hard cut on a longer sentence)."""
    pieces: list[str] = []
    current = ""
    for sentence in re.findall(r"[^.!?…\n]*[.!?…]+\s*|[^.!?…\n]+\s*|\n+", text):
        while len(sentence) > limit:
            cut = sentence.rfind(" ", 0, limit)
            cut = cut if cut > 0 else limit
            head, sentence = sentence[:cut], sentence[cut:]
            if current and len(current) + len(head) > limit:
                pieces.append(current)
                current = ""
            pieces.append(current + head)
            current = ""
        if current and len(current) + len(sentence) > limit:
            pieces.append(current)
            current = ""
        current += sentence
    if current:
        pieces.append(current)
    return [piece for piece in (p.strip() for p in pieces) if piece]


class GradiumProvider:
    name = "gradium"

    async def stream(self, text: str, voice: str, usage: Usage) -> AsyncGenerator[bytes, None]:
        if not settings.gradium_api_key:
            raise ProviderFailedError("not_configured")
        text = strip_delivery_tags(text)
        if not text.strip():
            return
        started = False
        try:
            for piece in split_sentences(text, GRADIUM_MAX_SESSION_CHARACTERS):
                async for chunk in self._session(piece, voice):
                    started = True
                    yield chunk
        except TimeoutError as exc:
            raise ProviderFailedError("timeout") from exc
        except aiohttp.ClientError as exc:
            if started:
                raise
            raise ProviderFailedError("error", type(exc).__name__) from exc
        if not started:
            raise ProviderFailedError("empty", "no audio from Gradium")

    async def _session(self, text: str, voice: str) -> AsyncGenerator[bytes, None]:
        """One WebSocket session: setup, the text, `end_of_stream`, then PCM chunks re-encoded as MP3."""
        timeout = settings.gradium_timeout_seconds
        encoder = lameenc.Encoder()
        encoder.set_bit_rate(GRADIUM_MP3_BITRATE)
        encoder.set_in_sample_rate(GRADIUM_SAMPLE_RATE)
        encoder.set_channels(1)
        encoder.set_quality(2)
        async with (
            aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, sock_connect=timeout)) as session,
            session.ws_connect(
                settings.gradium_base_url,
                headers={"x-api-key": settings.gradium_api_key},
                timeout=aiohttp.ClientWSTimeout(ws_receive=timeout),
            ) as ws,
        ):
            await ws.send_json({"type": "setup", "voice_id": voice, "model_name": "default", "output_format": "pcm"})
            await ws.send_json({"type": "text", "text": text})
            await ws.send_json({"type": "end_of_stream"})
            GRADIUM_CREDITS.inc(len(text))
            while True:
                message = await asyncio.wait_for(ws.receive(), timeout)
                if message.type != aiohttp.WSMsgType.TEXT:
                    if message.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSING, aiohttp.WSMsgType.CLOSED):
                        break
                    raise ProviderFailedError("error", f"websocket {message.type.name}")
                data = json.loads(message.data)
                kind = data.get("type")
                if kind == "error":
                    raise ProviderFailedError(_gradium_reason(data), str(data.get("message", ""))[:200])
                if kind == "audio":
                    mp3 = encoder.encode(base64.b64decode(data["audio"]))
                    if mp3:
                        yield bytes(mp3)
                elif kind == "end_of_stream":
                    break
        tail = encoder.flush()
        if tail:
            yield bytes(tail)


def _gradium_reason(data: dict) -> str:
    code = data.get("code")
    if code in (401, 403):
        return "unauthorized"
    if code in (402, 429):
        return "quota"
    return "error"

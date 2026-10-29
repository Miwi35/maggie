"""The e2e stack must synthesise speech without reaching Microsoft (MAG-94).

edge_tts opens its own WebSocket, so there is no base URL to redirect — the
only injection point is the provider switch these tests pin down.
"""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from aiohttp import WSServerHandshakeError

from app.tts.providers import SILENT_MP3_CLIP, SILENT_MP3_FRAME, SpeechUnavailableError
from app.tts.synthesis import synthesize_file, synthesize_speech
from app.tts.voice_message import mp3_duration_ms


async def collect(text: str) -> list[bytes]:
    return [chunk async for chunk in synthesize_speech(text)]


class TestFakeProvider:
    async def test_fake_provider_never_touches_edge_tts(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")

        with patch("app.tts.providers.edge_tts.Communicate") as communicate:
            chunks = await collect("Bonjour, je suis Maggie.")

        communicate.assert_not_called()
        assert chunks
        assert all(chunk == SILENT_MP3_FRAME for chunk in chunks)

    async def test_fake_provider_streams_more_than_one_chunk_for_long_text(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")

        short = await collect("Salut.")
        long = await collect("Salut. " * 40)

        # Journeys wait on a stream, not on a single response. One chunk for
        # everything would let a broken streaming path pass.
        assert len(short) == 1
        assert len(long) > 1

    async def test_fake_voice_message_is_a_whole_clip_of_valid_frames(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")

        clip = await synthesize_file("Salut.")

        assert clip == SILENT_MP3_CLIP
        assert len(SILENT_MP3_FRAME) == 417  # one MPEG-1 Layer III frame at 128 kbps / 44.1 kHz
        assert mp3_duration_ms(clip) > 2000

    async def test_fake_provider_yields_audio_even_for_empty_text(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")

        assert await collect("") == [SILENT_MP3_FRAME]


class TestDefaultProvider:
    async def test_default_still_goes_through_edge_tts(self, monkeypatch):
        # Dev and prod must be untouched by the e2e switch.
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")

        async def fake_stream():
            yield {"type": "audio", "data": b"real-audio"}
            yield {"type": "WordBoundary", "data": b""}

        communicate = MagicMock()
        communicate.stream = fake_stream

        with patch("app.tts.providers.edge_tts.Communicate", return_value=communicate) as factory:
            chunks = await collect("Bonjour.")

        factory.assert_called_once()
        assert chunks == [b"real-audio"]


def handshake_refused(status: int = 503) -> WSServerHandshakeError:
    return WSServerHandshakeError(MagicMock(), (), status=status, message="Invalid response status")


class RefusingThenAnswering:
    """Edge TTS as seen from production: Microsoft refuses the WebSocket upgrade `refusals` times."""

    def __init__(self, refusals: int):
        self.refusals = refusals
        self.attempts = 0

    def __call__(self, *_args):
        self.attempts += 1
        refuse = self.attempts <= self.refusals

        class Communicate:
            async def stream(self):
                if refuse:
                    raise handshake_refused()
                yield {"type": "audio", "data": b"real-audio"}

        return Communicate()


class TestRefusedHandshake:
    """MAG-401: a refused handshake used to escape as an unhandled error after a 200 had started."""

    async def test_a_single_refusal_is_retried(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")
        monkeypatch.setattr("app.tts.providers.asyncio.sleep", AsyncMock())
        factory = RefusingThenAnswering(refusals=1)
        monkeypatch.setattr("app.tts.providers.edge_tts.Communicate", factory)

        assert await collect("Bonjour.") == [b"real-audio"]
        assert factory.attempts == 2

    async def test_a_persistent_refusal_is_reported_as_unavailable_speech(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")
        monkeypatch.setattr("app.tts.providers.asyncio.sleep", AsyncMock())
        monkeypatch.setattr("app.tts.providers.edge_tts.Communicate", RefusingThenAnswering(refusals=99))

        with pytest.raises(SpeechUnavailableError):
            await collect("Bonjour.")

    def test_the_route_answers_502_instead_of_a_broken_200(self, monkeypatch, authed_client):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")
        monkeypatch.setattr("app.tts.providers.asyncio.sleep", AsyncMock())
        monkeypatch.setattr("app.tts.providers.edge_tts.Communicate", RefusingThenAnswering(refusals=99))

        response = authed_client.post("/tts/synthesize", json={"text": "Bonjour."})

        assert response.status_code == 502

    def test_the_route_still_streams_audio_when_the_handshake_succeeds(self, monkeypatch, authed_client):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")
        monkeypatch.setattr("app.tts.providers.edge_tts.Communicate", RefusingThenAnswering(refusals=0))

        response = authed_client.post("/tts/synthesize", json={"text": "Bonjour."})

        assert response.status_code == 200
        assert response.headers["content-type"] == "audio/mpeg"
        assert response.content == b"real-audio"

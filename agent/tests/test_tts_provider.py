"""The e2e stack must synthesise speech without reaching Microsoft (MAG-94).

edge_tts opens its own WebSocket, so there is no base URL to redirect — the
only injection point is the provider switch these tests pin down.
"""

from unittest.mock import MagicMock, patch

from app.tts.synthesis import SILENT_MP3_FRAME, synthesize_speech


async def collect(text: str) -> list[bytes]:
    return [chunk async for chunk in synthesize_speech(text)]


class TestFakeProvider:
    async def test_fake_provider_never_touches_edge_tts(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")

        with patch("app.tts.synthesis.edge_tts.Communicate") as communicate:
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

        with patch("app.tts.synthesis.edge_tts.Communicate", return_value=communicate) as factory:
            chunks = await collect("Bonjour.")

        factory.assert_called_once()
        assert chunks == [b"real-audio"]

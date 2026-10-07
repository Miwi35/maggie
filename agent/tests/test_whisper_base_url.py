"""Whisper must be redirectable to the e2e stand-in (MAG-94).

Transcription is the one LLM call the e2e journeys keep — MAG-95 replaces the
conversation model, not the speech-to-text — so it has to reach WireMock
instead of OpenAI, and nothing may change for dev and prod.
"""

from unittest.mock import AsyncMock, MagicMock, patch

from app.llm.transcription import _whisper_transcribe


def openai_client_spy() -> tuple[MagicMock, MagicMock]:
    """A stand-in AsyncOpenAI whose constructor arguments we can read back."""
    client = MagicMock()
    client.audio.transcriptions.create = AsyncMock(return_value="  bonjour  ")

    factory = MagicMock(return_value=client)

    return factory, client


class TestWhisperBaseUrl:
    async def test_configured_base_url_is_passed_to_the_client(self, monkeypatch):
        monkeypatch.setattr("app.llm.transcription.settings.openai_base_url", "http://wiremock:8080/openai/v1")
        monkeypatch.setattr("app.llm.transcription.settings.openai_api_key", "an-api-key")
        factory, _ = openai_client_spy()

        with patch("app.llm.transcription.openai.AsyncOpenAI", factory):
            await _whisper_transcribe(b"audio", "recording.webm")

        assert factory.call_args.kwargs["base_url"] == "http://wiremock:8080/openai/v1"

    async def test_empty_base_url_becomes_none_so_the_library_default_stands(self, monkeypatch):
        # Passing "" would make the client build requests against an empty
        # host, which fails as a connection error — far from the config that
        # caused it.
        monkeypatch.setattr("app.llm.transcription.settings.openai_base_url", "")
        monkeypatch.setattr("app.llm.transcription.settings.openai_api_key", "an-api-key")
        factory, _ = openai_client_spy()

        with patch("app.llm.transcription.openai.AsyncOpenAI", factory):
            await _whisper_transcribe(b"audio", "recording.webm")

        assert factory.call_args.kwargs["base_url"] is None

    async def test_transcript_is_trimmed(self, monkeypatch):
        monkeypatch.setattr("app.llm.transcription.settings.openai_base_url", "")
        monkeypatch.setattr("app.llm.transcription.settings.openai_api_key", "an-api-key")
        factory, _ = openai_client_spy()

        with patch("app.llm.transcription.openai.AsyncOpenAI", factory):
            assert (await _whisper_transcribe(b"audio", "recording.webm")).text == "bonjour"

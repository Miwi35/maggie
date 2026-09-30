"""Unit tests for the transcription service (Whisper + Claude cleanup)."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.transcription import transcribe_audio


class TestTranscriptionService:
    @pytest.mark.asyncio
    @patch("app.llm.transcription._cleanup_with_llm", new_callable=AsyncMock)
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_full_pipeline(self, mock_whisper, mock_cleanup):
        """transcribe_audio calls Whisper then Claude cleanup."""
        mock_whisper.return_value = "euh bonjour comment ça va"
        mock_cleanup.return_value = "Bonjour, comment ça va ?"

        result = await transcribe_audio(b"audio-bytes", "test.webm")

        assert result["raw"] == "euh bonjour comment ça va"
        assert result["clean"] == "Bonjour, comment ça va ?"
        mock_whisper.assert_called_once_with(b"audio-bytes", "test.webm")
        mock_cleanup.assert_called_once_with("euh bonjour comment ça va")

    @pytest.mark.asyncio
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_empty_transcript_skips_cleanup(self, mock_whisper):
        """When Whisper returns empty, cleanup is not called."""
        mock_whisper.return_value = ""

        result = await transcribe_audio(b"audio-bytes", "test.webm")

        assert result["raw"] == ""
        assert result["clean"] == ""

    @pytest.mark.asyncio
    @patch("app.llm.transcription.openai")
    async def test_whisper_called_with_correct_params(self, mock_openai_module):
        """_whisper_transcribe passes correct model and file to OpenAI."""
        from app.llm.transcription import _whisper_transcribe

        mock_client = AsyncMock()
        mock_openai_module.AsyncOpenAI.return_value = mock_client
        mock_client.audio.transcriptions.create = AsyncMock(return_value="Bonjour")

        result = await _whisper_transcribe(b"audio-data", "recording.webm")

        assert result == "Bonjour"
        mock_client.audio.transcriptions.create.assert_called_once_with(
            model="whisper-1",
            file=("recording.webm", b"audio-data"),
            response_format="text",
        )

    @pytest.mark.asyncio
    @patch("app.llm.transcription.create_llm_client")
    async def test_cleanup_called_with_raw_text(self, mock_create_client):
        """_cleanup_with_llm sends raw text to whichever client the provider switch hands it."""
        from app.llm.transcription import _cleanup_with_llm

        mock_client = AsyncMock()
        mock_create_client.return_value = mock_client
        mock_content_block = MagicMock()
        mock_content_block.text = "Texte propre."
        mock_usage = MagicMock()
        mock_usage.input_tokens = 50
        mock_usage.output_tokens = 10
        mock_response = MagicMock()
        mock_response.content = [mock_content_block]
        mock_response.usage = mock_usage
        mock_client.messages.create = AsyncMock(return_value=mock_response)

        result = await _cleanup_with_llm("texte sale euh")

        assert result == "Texte propre."
        mock_client.messages.create.assert_called_once()

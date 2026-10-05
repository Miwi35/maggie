"""Unit tests for the transcription service (Whisper, and the cleanup that is now the exception)."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.llm.transcription import (
    needs_cleanup,
    reset_cleanup_requests,
    strip_hesitations,
    transcribe_audio,
)


@pytest.fixture(autouse=True)
def fresh_cleanup_counter():
    reset_cleanup_requests()
    yield
    reset_cleanup_requests()


class TestTranscriptionService:
    @pytest.mark.asyncio
    @patch("app.llm.transcription._cleanup_with_llm", new_callable=AsyncMock)
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_a_text_that_needs_it_is_cleaned(self, mock_whisper, mock_cleanup):
        """A hesitant dictation goes through Whisper then the cleanup."""
        mock_whisper.return_value = "euh bonjour comment ça va"
        mock_cleanup.return_value = "Bonjour, comment ça va ?"

        result = await transcribe_audio(b"audio-bytes", "test.webm")

        assert result["raw"] == "euh bonjour comment ça va"
        assert result["clean"] == "Bonjour, comment ça va ?"
        mock_whisper.assert_called_once_with(b"audio-bytes", "test.webm")
        mock_cleanup.assert_called_once_with("euh bonjour comment ça va")

    @pytest.mark.asyncio
    @patch("app.llm.transcription._cleanup_with_llm", new_callable=AsyncMock)
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_a_clean_text_is_not_sent_to_the_model(self, mock_whisper, mock_cleanup):
        """In « auto », a transcript with nothing to fix costs no call (MAG-222)."""
        mock_whisper.return_value = "Ajoute des tomates à la liste de courses."

        result = await transcribe_audio(b"audio-bytes", "test.webm", cleanup="auto")

        assert result["clean"] == "Ajoute des tomates à la liste de courses."
        mock_cleanup.assert_not_called()

    @pytest.mark.asyncio
    @patch("app.llm.transcription._cleanup_with_llm", new_callable=AsyncMock)
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_cleanup_none_never_calls_the_model(self, mock_whisper, mock_cleanup):
        """What a conversation with Maggie asks for: the rule, never the model."""
        mock_whisper.return_value = "euh ajoute des tomates à la liste de courses"

        result = await transcribe_audio(b"audio-bytes", "test.webm", cleanup="none")

        assert result["raw"] == "euh ajoute des tomates à la liste de courses"
        assert result["clean"] == "ajoute des tomates à la liste de courses"
        mock_cleanup.assert_not_called()

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
    async def test_cleanup_runs_on_the_fast_model_and_counts(self, mock_create_client):
        """The cleanup nobody reads twice runs on Haiku, and the e2e counter sees it."""
        from app.llm.transcription import _cleanup_with_llm, cleanup_requests, settings

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
        assert mock_client.messages.create.call_args.kwargs["model"] == settings.anthropic_fast_model
        assert settings.anthropic_fast_model != settings.anthropic_model
        assert cleanup_requests() == 1


class TestNeedsCleanup:
    @pytest.mark.parametrize(
        "text",
        [
            "euh ajoute des tomates",
            "ben je crois que oui",
            "ajoute ajoute des tomates",
            "ajoute des tomates et des courgettes à la liste de courses du week-end",
        ],
        ids=["hesitation", "filler-ben", "repeated-word", "no-punctuation"],
    )
    def test_each_sign_asks_for_a_cleanup(self, text):
        assert needs_cleanup(text) is True

    @pytest.mark.parametrize(
        "text",
        [
            "Ajoute des tomates à la liste de courses.",
            "Oui",
            "demain matin",
            "Nous nous sommes vus hier soir chez Alex, c'était bien.",
        ],
        ids=["punctuated", "one-word", "short-answer", "reflexive-pronoun"],
    )
    def test_a_text_that_reads_well_is_left_alone(self, text):
        assert needs_cleanup(text) is False

    def test_an_empty_text_asks_for_nothing(self):
        assert needs_cleanup("   ") is False


class TestStripHesitations:
    @pytest.mark.parametrize(
        ("raw", "expected"),
        [
            ("euh ajoute des tomates", "ajoute des tomates"),
            ("ajoute euh des tomates", "ajoute des tomates"),
            ("ajoute des tomates euh", "ajoute des tomates"),
            ("Bonjour euh, ça va ?", "Bonjour, ça va ?"),
            ("Bonjour euh ! ça va ?", "Bonjour ! ça va ?"),
            ("ben hum ajoute des tomates", "ajoute des tomates"),
            ("Ajoute des tomates.", "Ajoute des tomates."),
        ],
        ids=[
            "leading",
            "middle",
            "trailing",
            "before-comma",
            "french-space-kept",
            "two-in-a-row",
            "nothing-to-do",
        ],
    )
    def test_isolated_fillers_go_away(self, raw, expected):
        assert strip_hesitations(raw) == expected

    def test_a_word_containing_a_filler_survives(self):
        assert strip_hesitations("Mets du beurre et des hummus") == "Mets du beurre et des hummus"

    def test_a_sentence_made_only_of_fillers_is_kept(self):
        """An empty bubble says less than « euh »."""
        assert strip_hesitations("euh hum") == "euh hum"

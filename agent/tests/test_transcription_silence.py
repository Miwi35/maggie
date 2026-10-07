"""Whisper must never put words on an audio that holds no speech (retour de recette MAG-222).

The owner saw « Thank you for watching » land in his chat without having said it:
Whisper, given silence, writes the subtitle boilerplate it was trained on. These are
the reproduction tests — a silent recording and a recording of plain noise must
produce no text at all, whatever the model answered.
"""

from unittest.mock import AsyncMock, patch

import pytest

from app.llm.transcription import (
    WhisperTranscript,
    holds_speech,
    looks_hallucinated,
    reset_cleanup_requests,
    transcribe_audio,
)


@pytest.fixture(autouse=True)
def fresh_cleanup_counter():
    reset_cleanup_requests()
    yield
    reset_cleanup_requests()


def segment(no_speech_prob: float = 0.02, avg_logprob: float = -0.3) -> dict:
    return {"no_speech_prob": no_speech_prob, "avg_logprob": avg_logprob}


def spoken(text: str = "ajoute des tomates à la liste de courses") -> WhisperTranscript:
    """What Whisper hands back for a sentence someone really said."""
    return WhisperTranscript(text=text, duration=2.4, segments=[segment()])


class TestHoldsSpeech:
    def test_a_sentence_really_said_is_speech(self):
        assert holds_speech(spoken()) is True

    def test_a_silent_recording_holds_no_speech(self):
        """The refused case: silence, and Whisper's « no speech » probability says so."""
        silent = WhisperTranscript(
            text="Thank you for watching!",
            duration=3.0,
            segments=[segment(no_speech_prob=0.94, avg_logprob=-0.42)],
        )

        assert holds_speech(silent) is False

    def test_a_recording_of_noise_holds_no_speech(self):
        """Noise: the model produced words, but none of them came out confidently."""
        noise = WhisperTranscript(
            text="Sous-titres réalisés par la communauté d'Amara.org",
            duration=4.0,
            segments=[segment(no_speech_prob=0.45, avg_logprob=-1.8)],
        )

        assert holds_speech(noise) is False

    def test_noise_is_refused_on_its_scores_even_when_the_words_are_not_boilerplate(self):
        """The two scores together: a bad average, and the model doubting it was speech."""
        noise = WhisperTranscript(
            text="Ça, c'est ça.",
            duration=4.0,
            segments=[segment(no_speech_prob=0.45, avg_logprob=-1.8)],
        )

        assert holds_speech(noise) is False

    def test_a_short_command_scored_badly_is_still_speech(self):
        """whisper-1 rates « appelle le dentiste » around -1.2 and knows it heard a voice.

        A poor average log-probability on its own would destroy it: a short French
        command is one segment, so there is no other segment to carry the clip.
        """
        short = WhisperTranscript(
            text="appelle le dentiste",
            duration=1.3,
            segments=[segment(no_speech_prob=0.02, avg_logprob=-1.2)],
        )

        assert holds_speech(short) is True

    def test_a_clip_too_short_to_hold_a_sentence_is_refused(self):
        assert holds_speech(WhisperTranscript(text="Merci.", duration=0.2, segments=[segment()])) is False

    def test_a_known_hallucination_is_refused_even_when_it_reads_well(self):
        """What the owner actually got: high confidence, and a sentence he never said."""
        confident = WhisperTranscript(
            text="Thank you for watching!",
            duration=5.0,
            segments=[segment(no_speech_prob=0.05, avg_logprob=-0.2)],
        )

        assert holds_speech(confident) is False

    def test_an_empty_transcript_holds_no_speech(self):
        assert holds_speech(WhisperTranscript(text="   ", duration=3.0, segments=[segment()])) is False

    def test_a_transcription_that_reports_nothing_is_judged_on_its_text_alone(self):
        """An endpoint that answers plain text gives no segments and no duration."""
        assert holds_speech(WhisperTranscript(text="bonjour Maggie", duration=None, segments=[])) is True
        assert holds_speech(WhisperTranscript(text="Thank you for watching", duration=None, segments=[])) is False

    def test_one_spoken_segment_among_silent_ones_is_enough(self):
        """A pause in the middle of a sentence is a silent segment, not a silent clip."""
        with_pause = WhisperTranscript(
            text="ajoute des tomates et aussi du pain",
            duration=8.0,
            segments=[segment(), segment(no_speech_prob=0.9, avg_logprob=-0.4), segment()],
        )

        assert holds_speech(with_pause) is True

    def test_segments_can_be_objects_rather_than_dicts(self):
        """What the OpenAI client really returns: pydantic models, not mappings."""

        class Segment:
            no_speech_prob = 0.95
            avg_logprob = -0.3

        # Words the phrase lists would let through, so the scores are what decides.
        assert holds_speech(WhisperTranscript(text="Oui, bon.", duration=3.0, segments=[Segment()])) is False


class TestLooksHallucinated:
    @pytest.mark.parametrize(
        "text",
        [
            "Thank you for watching!",
            "thanks for watching",
            "Merci d'avoir regardé cette vidéo.",
            "Sous-titres réalisés par la communauté d'Amara.org",
            "Sous-titrage Société Radio-Canada",
            "Abonnez-vous à la chaîne !",
            "...",
            "♪ ♪ ♪",
        ],
        ids=[
            "english",
            "english-plural",
            "french",
            "amara",
            "radio-canada",
            "subscribe",
            "ellipsis",
            "music",
        ],
    )
    def test_the_boilerplate_whisper_says_over_silence_is_caught(self, text):
        assert looks_hallucinated(text) is True

    @pytest.mark.parametrize(
        "text",
        [
            "ajoute des tomates à la liste de courses",
            "Merci de me rappeler d'appeler le dentiste demain matin.",
            "Note que je dois regarder le match ce soir.",
            "Abonne-moi au cours de piano du mercredi.",
            "oui",
            # He says it to Maggie, and refusing it answered him « Je n'ai rien
            # entendu » for a word he really spoke.
            "Merci.",
            "Merci beaucoup !",
            "Au revoir",
        ],
        ids=[
            "grocery",
            "thanks-in-a-sentence",
            "watch-in-a-sentence",
            "subscribe-lookalike",
            "one-word",
            "bare-thanks",
            "thanks-a-lot",
            "goodbye",
        ],
    )
    def test_a_sentence_the_owner_could_say_is_left_alone(self, text):
        assert looks_hallucinated(text) is False


class TestTranscribeAudioRefusesSilence:
    @pytest.mark.asyncio
    @patch("app.llm.transcription._cleanup_with_llm", new_callable=AsyncMock)
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_a_silent_recording_produces_no_text(self, mock_whisper, mock_cleanup):
        mock_whisper.return_value = WhisperTranscript(
            text="Thank you for watching!",
            duration=3.0,
            segments=[segment(no_speech_prob=0.94, avg_logprob=-0.42)],
        )

        result = await transcribe_audio(b"silence", "voice.wav", cleanup="none")

        assert result == {"raw": "", "clean": ""}
        mock_cleanup.assert_not_called()

    @pytest.mark.asyncio
    @patch("app.llm.transcription._cleanup_with_llm", new_callable=AsyncMock)
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_a_recording_of_noise_produces_no_text(self, mock_whisper, mock_cleanup):
        mock_whisper.return_value = WhisperTranscript(
            text="Sous-titres réalisés par la communauté d'Amara.org",
            duration=4.0,
            segments=[segment(no_speech_prob=0.45, avg_logprob=-1.8)],
        )

        result = await transcribe_audio(b"noise", "voice.wav", cleanup="auto")

        assert result == {"raw": "", "clean": ""}
        mock_cleanup.assert_not_called()

    @pytest.mark.asyncio
    @patch("app.llm.transcription._whisper_transcribe", new_callable=AsyncMock)
    async def test_a_sentence_really_said_still_comes_through(self, mock_whisper):
        mock_whisper.return_value = spoken("euh ajoute des tomates à la liste de courses")

        result = await transcribe_audio(b"audio", "voice.wav", cleanup="none")

        assert result["raw"] == "euh ajoute des tomates à la liste de courses"
        assert result["clean"] == "ajoute des tomates à la liste de courses"


class TestWhisperAsksForWhatItNeedsToJudge:
    @pytest.mark.asyncio
    @patch("app.llm.transcription.openai")
    async def test_the_transcription_is_asked_for_its_own_silence_scores(self, mock_openai_module, monkeypatch):
        """`verbose_json` is the only response format that carries them."""
        from app.llm.transcription import _whisper_transcribe

        monkeypatch.setattr("app.llm.transcription.settings.openai_base_url", "")
        mock_client = AsyncMock()
        mock_openai_module.AsyncOpenAI.return_value = mock_client
        mock_client.audio.transcriptions.create = AsyncMock(
            return_value={"text": "  bonjour  ", "duration": 1.5, "segments": [segment()]},
        )

        transcript = await _whisper_transcribe(b"audio-data", "recording.wav")

        assert mock_client.audio.transcriptions.create.call_args.kwargs["response_format"] == "verbose_json"
        assert transcript.text == "bonjour"
        assert transcript.duration == 1.5
        assert len(transcript.segments) == 1

    @pytest.mark.asyncio
    @patch("app.llm.transcription.openai")
    async def test_a_server_that_answers_plain_text_still_works(self, mock_openai_module, monkeypatch):
        """Whatever the stand-in answers, the chain must not crash on its shape."""
        from app.llm.transcription import _whisper_transcribe

        monkeypatch.setattr("app.llm.transcription.settings.openai_base_url", "")
        mock_client = AsyncMock()
        mock_openai_module.AsyncOpenAI.return_value = mock_client
        mock_client.audio.transcriptions.create = AsyncMock(return_value="bonjour Maggie")

        transcript = await _whisper_transcribe(b"audio-data", "recording.wav")

        assert transcript.text == "bonjour Maggie"
        assert transcript.duration is None
        assert transcript.segments == []

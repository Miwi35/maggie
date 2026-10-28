"""Maggie's voice is Claire, by ElevenLabs, and Edge speaks the sentences ElevenLabs cannot (ADR-014, MAG-689)."""

import json
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
import respx
from aiohttp import WSServerHandshakeError

from app.metrics import ELEVENLABS_CREDITS, TTS_FALLBACKS
from app.tts.synthesis import (
    CLAIRE_VOICE,
    DEFAULT_VOICE,
    VOICE_IDS,
    SpeechUnavailableError,
    get_voices,
    synthesize_file,
    synthesize_speech,
)
from app.tts.text import strip_delivery_tags

SPEECH_URL = f"https://api.elevenlabs.io/v1/text-to-speech/{CLAIRE_VOICE}/stream"


@pytest.fixture(autouse=True)
def elevenlabs_configured(monkeypatch):
    monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "elevenlabs")
    monkeypatch.setattr("app.tts.synthesis.settings.elevenlabs_api_key", "xi-test-key")


class EdgeSpy:
    """Stands in for edge_tts.Communicate and remembers what it was asked to say."""

    def __init__(self):
        self.spoken: list[tuple[str, str]] = []

    def __call__(self, text, voice):
        self.spoken.append((text, voice))

        class Communicate:
            async def stream(self):
                yield {"type": "audio", "data": b"edge-audio"}

        return Communicate()


@pytest.fixture
def edge(monkeypatch):
    spy = EdgeSpy()
    monkeypatch.setattr("app.tts.providers.edge_tts.Communicate", spy)
    return spy


async def collect(text: str, voice: str = DEFAULT_VOICE, **kwargs) -> bytes:
    return b"".join([chunk async for chunk in synthesize_speech(text, voice, **kwargs)])


def sent_body(route) -> dict:
    return json.loads(route.calls.last.request.content)


class TestTheRequestToElevenLabs:
    @respx.mock
    async def test_live_voice_asks_v4_turbo_with_claire_in_french(self, edge):
        route = respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, content=b"claire-audio"))

        assert await collect("Bonjour monsieur.") == b"claire-audio"

        assert sent_body(route) == {"text": "Bonjour monsieur.", "model_id": "eleven_v4_turbo", "language_code": "fr"}
        request = route.calls.last.request
        assert request.headers["xi-api-key"] == "xi-test-key"
        assert request.url.params["output_format"].startswith("mp3_")
        assert edge.spoken == []

    @respx.mock
    async def test_a_voice_message_asks_v4(self, edge):
        route = respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, content=b"claire-audio"))

        assert await synthesize_file("Votre point du matin.") == b"claire-audio"

        assert sent_body(route)["model_id"] == "eleven_v4"
        assert sent_body(route)["language_code"] == "fr"

    @respx.mock
    async def test_the_delivery_tags_reach_the_voice_that_acts_them_out(self, edge):
        route = respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, content=b"claire-audio"))

        await collect("[warmly] Bonjour monsieur. [softly] Votre journée est calme.")

        assert sent_body(route)["text"] == "[warmly] Bonjour monsieur. [softly] Votre journée est calme."

    @respx.mock
    async def test_the_credits_are_counted_from_the_figure_the_api_reports(self, edge):
        respx.post(SPEECH_URL).mock(
            return_value=httpx.Response(200, content=b"claire-audio", headers={"character-cost": "9"})
        )
        counter = ELEVENLABS_CREDITS.labels(model="eleven_v4_turbo")
        before = counter._value.get()

        await collect("Bonjour.")

        assert counter._value.get() - before == 9

    @respx.mock
    async def test_the_credits_default_to_the_length_of_the_text(self, edge):
        respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, content=b"claire-audio"))
        counter = ELEVENLABS_CREDITS.labels(model="eleven_v4")
        before = counter._value.get()

        await synthesize_file("Bonjour.")

        assert counter._value.get() - before == len("Bonjour.")


class TestEdgeSpeaksWhenElevenLabsCannot:
    @respx.mock
    @pytest.mark.parametrize(
        ("failure", "reason"),
        [
            (httpx.Response(429, json={"detail": "too_many_requests"}), "quota"),
            (httpx.Response(401, json={"detail": {"status": "quota_exceeded"}}), "unauthorized"),
            (httpx.Response(503), "server"),
            (httpx.Response(422), "error"),
            (httpx.ConnectTimeout("slow"), "timeout"),
            (httpx.ReadTimeout("slow"), "timeout"),
            (httpx.ConnectError("down"), "error"),
            (httpx.Response(200, content=b""), "empty"),
        ],
    )
    async def test_the_sentence_is_spoken_by_edge_without_an_exception(self, failure, reason, edge):
        route = respx.post(SPEECH_URL)
        route.mock(side_effect=failure) if isinstance(failure, Exception) else route.mock(return_value=failure)
        counter = TTS_FALLBACKS.labels(provider="elevenlabs", reason=reason)
        before = counter._value.get()

        with patch("app.tts.synthesis.capture_signal") as signal:
            audio = await collect("Bonjour monsieur.")

        assert audio == b"edge-audio"
        assert edge.spoken == [("Bonjour monsieur.", "fr-FR-DeniseNeural")]
        assert counter._value.get() - before == 1
        signal.assert_called_once_with("tts_fallback", provider="elevenlabs", reason=reason)

    async def test_no_api_key_is_a_fallback_too(self, monkeypatch, edge):
        monkeypatch.setattr("app.tts.synthesis.settings.elevenlabs_api_key", "")

        with patch("app.tts.synthesis.capture_signal") as signal:
            assert await collect("Bonjour.") == b"edge-audio"

        signal.assert_called_once_with("tts_fallback", provider="elevenlabs", reason="not_configured")

    @respx.mock
    async def test_a_voice_message_falls_back_too(self, edge):
        respx.post(SPEECH_URL).mock(return_value=httpx.Response(429))

        with patch("app.tts.synthesis.capture_signal"):
            assert await synthesize_file("Votre point du matin.") == b"edge-audio"

    @respx.mock
    async def test_edge_never_receives_a_delivery_tag(self, edge):
        respx.post(SPEECH_URL).mock(return_value=httpx.Response(500))

        with patch("app.tts.synthesis.capture_signal"):
            await collect(
                "[warmly] Bonjour monsieur. Votre journée est calme [softly], je me suis permis de décaler le rendez-vous."
            )

        ((text, _voice),) = edge.spoken
        assert "[" not in text
        assert text == "Bonjour monsieur. Votre journée est calme, je me suis permis de décaler le rendez-vous."

    @respx.mock
    async def test_a_failure_after_the_first_chunk_is_not_started_over(self, edge):
        async def cut_short():
            yield b"claire-"
            raise httpx.ReadError("connection lost")

        respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, stream=cut_short()))
        received = []

        with pytest.raises(httpx.ReadError):
            async for chunk in synthesize_speech("Bonjour monsieur."):
                received.append(chunk)

        assert received == [b"claire-"]
        assert edge.spoken == []

    @respx.mock
    async def test_when_edge_is_down_too_the_error_is_the_one_the_route_answers_502_to(self, monkeypatch):
        respx.post(SPEECH_URL).mock(return_value=httpx.Response(429))
        monkeypatch.setattr("app.tts.providers.asyncio.sleep", AsyncMock())
        class Refusing:
            def __init__(self, *_args):
                pass

            async def stream(self):
                raise WSServerHandshakeError(MagicMock(), (), status=503, message="refused")
                yield

        monkeypatch.setattr("app.tts.providers.edge_tts.Communicate", Refusing)

        with patch("app.tts.synthesis.capture_signal"), pytest.raises(SpeechUnavailableError):
            await collect("Bonjour.")


class TestWhoSpeaks:
    @respx.mock
    async def test_an_edge_voice_chosen_by_the_user_stays_on_edge(self, edge):
        route = respx.post(SPEECH_URL)

        assert await collect("Bonjour.", "fr-FR-HenriNeural") == b"edge-audio"

        assert not route.called
        assert edge.spoken == [("Bonjour.", "fr-FR-HenriNeural")]

    @respx.mock
    async def test_the_edge_setting_forces_edge_even_for_claire(self, monkeypatch, edge):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")
        route = respx.post(SPEECH_URL)

        assert await collect("Bonjour.", CLAIRE_VOICE) == b"edge-audio"

        assert not route.called
        assert edge.spoken == [("Bonjour.", "fr-FR-DeniseNeural")]

    @respx.mock
    async def test_the_fake_setting_wins_over_the_voice(self, monkeypatch, edge):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")
        route = respx.post(SPEECH_URL)

        assert await collect("Bonjour.", CLAIRE_VOICE)

        assert not route.called
        assert edge.spoken == []


class TestTheVoiceList:
    def test_claire_comes_first_and_is_the_default(self):
        voices = get_voices()

        assert voices[0]["id"] == CLAIRE_VOICE == "6vTyAgAT8PncODBcLjRf"
        assert voices[0]["name"] == "Claire"
        assert voices[0]["provider"] == "elevenlabs"
        assert DEFAULT_VOICE == CLAIRE_VOICE

    def test_the_edge_voices_are_still_offered(self):
        edge_voices = [v for v in get_voices() if v["provider"] == "edge"]

        assert "fr-FR-DeniseNeural" in {v["id"] for v in edge_voices}
        assert len(edge_voices) == len(get_voices()) - 1
        assert VOICE_IDS == {v["id"] for v in get_voices()}


class TestTheRoutes:
    @respx.mock
    def test_the_route_streams_claire(self, authed_client, edge):
        route = respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, content=b"claire-audio"))

        response = authed_client.post("/tts/synthesize", json={"text": "Bonjour monsieur."})

        assert response.status_code == 200
        assert response.content == b"claire-audio"
        assert sent_body(route)["model_id"] == "eleven_v4_turbo"

    @respx.mock
    def test_the_route_can_make_a_voice_message(self, authed_client, edge):
        route = respx.post(SPEECH_URL).mock(return_value=httpx.Response(200, content=b"claire-audio"))

        response = authed_client.post("/tts/synthesize", json={"text": "Votre point.", "usage": "file"})

        assert response.status_code == 200
        assert sent_body(route)["model_id"] == "eleven_v4"

    def test_an_unknown_usage_is_refused(self, authed_client):
        response = authed_client.post("/tts/synthesize", json={"text": "Bonjour.", "usage": "radio"})

        assert response.status_code == 422

    @respx.mock
    def test_a_quota_exhausted_at_elevenlabs_is_still_a_200_with_edge_audio(self, authed_client, edge):
        respx.post(SPEECH_URL).mock(return_value=httpx.Response(429))

        with patch("app.tts.synthesis.capture_signal"):
            response = authed_client.post("/tts/synthesize", json={"text": "Bonjour monsieur."})

        assert response.status_code == 200
        assert response.content == b"edge-audio"

    def test_the_voices_route_lists_claire_first(self, authed_client):
        voices = authed_client.get("/tts/voices").json()

        assert voices[0]["name"] == "Claire"

    def test_claire_can_be_chosen_as_the_voice(self, authed_client):
        with patch("app.api.routes.user_setting_repo.set_tts_voice") as store:
            store.return_value = None
            response = authed_client.put("/tts/voice", json={"voice": CLAIRE_VOICE})

        assert response.status_code == 200
        assert response.json() == {"voice": CLAIRE_VOICE}


class TestDeliveryTags:
    @pytest.mark.parametrize(
        ("spoken", "plain"),
        [
            ("[warmly] Bonjour monsieur.", "Bonjour monsieur."),
            ("Bonjour [softly] monsieur.", "Bonjour monsieur."),
            ("Bonjour monsieur [reassuring].", "Bonjour monsieur."),
            ("[warmly][softly] Voilà.", "Voilà."),
            ("Rien à retirer ici.", "Rien à retirer ici."),
        ],
    )
    def test_the_words_around_a_tag_stay_readable(self, spoken, plain):
        assert strip_delivery_tags(spoken) == plain

    async def test_a_sentence_made_only_of_tags_is_not_sent_to_edge(self, monkeypatch, edge):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")

        assert await collect("[warmly]") == b""
        assert edge.spoken == []

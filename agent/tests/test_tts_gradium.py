"""Armelle and Follette speak through Gradium; the audio stays MP3 and Edge catches every failure (MAG-786, ADR-014)."""

import asyncio
import base64
import json

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from app.config import Settings
from app.metrics import GRADIUM_CREDITS, TTS_FALLBACKS
from app.tts.providers import GRADIUM_MAX_SESSION_CHARACTERS, split_sentences
from app.tts.synthesis import (
    ARMELLE_VOICE,
    CLAIRE_VOICE,
    FOLLETTE_VOICE,
    get_voices,
    synthesize_file,
    synthesize_speech,
)

# 100 ms of 48 kHz 16-bit mono silence.
PCM_CHUNK = b"\x00\x00" * 4800


class FakeGradium:
    """A local WebSocket server that speaks Gradium's protocol and records each session."""

    def __init__(self):
        self.sessions: list[dict] = []
        self.mode = "ok"

    async def handler(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        session = {"headers": dict(request.headers), "setup": None, "text": "", "ended": False}
        self.sessions.append(session)
        async for message in ws:
            data = json.loads(message.data)
            if data["type"] == "setup":
                session["setup"] = data
            elif data["type"] == "text":
                session["text"] += data["text"]
            elif data["type"] == "end_of_stream":
                session["ended"] = True
                if self.mode == "error":
                    await ws.send_json({"type": "error", "message": "boom", "code": 500})
                elif self.mode == "quota":
                    await ws.send_json({"type": "error", "message": "no credits", "code": 429})
                elif self.mode == "silent":
                    await asyncio.sleep(5)
                else:
                    for _ in range(3):
                        await ws.send_json({"type": "audio", "audio": base64.b64encode(PCM_CHUNK).decode()})
                    await ws.send_json({"type": "end_of_stream"})
                await ws.close()
        return ws


@pytest.fixture
async def gradium(monkeypatch):
    fake = FakeGradium()
    app = web.Application()
    app.router.add_get("/api/speech/tts", fake.handler)
    server = TestServer(app)
    await server.start_server()
    monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "elevenlabs")
    monkeypatch.setattr("app.tts.synthesis.settings.gradium_api_key", "gr-test-key")
    monkeypatch.setattr("app.tts.synthesis.settings.gradium_base_url", f"ws://127.0.0.1:{server.port}/api/speech/tts")
    monkeypatch.setattr("app.tts.synthesis.settings.gradium_timeout_seconds", 0.5)
    yield fake
    await server.close()


class EdgeSpy:
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


async def collect(text: str, voice: str = ARMELLE_VOICE, **kwargs) -> bytes:
    return b"".join([chunk async for chunk in synthesize_speech(text, voice, **kwargs)])


def fallbacks(reason: str) -> float:
    return TTS_FALLBACKS.labels(provider="gradium", reason=reason)._value.get()


class TestTheSessionWithGradium:
    async def test_setup_carries_the_voice_pcm_and_the_default_model(self, gradium, edge):
        audio = await collect("Bonjour monsieur.", FOLLETTE_VOICE)

        [session] = gradium.sessions
        assert session["setup"]["voice_id"] == FOLLETTE_VOICE
        assert session["setup"]["output_format"] == "pcm"
        assert session["setup"]["model_name"] == "default"
        assert session["headers"]["x-api-key"] == "gr-test-key"
        assert session["text"] == "Bonjour monsieur."
        assert session["ended"]
        assert edge.spoken == []
        assert audio

    async def test_the_audio_is_mp3(self, gradium, edge):
        audio = await collect("Bonjour monsieur.")

        assert (audio[0] == 0xFF and audio[1] & 0xE0 == 0xE0) or audio.startswith(b"ID3")

    def test_the_default_url_is_the_eu_region(self):
        assert Settings().gradium_base_url == "wss://eu.api.gradium.ai/api/speech/tts"

    async def test_delivery_tags_are_stripped_before_sending(self, gradium, edge):
        await collect("[softly] Bonjour [laughs] monsieur.")

        assert "[" not in gradium.sessions[0]["text"]
        assert "Bonjour" in gradium.sessions[0]["text"]

    async def test_one_credit_per_character_sent(self, gradium, edge):
        before = GRADIUM_CREDITS._value.get()

        await collect("Bonjour monsieur.")

        assert GRADIUM_CREDITS._value.get() - before == len("Bonjour monsieur.")


class TestEdgeCatchesGradium:
    async def test_an_error_message_goes_to_edge(self, gradium, edge):
        gradium.mode = "error"
        before = fallbacks("error")

        assert await collect("Bonjour.") == b"edge-audio"

        assert fallbacks("error") == before + 1
        assert edge.spoken[0][1] == "fr-FR-DeniseNeural"

    async def test_a_quota_error_is_labelled_quota(self, gradium, edge):
        gradium.mode = "quota"
        before = fallbacks("quota")

        assert await collect("Bonjour.") == b"edge-audio"

        assert fallbacks("quota") == before + 1

    async def test_no_answer_in_time_goes_to_edge(self, gradium, edge):
        gradium.mode = "silent"
        before = fallbacks("timeout")

        assert await collect("Bonjour.") == b"edge-audio"

        assert fallbacks("timeout") == before + 1

    async def test_no_key_goes_to_edge(self, gradium, edge, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.gradium_api_key", "")
        before = fallbacks("not_configured")

        assert await collect("Bonjour.") == b"edge-audio"

        assert gradium.sessions == []
        assert fallbacks("not_configured") == before + 1

    async def test_an_unreachable_server_goes_to_edge(self, gradium, edge, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.gradium_base_url", "ws://127.0.0.1:1/api/speech/tts")

        assert await collect("Bonjour.") == b"edge-audio"


class TestLongVoiceMessages:
    async def test_a_long_text_goes_in_sessions_of_at_most_1500_characters(self, gradium, edge):
        text = "Voici une phrase du point du matin. " * 120

        audio = await synthesize_file(text, ARMELLE_VOICE)

        assert len(gradium.sessions) > 1
        for session in gradium.sessions:
            assert len(session["text"]) <= GRADIUM_MAX_SESSION_CHARACTERS
            assert session["text"].endswith(".")
        sent = " ".join(s["text"] for s in gradium.sessions)
        assert sent.split() == text.split()
        assert len(audio) > len(gradium.sessions) * 3000

    def test_split_cuts_at_sentence_ends(self):
        assert split_sentences("Un. Deux. Trois.", 10) == ["Un. Deux.", "Trois."]

    def test_split_cuts_an_endless_sentence_at_a_space(self):
        pieces = split_sentences("mot " * 10, 12)

        assert all(len(p) <= 12 for p in pieces)
        assert " ".join(pieces).split() == ["mot"] * 10


class TestTheVoiceList:
    def test_claire_then_gradium_then_edge(self):
        voices = get_voices()

        assert [v["name"] for v in voices[:3]] == ["Claire", "Armelle", "Follette"]
        assert [v["provider"] for v in voices[:3]] == ["elevenlabs", "gradium", "gradium"]
        assert voices[0]["id"] == CLAIRE_VOICE
        assert all(v["provider"] == "edge" for v in voices[3:])

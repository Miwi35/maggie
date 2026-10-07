"""The e2e-only surface must exist on the e2e stack and nowhere else (MAG-205).

The agent's counterpart of the API's `E2eSurfaceAbsenceTest`: it asks the application which
routes it serves under each provider, because that is what decides whether anything can reach
the code — not a comment saying « e2e only ».
"""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.e2e import setup_e2e
from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.transcription import WhisperTranscript, reset_cleanup_requests
from app.tts.synthesis import reset_fake_synthesis_requests, synthesize_speech

TOKEN = "token-for-the-test"
URL = "/e2e/tts/syntheses"
CLEANUPS_URL = "/e2e/transcription/cleanups"
IMAGES_URL = "/e2e/llm/images"


def _client_saying(text: str) -> AsyncMock:
    """An Anthropic-shaped client whose single answer is `text`."""
    block = MagicMock()
    block.text = text
    response = MagicMock()
    response.content = [block]
    response.usage = MagicMock(input_tokens=10, output_tokens=5)
    client = AsyncMock()
    client.messages.create = AsyncMock(return_value=response)
    return client


def app_for(monkeypatch, provider: str) -> FastAPI:
    monkeypatch.setattr("app.e2e.settings.tts_provider", provider)
    monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
    app = FastAPI()
    setup_e2e(app)
    return app


@pytest.fixture(autouse=True)
def fresh_counter():
    reset_fake_synthesis_requests()
    reset_cleanup_requests()
    yield
    reset_fake_synthesis_requests()
    reset_cleanup_requests()


class TestAbsenceOutsideE2e:
    @pytest.mark.parametrize("provider", ["edge", ""])
    @pytest.mark.parametrize("url", [URL, CLEANUPS_URL, IMAGES_URL])
    def test_no_e2e_route_is_mounted(self, monkeypatch, provider, url):
        app = app_for(monkeypatch, provider)

        assert [route.path for route in app.routes if route.path.startswith("/e2e")] == []
        with TestClient(app) as client:
            assert client.get(url, headers={"X-E2E-Token": TOKEN}).status_code == 404
            assert client.delete(url, headers={"X-E2E-Token": TOKEN}).status_code == 404

    def test_the_real_application_does_not_serve_it_by_default(self):
        from app.main import app

        assert [route.path for route in app.routes if route.path.startswith("/e2e")] == []


class TestOnTheE2eStack:
    @pytest.mark.parametrize("url", [URL, CLEANUPS_URL, IMAGES_URL])
    def test_requires_the_token(self, monkeypatch, url):
        with TestClient(app_for(monkeypatch, "fake")) as client:
            assert client.get(url).status_code == 401
            assert client.get(url, headers={"X-E2E-Token": "wrong"}).status_code == 401
            assert client.delete(url).status_code == 401

    def test_stays_closed_when_no_token_is_configured(self, monkeypatch):
        app = app_for(monkeypatch, "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", "")

        with TestClient(app) as client:
            assert client.get(URL, headers={"X-E2E-Token": ""}).status_code == 401

    async def test_counts_every_synthesis_and_resets(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")
        headers = {"X-E2E-Token": TOKEN}

        with TestClient(app_for(monkeypatch, "fake")) as client:
            assert client.get(URL, headers=headers).json() == {"count": 0}

            async for _ in synthesize_speech("Bonjour."):
                pass
            async for _ in synthesize_speech("Encore."):
                pass
            assert client.get(URL, headers=headers).json() == {"count": 2}

            assert client.delete(URL, headers=headers).json() == {"count": 0}
            assert client.get(URL, headers=headers).json() == {"count": 0}

    async def test_the_real_provider_never_moves_the_counter(self, monkeypatch):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "edge")

        class Stream:
            async def stream(self):
                yield {"type": "audio", "data": b"x"}

        monkeypatch.setattr("app.tts.synthesis.edge_tts.Communicate", lambda *_: Stream())
        async for _ in synthesize_speech("Bonjour."):
            pass

        from app.tts.synthesis import fake_synthesis_requests

        assert fake_synthesis_requests() == 0

    def test_counts_every_cleanup_and_resets(self, monkeypatch, authed_client):
        """The voice journey's proof of an absence: no cleanup when the text goes to Maggie."""
        headers = {"X-E2E-Token": TOKEN}

        # The real `_cleanup_with_llm` runs — it is what moves the counter — with only
        # the model behind it replaced.
        with (
            patch(
                "app.llm.transcription._whisper_transcribe",
                # A transcript with speech behind it: the silence gate added by
                # MAG-222's retour de recette refuses one without.
                AsyncMock(
                    return_value=WhisperTranscript(
                        text="euh bonjour",
                        duration=1.5,
                        segments=[{"no_speech_prob": 0.02, "avg_logprob": -0.3}],
                    ),
                ),
            ),
            patch("app.llm.transcription.create_llm_client", return_value=_client_saying("Bonjour.")),
        ):
            with TestClient(app_for(monkeypatch, "fake")) as client:
                assert client.get(CLEANUPS_URL, headers=headers).json() == {"count": 0}

                audio = {"audio": ("clip.webm", b"bytes", "audio/webm")}
                assert authed_client.post("/transcribe", files=audio, data={"cleanup": "none"}).status_code == 200
                assert client.get(CLEANUPS_URL, headers=headers).json() == {"count": 0}

                assert authed_client.post("/transcribe", files=audio, data={"cleanup": "auto"}).status_code == 200
                assert client.get(CLEANUPS_URL, headers=headers).json() == {"count": 1}

                assert client.delete(CLEANUPS_URL, headers=headers).json() == {"count": 0}
                assert client.get(CLEANUPS_URL, headers=headers).json() == {"count": 0}

    def test_the_synthesis_endpoint_is_what_counts(self, monkeypatch, authed_client):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")
        headers = {"X-E2E-Token": TOKEN}

        assert authed_client.post("/tts/synthesize", json={"text": "Bonjour."}).status_code == 200
        assert authed_client.post("/tts/synthesize", json={"text": "x", "voice": "nope"}).status_code == 400

        with TestClient(app_for(monkeypatch, "fake")) as client:
            assert client.get(URL, headers=headers).json() == {"count": 1}

    async def test_counts_every_question_with_a_picture_and_resets(self, monkeypatch):
        """The voice journey's proof that the screenshot crossed the agent to the model (MAG-214)."""
        headers = {"X-E2E-Token": TOKEN}
        picture = {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "/9j/"}}
        model = FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)

        with TestClient(app_for(monkeypatch, "fake")) as client:
            assert client.delete(IMAGES_URL, headers=headers).json() == {"count": 0}

            await model.messages.create(
                messages=[{"role": "user", "content": [picture, {"type": "text", "text": "c'est quoi ?"}]}]
            )
            assert client.get(IMAGES_URL, headers=headers).json() == {"count": 1}

            assert client.delete(IMAGES_URL, headers=headers).json() == {"count": 0}

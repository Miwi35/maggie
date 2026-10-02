"""The e2e-only surface must exist on the e2e stack and nowhere else (MAG-205).

The agent's counterpart of the API's `E2eSurfaceAbsenceTest`: it asks the application which
routes it serves under each provider, because that is what decides whether anything can reach
the code — not a comment saying « e2e only ».
"""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.e2e import setup_e2e
from app.tts.synthesis import reset_fake_synthesis_requests, synthesize_speech

TOKEN = "token-for-the-test"
URL = "/e2e/tts/syntheses"


def app_for(monkeypatch, provider: str) -> FastAPI:
    monkeypatch.setattr("app.e2e.settings.tts_provider", provider)
    monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
    app = FastAPI()
    setup_e2e(app)
    return app


@pytest.fixture(autouse=True)
def fresh_counter():
    reset_fake_synthesis_requests()
    yield
    reset_fake_synthesis_requests()


class TestAbsenceOutsideE2e:
    @pytest.mark.parametrize("provider", ["edge", ""])
    def test_no_e2e_route_is_mounted(self, monkeypatch, provider):
        app = app_for(monkeypatch, provider)

        assert [route.path for route in app.routes if route.path.startswith("/e2e")] == []
        with TestClient(app) as client:
            assert client.get(URL, headers={"X-E2E-Token": TOKEN}).status_code == 404
            assert client.delete(URL, headers={"X-E2E-Token": TOKEN}).status_code == 404

    def test_the_real_application_does_not_serve_it_by_default(self, monkeypatch):
        monkeypatch.delenv("TTS_PROVIDER", raising=False)
        from app.config import Settings

        if Settings().tts_provider == "fake":
            pytest.skip("the process itself runs the e2e provider")
        from app.main import app

        assert [route.path for route in app.routes if route.path.startswith("/e2e")] == []


class TestOnTheE2eStack:
    def test_requires_the_token(self, monkeypatch):
        with TestClient(app_for(monkeypatch, "fake")) as client:
            assert client.get(URL).status_code == 401
            assert client.get(URL, headers={"X-E2E-Token": "wrong"}).status_code == 401
            assert client.delete(URL).status_code == 401

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

    def test_the_synthesis_endpoint_is_what_counts(self, monkeypatch, authed_client):
        monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")
        headers = {"X-E2E-Token": TOKEN}

        assert authed_client.post("/tts/synthesize", json={"text": "Bonjour."}).status_code == 200
        assert authed_client.post("/tts/synthesize", json={"text": "x", "voice": "nope"}).status_code == 400

        with TestClient(app_for(monkeypatch, "fake")) as client:
            assert client.get(URL, headers=headers).json() == {"count": 1}

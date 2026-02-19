"""Route-level tests for POST /transcribe."""

from unittest.mock import AsyncMock, patch


class TestTranscribeRoute:
    def test_transcribe_requires_auth(self, client):
        """POST /transcribe without auth returns 401/403."""
        response = client.post("/transcribe", files={"audio": ("test.webm", b"fake-audio", "audio/webm")})
        assert response.status_code in (401, 403)

    @patch("app.api.routes.transcribe_audio", new_callable=AsyncMock)
    def test_transcribe_returns_clean_text(self, mock_transcribe, authed_client):
        """POST /transcribe with valid audio returns raw + clean text."""
        mock_transcribe.return_value = {"raw": "euh bonjour", "clean": "Bonjour."}

        response = authed_client.post(
            "/transcribe",
            files={"audio": ("recording.webm", b"fake-audio-bytes", "audio/webm")},
        )

        assert response.status_code == 200
        data = response.json()
        assert data["raw"] == "euh bonjour"
        assert data["clean"] == "Bonjour."
        mock_transcribe.assert_called_once()

    def test_transcribe_empty_file_returns_400(self, authed_client):
        """POST /transcribe with empty file returns 400."""
        response = authed_client.post(
            "/transcribe",
            files={"audio": ("recording.webm", b"", "audio/webm")},
        )

        assert response.status_code == 400

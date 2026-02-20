from datetime import UTC
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.proaction_model import ProactionStatus


class TestProactionModel:
    def test_proaction_status_values(self):
        """ProactionStatus enum should have the expected values."""
        assert ProactionStatus.PENDING == "pending"
        assert ProactionStatus.RUNNING == "running"
        assert ProactionStatus.COMPLETED == "completed"
        assert ProactionStatus.FAILED == "failed"

    def test_proaction_to_dict(self):
        """Proaction.to_dict() should serialize correctly."""
        from datetime import datetime

        from app.db.proaction_model import Proaction

        proaction = Proaction(
            id="abc123",
            user_id="user-1",
            prompt="Check calendar",
            status=ProactionStatus.PENDING,
            scheduled_at=datetime(2026, 2, 20, 9, 0, tzinfo=UTC),
            created_at=datetime(2026, 2, 19, 10, 0, tzinfo=UTC),
        )

        d = proaction.to_dict()
        assert d["id"] == "abc123"
        assert d["userId"] == "user-1"
        assert d["prompt"] == "Check calendar"
        assert d["status"] == "pending"
        assert d["scheduledAt"] == "2026-02-20T09:00:00+00:00"
        assert d["response"] is None
        assert d["error"] is None
        assert d["completedAt"] is None


class TestProactionRouteAuth:
    def test_proactions_endpoint_requires_auth(self, client):
        """GET /proactions without auth returns 401 or 403."""
        response = client.get("/proactions")
        assert response.status_code in (401, 403)

    @patch("app.api.routes.proaction_repo")
    def test_proactions_endpoint_with_auth(self, mock_repo, authed_client):
        """GET /proactions with valid auth returns proactions list."""
        mock_proaction = MagicMock()
        mock_proaction.to_dict.return_value = {
            "id": "abc123",
            "userId": "test-user",
            "prompt": "Check calendar",
            "status": "pending",
        }
        mock_repo.find_by_user = AsyncMock(return_value=[mock_proaction])

        response = authed_client.get("/proactions")

        assert response.status_code == 200
        data = response.json()
        assert len(data) == 1
        assert data[0]["id"] == "abc123"

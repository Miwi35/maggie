class TestRoutes:
    def test_health_endpoint(self, client):
        """GET /health returns status ok and service name."""
        response = client.get("/health")

        assert response.status_code == 200

        data = response.json()
        assert data["status"] == "ok"
        assert data["service"] == "maggie-agent-hub"

    def test_chat_endpoint_without_api_key(self, client):
        """POST /chat without ANTHROPIC_API_KEY returns a not-configured message."""
        response = client.post(
            "/chat",
            json={"message": "Hello", "user_id": "test-user"},
        )

        assert response.status_code == 200

        data = response.json()
        assert "not configured" in data["response"].lower()
        assert data["tool_calls"] == []

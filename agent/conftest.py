import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture
def client():
    """FastAPI test client."""
    return TestClient(app)


@pytest.fixture
def mock_settings(monkeypatch):
    """Override settings for testing."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    monkeypatch.setenv("MCP_SERVER_URL", "http://localhost:9999/_mcp")
    monkeypatch.setenv("MERCURE_URL", "http://localhost:9999/.well-known/mercure")
    monkeypatch.setenv("MERCURE_JWT_SECRET", "test-secret")

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.auth import get_current_user_id


@pytest.fixture()
def client():
    """FastAPI test client with lifespan disabled to avoid MCP connection attempts."""
    test_app = FastAPI()
    test_app.include_router(router)

    with TestClient(test_app) as c:
        yield c


@pytest.fixture()
def authed_client():
    """FastAPI test client with auth dependency overridden to return 'test-user'."""
    test_app = FastAPI()
    test_app.include_router(router)
    test_app.dependency_overrides[get_current_user_id] = lambda: "test-user"

    with TestClient(test_app) as c:
        yield c

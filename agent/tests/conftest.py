import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def client():
    """FastAPI test client with lifespan disabled to avoid MCP connection attempts."""
    from fastapi import FastAPI

    from app.api.routes import router

    test_app = FastAPI()
    test_app.include_router(router)

    with TestClient(test_app) as c:
        yield c

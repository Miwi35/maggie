from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.e2e import setup_e2e
from app.memory.bucket import FakeBucket
from app.memory.service import MemoryService
from tests.memory.conftest import USER_A, USER_B, fiche

TOKEN = "token-for-the-test"
HEADERS = {"X-E2E-Token": TOKEN}
ROUTES = [
    ("get", f"/e2e/memory/{USER_A}/state"),
    ("post", f"/e2e/memory/{USER_A}/sync"),
    ("get", f"/e2e/memory/{USER_A}/search?q=x"),
    ("post", f"/e2e/memory/{USER_A}/night"),
    ("put", f"/e2e/memory/bucket/{USER_A}/x.md"),
    ("put", "/e2e/memory/outage"),
]


def app_for(monkeypatch, provider: str) -> FastAPI:
    monkeypatch.setattr("app.e2e.settings.tts_provider", provider)
    monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
    app = FastAPI()
    setup_e2e(app)
    return app


@pytest.fixture()
def client(monkeypatch, env):
    monkeypatch.setattr("app.e2e.memory_service", env.service)
    with TestClient(app_for(monkeypatch, "fake")) as c:
        yield c


@pytest.mark.parametrize("provider", ["edge", ""])
def test_the_memory_routes_do_not_exist_outside_the_e2e_stack(monkeypatch, provider):
    app = app_for(monkeypatch, provider)
    assert [r.path for r in app.routes if "/memory" in r.path] == []
    with TestClient(app) as c:
        for method, url in ROUTES:
            assert c.request(method, url, headers=HEADERS, json={"text": "x", "down": True}).status_code == 404


@pytest.mark.parametrize(("method", "url"), ROUTES)
def test_they_need_the_token(client, method, url):
    assert client.request(method, url, json={"text": "x", "down": True}).status_code == 401


def test_they_refuse_a_service_that_is_not_the_fake_bucket(monkeypatch, memory_db, tmp_path):
    monkeypatch.setattr("app.e2e.memory_service", MemoryService(None, tmp_path))
    with TestClient(app_for(monkeypatch, "fake")) as c:
        assert c.put("/e2e/memory/outage", headers=HEADERS, json={"down": True}).status_code == 409


def test_the_owner_edit_journey_through_the_routes(client, env):
    assert (
        client.put(
            f"/e2e/memory/bucket/{USER_A}/Roses.md", headers=HEADERS, json={"text": "Des pivoines.\n"}
        ).status_code
        == 200
    )

    synced = client.post(f"/e2e/memory/{USER_A}/sync", headers=HEADERS).json()
    state = client.get(f"/e2e/memory/{USER_A}/state", headers=HEADERS).json()
    found = client.get(f"/e2e/memory/{USER_A}/search", headers=HEADERS, params={"q": "pivoines"}).json()

    assert synced["ok"] and synced["pulled"] >= 1
    assert "Roses.md" in state["local"] and "Roses.md" in state["indexed"] and state["outbox"] == []
    assert found == {"paths": ["Roses.md"]}


def test_the_outage_journey_through_the_routes(client, env):
    assert client.put("/e2e/memory/outage", headers=HEADERS, json={"down": True}).json() == {"down": True}
    client.post(f"/e2e/memory/{USER_A}/write", headers=HEADERS, json={"path": "Tulipes.md", "text": fiche("Avril.")})

    failed = client.post(f"/e2e/memory/{USER_A}/sync", headers=HEADERS).json()
    waiting = client.get(f"/e2e/memory/{USER_A}/state", headers=HEADERS).json()
    assert failed["ok"] is False and waiting["degraded"] and "Tulipes.md" in waiting["outbox"]
    assert client.get(f"/e2e/memory/bucket/{USER_A}/Tulipes.md", headers=HEADERS).status_code == 404

    client.put("/e2e/memory/outage", headers=HEADERS, json={"down": False})
    assert client.post(f"/e2e/memory/{USER_A}/sync", headers=HEADERS).json()["ok"]
    assert "Avril." in client.get(f"/e2e/memory/bucket/{USER_A}/Tulipes.md", headers=HEADERS).json()["text"]
    assert client.get(f"/e2e/memory/{USER_A}/state", headers=HEADERS).json()["degraded"] is False


def test_write_refuses_an_escaping_path_and_a_bucket_key_cannot_escape(client, env):
    assert (
        client.post(f"/e2e/memory/{USER_A}/write", headers=HEADERS, json={"path": "../x.md", "text": "x"}).status_code
        == 400
    )
    escaping = client.put(f"/e2e/memory/bucket/{USER_A}/%2e%2e/{USER_B}/x.md", headers=HEADERS, json={"text": "x"})
    assert escaping.status_code == 400
    assert env.bucket.objects == {}


def test_rebuild_and_reset(client, env):
    client.post(f"/e2e/memory/{USER_A}/write", headers=HEADERS, json={"path": "Roses.md", "text": fiche("x")})
    client.post(f"/e2e/memory/{USER_A}/sync", headers=HEADERS)

    assert client.post(f"/e2e/memory/{USER_A}/rebuild", headers=HEADERS).json() == {"users": 1, "files": 1}
    assert client.delete(f"/e2e/memory/{USER_A}", headers=HEADERS).json() == {"reset": True}
    state = client.get(f"/e2e/memory/{USER_A}/state", headers=HEADERS).json()
    assert state == {"degraded": False, "local": [], "indexed": [], "outbox": []}
    assert not any(k.startswith(USER_A) for k in env.bucket.objects)
    assert isinstance(env.bucket, FakeBucket)


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", "state"),
        ("post", "sync"),
        ("post", "write"),
        ("get", "search?q=x"),
        ("post", "rebuild"),
        ("post", "night"),
    ],
)
def test_a_user_id_that_is_not_one_is_a_400_not_a_500(client, method, path):
    url = f"/e2e/memory/pas-un-id/{path}"
    response = client.request(method, url, headers=HEADERS, json={"path": "x.md", "text": "x"})
    assert response.status_code == 400


def test_resetting_a_user_id_that_is_not_one_is_a_400(client):
    assert client.delete("/e2e/memory/pas-un-id", headers=HEADERS).status_code == 400


def test_the_night_route_carries_the_pass_to_its_end_and_returns_its_account(client, monkeypatch):
    running = SimpleNamespace(status="running", night="2026-10-14", calls=3, report={})
    done = SimpleNamespace(status="done", night="2026-10-14", calls=5, report={"cartes": ["a"]})
    night = AsyncMock(side_effect=[running, running, done])
    monkeypatch.setattr("app.e2e.memory_night", SimpleNamespace(run=night))

    response = client.post(f"/e2e/memory/{USER_A}/night", headers=HEADERS)

    assert response.json() == {"status": "done", "night": "2026-10-14", "calls": 5, "report": {"cartes": ["a"]}}
    assert night.await_count == 3
    assert night.await_args_list[0].kwargs == {"force": True}
    assert night.await_args_list[1].kwargs == {}


def test_the_night_route_says_so_when_there_is_nothing_to_run(client, monkeypatch):
    monkeypatch.setattr("app.e2e.memory_night", SimpleNamespace(run=AsyncMock(return_value=None)))

    assert client.post(f"/e2e/memory/{USER_A}/night", headers=HEADERS).json() == {"status": "none"}

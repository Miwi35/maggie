"""The `/e2e/memory/*` routes: a journey drives the simulated bucket and Maggie's write door through them."""

from unittest.mock import patch

import httpx
import pytest
from fastapi import FastAPI

from app.e2e import setup_e2e
from app.memory.service import MemoryService

TOKEN = "token-for-the-test"
HEADERS = {"X-E2E-Token": TOKEN}


def service_of(world) -> MemoryService:
    return MemoryService(world.bucket, world.store, world.reconciler, world.summary, world.sync)


@pytest.fixture()
def e2e(world, monkeypatch):
    monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
    monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
    app = FastAPI()
    setup_e2e(app)
    with patch("app.memory.service.memory_service", service_of(world)):
        yield httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t", headers=HEADERS)


MEMORY_ROUTES = [
    ("PUT", "/e2e/memory/bucket/outage", {"down": True}),
    ("PUT", "/e2e/memory/bucket/object", {"key": "u/a.md", "content": "x"}),
    ("GET", "/e2e/memory/bucket/object?key=u/a.md", None),
    ("GET", "/e2e/memory/bucket/keys", None),
    ("POST", "/e2e/memory/notes", {"user_id": "u", "title": "t", "body": "b"}),
    ("POST", "/e2e/memory/sync", None),
    ("GET", "/e2e/memory/outbox", None),
]


class TestAccess:
    @pytest.mark.parametrize(("method", "url", "body"), MEMORY_ROUTES)
    async def test_every_memory_route_needs_the_token(self, world, monkeypatch, method, url, body):
        monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
        app = FastAPI()
        setup_e2e(app)

        with patch("app.memory.service.memory_service", service_of(world)):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as client:
                missing = await client.request(method, url, json=body)
                wrong = await client.request(method, url, json=body, headers={"X-E2E-Token": "nope"})

        assert (missing.status_code, wrong.status_code) == (401, 401)

    @pytest.mark.parametrize("provider", ["edge", ""])
    @pytest.mark.parametrize(("method", "url", "body"), MEMORY_ROUTES)
    async def test_outside_the_e2e_stack_the_memory_routes_do_not_exist(self, monkeypatch, provider, method, url, body):
        monkeypatch.setattr("app.e2e.settings.tts_provider", provider)
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
        app = FastAPI()
        setup_e2e(app)

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as client:
            response = await client.request(method, url, json=body, headers=HEADERS)

        assert response.status_code == 404


class TestBucketSurface:
    async def test_the_owner_edits_a_note_and_reads_it_back(self, world, e2e):
        async with e2e as client:
            put = await client.put("/e2e/memory/bucket/object", json={"key": "u/a.md", "content": "Bonjour é"})
            got = await client.get("/e2e/memory/bucket/object", params={"key": "u/a.md"})
            absent = await client.get("/e2e/memory/bucket/object", params={"key": "u/none.md"})

        assert put.json() == {"etag": world.bucket.objects["u/a.md"].etag}
        assert got.json() == {"content": "Bonjour é"} and absent.json() == {"content": None}

    async def test_the_keys_are_listed_sorted(self, world, e2e):
        world.bucket.seed("u/b.md", "x")
        world.bucket.seed("u/a.md", "x")

        async with e2e as client:
            keys = (await client.get("/e2e/memory/bucket/keys")).json()

        assert keys == {"keys": ["u/a.md", "u/b.md"]}

    async def test_the_owner_edit_goes_through_no_condition_and_no_outage(self, world, e2e):
        world.bucket.down = True

        async with e2e as client:
            response = await client.put("/e2e/memory/bucket/object", json={"key": "u/a.md", "content": "x"})
            await client.get("/e2e/memory/bucket/keys")

        assert response.status_code == 200 and "u/a.md" in world.bucket.objects
        assert "put" not in world.bucket.calls, "seeding bypasses the bucket interface"

    async def test_the_outage_switch_drives_the_fake_bucket(self, world, e2e):
        async with e2e as client:
            on = await client.put("/e2e/memory/bucket/outage", json={"down": True})
            assert world.bucket.down is True
            off = await client.put("/e2e/memory/bucket/outage", json={"down": False})

        assert (on.json(), off.json()) == ({"down": True}, {"down": False})
        assert world.bucket.down is False

    async def test_a_bad_body_is_a_422(self, e2e):
        async with e2e as client:
            assert (await client.put("/e2e/memory/bucket/outage", json={})).status_code == 422
            assert (await client.put("/e2e/memory/bucket/object", json={"key": "k"})).status_code == 422
            assert (await client.post("/e2e/memory/notes", json={"user_id": "u"})).status_code == 422

    @pytest.mark.parametrize(
        ("method", "url", "body"),
        [
            ("PUT", "/e2e/memory/bucket/outage", {"down": True}),
            ("PUT", "/e2e/memory/bucket/object", {"key": "k", "content": "x"}),
            ("GET", "/e2e/memory/bucket/object?key=k", None),
            ("GET", "/e2e/memory/bucket/keys", None),
        ],
    )
    async def test_a_real_bucket_cannot_be_driven(self, world, monkeypatch, method, url, body):
        class RealLooking:
            pass

        service = service_of(world)
        service.bucket = RealLooking()  # type: ignore[assignment]
        monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
        app = FastAPI()
        setup_e2e(app)

        with patch("app.memory.service.memory_service", service):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as client:
                response = await client.request(method, url, json=body, headers=HEADERS)

        assert response.status_code == 409

    async def test_without_a_service_the_bucket_routes_answer_409(self, monkeypatch):
        monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", TOKEN)
        app = FastAPI()
        setup_e2e(app)

        with patch("app.memory.service.memory_service", None):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as client:
                responses = [
                    await client.get("/e2e/memory/bucket/keys", headers=HEADERS),
                    await client.post("/e2e/memory/notes", json={"user_id": "u", "title": "t", "body": "b"}, headers=HEADERS),
                    await client.post("/e2e/memory/sync", headers=HEADERS),
                ]

        assert [r.status_code for r in responses] == [409, 409, 409]


class TestNotesAndSync:
    async def test_a_note_written_for_maggie_lands_in_the_bucket_and_the_index(self, world, e2e):
        async with e2e as client:
            response = await client.post(
                "/e2e/memory/notes", json={"user_id": world.user, "title": "Allergie", "body": "Arachides"}
            )

        body = response.json()
        assert response.status_code == 200 and body["status"] == "written"
        assert body["path"] == f"{world.user}/Allergie.md" and body["noteId"]
        assert body["path"] in world.bucket.objects
        assert world.row(body["noteId"]).path == body["path"]

    async def test_during_an_outage_the_note_is_queued_and_the_outbox_endpoint_counts_it(self, world, e2e):
        async with e2e as client:
            assert (await client.get("/e2e/memory/outbox")).json() == {"pending": 0}
            await client.put("/e2e/memory/bucket/outage", json={"down": True})
            response = await client.post(
                "/e2e/memory/notes", json={"user_id": world.user, "title": "Plus tard", "body": "x"}
            )
            queued = (await client.get("/e2e/memory/outbox")).json()
            await client.put("/e2e/memory/bucket/outage", json={"down": False})
            sync = await client.post("/e2e/memory/sync")
            drained = (await client.get("/e2e/memory/outbox")).json()

        assert response.json()["status"] == "pending"
        assert queued == {"pending": 1} and drained == {"pending": 0}
        assert sync.json() == {"ok": True}
        assert f"{world.user}/Plus tard.md" in world.bucket.objects

    async def test_sync_picks_up_what_the_owner_edited(self, world, e2e):
        async with e2e as client:
            await client.put(
                "/e2e/memory/bucket/object",
                json={"key": f"{world.user}/Cafe.md", "content": "---\ntitle: Café\n---\nSans sucre\n"},
            )
            sync = await client.post("/e2e/memory/sync")

        assert sync.json() == {"ok": True}
        assert [r.title for r in world.rows(world.user)] == ["Café"]

    async def test_sync_reports_a_failed_pass(self, world, e2e):
        world.bucket.down = True

        async with e2e as client:
            sync = await client.post("/e2e/memory/sync")

        assert sync.json() == {"ok": False}

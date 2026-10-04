from unittest.mock import patch

import httpx
import pytest
from fastapi import FastAPI

from app.api.memory_routes import router
from app.auth import get_current_user_id
from app.memory.bucket import BucketUnavailable
from app.memory.frontmatter import NoteDoc
from app.memory.service import MemoryService


def service_of(world) -> MemoryService:
    return MemoryService(world.bucket, world.store, world.reconciler, world.summary, world.sync)


def build_app(user: str | None) -> FastAPI:
    app = FastAPI()
    app.include_router(router)
    if user is not None:
        app.dependency_overrides[get_current_user_id] = lambda: user
    return app


@pytest.fixture()
def api(world):
    """An authenticated client on the world's service, as `world.user`."""
    with patch("app.memory.service.memory_service", service_of(world)):
        yield httpx.AsyncClient(transport=httpx.ASGITransport(app=build_app(world.user)), base_url="http://t")


class TestAuthentication:
    @pytest.mark.parametrize(
        ("method", "url"),
        [("GET", "/memory/status"), ("POST", "/memory/sync"), ("POST", "/memory/rebuild"), ("GET", "/memory/events")],
    )
    async def test_every_route_refuses_a_request_without_a_token(self, world, method, url):
        with patch("app.memory.service.memory_service", service_of(world)):
            transport = httpx.ASGITransport(app=build_app(None))
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
                response = await client.request(method, url)

        assert response.status_code in (401, 403)

    @pytest.mark.parametrize(
        ("method", "url"),
        [("GET", "/memory/status"), ("POST", "/memory/sync"), ("POST", "/memory/rebuild")],
    )
    async def test_without_a_configured_bucket_the_routes_answer_503(self, world, method, url):
        with patch("app.memory.service.memory_service", None):
            transport = httpx.ASGITransport(app=build_app(world.user))
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
                response = await client.request(method, url)

        assert response.status_code == 503
        assert "not configured" in response.json()["detail"]

    async def test_events_do_not_need_the_service(self, world):
        with patch("app.memory.service.memory_service", None):
            transport = httpx.ASGITransport(app=build_app(world.user))
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
                response = await client.get("/memory/events")

        assert response.status_code == 200 and response.json() == []


class TestStatus:
    async def test_a_fresh_service_has_nothing_to_report(self, api):
        async with api as client:
            body = (await client.get("/memory/status")).json()

        assert body == {
            "degraded": False,
            "failedPasses": 0,
            "lastAttemptAt": None,
            "lastSuccessAt": None,
            "indexAgeSeconds": None,
            "outboxPending": 0,
        }

    async def test_after_a_pass_the_timestamps_are_iso_and_the_index_is_fresh(self, world, api):
        await world.sync.run_pass("test")

        async with api as client:
            body = (await client.get("/memory/status")).json()

        assert body["lastAttemptAt"] == body["lastSuccessAt"] == world.clock.now.isoformat()
        assert body["degraded"] is False and body["indexAgeSeconds"] is None

    async def test_an_outage_shows_degraded_mode_the_age_and_the_queued_writes(self, world, api):
        await world.sync.run_pass("test")
        world.bucket.down = True
        world.clock.advance(125)
        await world.store.save(world.user, NoteDoc(title="Hors ligne", body="x"))
        await world.sync.run_pass("test")

        async with api as client:
            body = (await client.get("/memory/status")).json()

        assert body["degraded"] is True
        assert body["failedPasses"] == 1
        assert body["indexAgeSeconds"] == 125
        assert body["outboxPending"] == 1
        assert body["lastSuccessAt"] != body["lastAttemptAt"]


class TestManualSync:
    async def test_reconciles_now_and_reports_the_pass(self, world, api):
        world.seed(f"{world.user}/a.md", title="A")

        async with api as client:
            response = await client.post("/memory/sync")

        body = response.json()
        assert response.status_code == 200
        assert body["ok"] is True and body["trigger"] == "manual" and body["degraded"] is False
        assert body["report"]["indexed"] == 1 and body["outboxRemaining"] == 0
        assert len(world.rows(world.user)) == 1

    async def test_flushes_the_outbox_first(self, world, api):
        world.bucket.down = True
        await world.store.save(world.user, NoteDoc(title="En attente", body="x"))
        world.bucket.down = False

        async with api as client:
            body = (await client.post("/memory/sync")).json()

        assert body["ok"] is True and body["outboxRemaining"] == 0
        assert world.outbox() == [] and len(world.bucket.objects) >= 1

    async def test_a_down_bucket_is_a_failed_outcome_not_an_error(self, world, api):
        world.bucket.down = True

        async with api as client:
            response = await client.post("/memory/sync")

        body = response.json()
        assert response.status_code == 200
        assert body["ok"] is False and body["degraded"] is True and "unavailable" in body["error"]
        assert body["report"] is None


class TestRebuild:
    async def test_rebuilds_only_the_callers_notes(self, world, api):
        mine = world.seed(f"{world.user}/a.md", title="A")
        theirs = world.seed(f"{world.other_user}/b.md", title="B")
        await world.sync.run_pass("test")
        world.update_row(theirs.id, title="Only in the index")

        async with api as client:
            response = await client.post("/memory/rebuild")

        assert response.status_code == 200
        assert response.json()["report"]["indexed"] == 1
        assert world.row(mine.id).title == "A"
        assert world.row(theirs.id).title == "Only in the index", "another user's index is not touched"

    async def test_queued_writes_block_it_with_a_409(self, world, api):
        world.bucket.down = True
        await world.store.save(world.user, NoteDoc(title="En attente", body="x"))

        async with api as client:
            response = await client.post("/memory/rebuild")

        assert response.status_code == 409
        assert len(world.outbox()) == 1

    async def test_a_down_bucket_is_a_503_and_leaves_the_index_alone(self, world, api):
        seeded = world.seed(f"{world.user}/a.md")
        await world.sync.run_pass("test")
        world.bucket.down = True

        async with api as client:
            response = await client.post("/memory/rebuild")

        assert response.status_code == 503
        assert "Rebuild failed" in response.json()["detail"]
        assert world.row(seeded.id).path == f"{world.user}/a.md"

    async def test_any_other_failure_is_a_503_too(self, world, api, monkeypatch):
        async def boom(*_a, **_k):
            raise BucketUnavailable("half-way")

        monkeypatch.setattr(world.reconciler, "reconcile", boom)

        async with api as client:
            response = await client.post("/memory/rebuild")

        assert response.status_code == 503


class TestEvents:
    async def test_lists_the_callers_journal_newest_first(self, world, api):
        world.seed(f"{world.user}/a.md", title="A")
        world.seed(f"{world.other_user}/b.md", title="B")
        await world.sync.run_pass("test")
        await world.store.save(world.user, NoteDoc(title="C", body="x"))

        async with api as client:
            events = (await client.get("/memory/events")).json()

        assert events, "the journal is not empty"
        assert [e["id"] for e in events] == sorted((e["id"] for e in events), reverse=True)
        assert {e["path"].split("/")[0] for e in events if e["path"]} == {world.user}
        first = events[0]
        assert set(first) == {"id", "kind", "noteId", "path", "detail", "at"}
        assert first["at"] is not None

    @pytest.mark.parametrize(("limit", "expected"), [(2, 2), (0, 1), (-5, 1), (10_000, 5)])
    async def test_the_limit_is_clamped(self, world, api, limit, expected):
        for i in range(5):
            world.seed(f"{world.user}/n{i}.md", title=f"n{i}")
        await world.sync.run_pass("test")
        assert len(world.events(world.user)) == 5

        async with api as client:
            events = (await client.get("/memory/events", params={"limit": limit})).json()

        assert len(events) == expected

    async def test_a_caller_never_sees_another_users_events(self, world):
        world.seed(f"{world.other_user}/b.md", title="B")
        await world.sync.run_pass("test")

        with patch("app.memory.service.memory_service", service_of(world)):
            transport = httpx.ASGITransport(app=build_app(world.user))
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
                assert (await client.get("/memory/events")).json() == []

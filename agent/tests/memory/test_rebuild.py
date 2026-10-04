import pytest
from sqlalchemy import inspect as sa_inspect

from app.db.memory_note_model import MemoryNote
from app.memory.bucket import BucketUnavailable, FakeBucket
from app.memory.frontmatter import NoteDoc
from app.memory.sync import RebuildBlocked

USAGE = {"last_used_at", "use_count"}
NOT_CONTENT = USAGE | {"indexed_at"}


def snapshot(world, user: str | None = None, ignore: set[str] = NOT_CONTENT) -> dict[str, dict]:
    """The index by path. An unreadable file has no id of its own, so its row gets a new one on every rebuild."""
    columns = [c.key for c in sa_inspect(MemoryNote).mapper.column_attrs if c.key not in ignore]
    rows = {}
    for row in world.rows(user, live_only=True):
        values = {name: getattr(row, name) for name in columns}
        if row.unreadable and "id" in values:
            values["id"] = "<unreadable>"
        rows[row.path] = values
    return rows


def populate(world):
    world.seed(f"{world.user}/a.md", title="A", body="alpha", tags=["x", "y"], pinned=True, importance=2)
    world.seed(f"{world.user}/b.md", title="B", body="beta", summary="s", links=["A"], sources=["src"])
    world.seed(f"{world.user}/archive/c.md", title="C", body="gamma", archive_reason="old")
    world.bucket.seed(f"{world.user}/hand.md", "# Made by hand\n\nno header")
    world.bucket.seed(f"{world.user}/broken.md", "---\ntitle: [oops\n---\nx")
    world.seed(f"{world.other_user}/z.md", title="Z", body="zeta")


class TestRebuild:
    async def test_dropping_and_reindexing_gives_the_same_index(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        before = snapshot(world)
        assert len(before) == 6

        report = await world.sync.rebuild()

        assert snapshot(world) == before
        assert (report.indexed, report.unreadable) == (5, 1) and report.changed_users == {world.user, world.other_user}

    async def test_the_usage_fields_come_back_by_note_id(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        a = world.row_at(f"{world.user}/a.md")
        z = world.row_at(f"{world.other_user}/z.md")
        await world.repo.mark_used(world.user, [a.id])
        await world.repo.mark_used(world.user, [a.id])
        await world.repo.mark_used(world.other_user, [z.id])
        used_at = world.row(a.id).last_used_at

        await world.sync.rebuild()

        a_after, z_after = world.row(a.id), world.row(z.id)
        assert (a_after.use_count, a_after.last_used_at) == (2, used_at)
        assert z_after.use_count == 1
        assert world.row_at(f"{world.user}/b.md").use_count == 0
        assert world.row_at(f"{world.user}/b.md").last_used_at is None

    async def test_one_users_rebuild_leaves_the_others_alone(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        other_before = snapshot(world, world.other_user, ignore=set())

        report = await world.sync.rebuild(world.user)

        assert snapshot(world, world.other_user, ignore=set()) == other_before
        assert report.changed_users == {world.user}

    async def test_the_rebuild_is_quiet_and_leaves_one_event_per_user(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        world.publisher.clear()
        events_before = len(world.events())

        await world.sync.rebuild()

        assert world.publisher.published == []
        new = world.events()[events_before:]
        assert sorted((e.user_id, e.kind) for e in new) == sorted(
            [(world.user, "rebuild"), (world.other_user, "rebuild")]
        )

    async def test_it_marks_the_service_fresh(self, world):
        populate(world)
        world.bucket.down = True
        await world.sync.run_pass("loop")
        world.bucket.down = False
        world.clock.advance(500)

        await world.sync.rebuild()

        assert world.sync.degraded is False and world.sync.failures == 0
        assert world.sync.last_success_at == world.clock() == world.sync.last_attempt_at

    async def test_queued_writes_that_cannot_reach_the_bucket_block_the_rebuild(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="Queued", body="only in the index"))
        before = snapshot(world)

        with pytest.raises(RebuildBlocked):
            await world.sync.rebuild()

        assert snapshot(world) == before
        assert world.row(queued.note_id).pending_sync is True and len(world.outbox()) == 1

    async def test_queued_writes_are_flushed_first_when_the_bucket_is_back(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="Queued", body="offline"))
        world.bucket.down = False

        await world.sync.rebuild()

        assert world.outbox() == [] and queued.path in world.bucket.objects
        assert world.row(queued.note_id).body == "offline"

    async def test_a_bucket_that_is_down_leaves_the_index_untouched(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        before = snapshot(world, ignore=set())
        world.bucket.down = True

        with pytest.raises(BucketUnavailable):
            await world.sync.rebuild()

        assert snapshot(world, ignore=set()) == before

    async def test_a_listing_that_dies_half_way_leaves_the_index_untouched(self, make_world):
        bucket = FakeBucket(page_size=2)
        world = make_world(bucket)
        populate(world)
        await world.sync.run_pass("loop")
        before = snapshot(world, ignore=set())
        bucket.fail_listing_after_pages = 1

        with pytest.raises(BucketUnavailable):
            await world.sync.rebuild()

        assert snapshot(world, ignore=set()) == before

    async def test_notes_removed_from_the_bucket_do_not_come_back(self, world):
        populate(world)
        await world.sync.run_pass("loop")
        gone = world.row_at(f"{world.user}/b.md")
        world.bucket.remove(gone.path)
        await world.sync.run_pass("loop")
        assert world.row(gone.id).deleted_at is not None

        await world.sync.rebuild()

        assert gone.path not in snapshot(world)


def test_the_cli_entry_point_reports_a_missing_bucket(monkeypatch, capsys):
    import asyncio

    from app.memory import rebuild as rebuild_cli

    async def ensure_table():
        return None

    monkeypatch.setattr(rebuild_cli.proaction_repo, "ensure_table", ensure_table)
    monkeypatch.setattr(rebuild_cli.memory_service, "configure", lambda: None)

    assert asyncio.run(rebuild_cli.run(None)) == 2
    assert "MEMORY_BUCKET is not set" in capsys.readouterr().err



class TestRebuildFailure:
    async def test_usage_survives_a_reconcile_that_fails_after_the_drop(self, world, monkeypatch):
        populate(world)
        await world.sync.run_pass("loop")
        a = world.row_at(f"{world.user}/a.md")
        await world.repo.mark_used(world.user, [a.id])
        await world.repo.mark_used(world.user, [a.id])
        used_at = world.row(a.id).last_used_at

        async def broken(quiet: bool = False):
            raise BucketUnavailable("died after the drop")

        real = world.reconciler.reconcile
        monkeypatch.setattr(world.reconciler, "reconcile", broken)
        with pytest.raises(BucketUnavailable):
            await world.sync.rebuild()
        assert world.rows() == []

        monkeypatch.setattr(world.reconciler, "reconcile", real)
        outcome = await world.sync.run_pass("loop")

        assert outcome.ok
        after = world.row(a.id)
        assert (after.use_count, after.last_used_at) == (2, used_at)

    async def test_pending_usage_is_dropped_once_a_complete_pass_has_nothing_left_to_give_it_to(self, world):
        world.sync._pending_usage = {"gone": (None, 3)}

        await world.sync.run_pass("loop")

        assert world.sync._pending_usage == {}


def test_the_cli_refuses_to_run_without_offline(capsys, monkeypatch):
    from app.memory import rebuild as rebuild_cli

    called = []

    async def run(user_id):
        called.append(user_id)
        return 0

    monkeypatch.setattr(rebuild_cli, "run", run)

    assert rebuild_cli.main([]) == 2
    assert rebuild_cli.main(["01ABC"]) == 2
    assert "--offline" in capsys.readouterr().err and "stopped" in rebuild_cli.__doc__
    assert called == []

    assert rebuild_cli.main(["--offline", "01ABC"]) == 0
    assert rebuild_cli.main(["--offline"]) == 0
    assert called == ["01ABC", None]

import pytest

from app.memory.bucket import BucketUnavailable
from app.memory.frontmatter import NoteDoc, parse_note
from tests.memory.conftest import RacyBucket


def stored(world, key: str) -> NoteDoc:
    return parse_note(world.bucket.objects[key].body.decode(), "x")


class TestFlush:
    async def test_a_queued_creation_is_pushed_when_the_bucket_is_back(self, world):
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="A", body="offline"))
        world.bucket.down = False
        world.publisher.clear()

        report = await world.store.flush_outbox()

        assert (report.pushed, report.conflicts, report.dropped, report.remaining) == (1, 0, 0, 0)
        assert stored(world, queued.path).body == "offline"
        assert world.outbox() == []
        row = world.row(queued.note_id)
        assert row.pending_sync is False and row.etag == world.bucket.objects[queued.path].etag
        assert f"{world.user}/SOMMAIRE.md" in world.bucket.objects

    async def test_the_pushed_version_is_not_read_back_by_the_next_pass(self, world):
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="A", body="offline"))
        world.bucket.down = False
        await world.store.flush_outbox()
        world.publisher.clear()

        await world.reconciler.reconcile()

        assert world.bucket.count("get") == 0
        assert world.row(queued.note_id).pending_sync is False
        assert world.publisher.published == []

    async def test_a_queued_update_is_pushed_if_match_the_version_it_read(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        bucket.down = True
        await world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v2"))
        bucket.down = False
        bucket.puts.clear()

        report = await world.store.flush_outbox()

        assert report.pushed == 1
        assert (created.path, created.etag, False) in bucket.puts
        assert stored(world, created.path).body == "v2"
        assert world.row(created.note_id).pending_sync is False and world.outbox() == []

    async def test_a_queued_archive_is_pushed_and_the_original_removed(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        world.bucket.down = True
        await world.store.archive(world.user, created.note_id)
        world.bucket.down = False

        report = await world.store.flush_outbox()

        assert report.pushed == 1 and report.remaining == 0
        assert f"{world.user}/archive/A.md" in world.bucket.objects
        assert created.path not in world.bucket.objects
        row = world.row(created.note_id)
        assert (row.status, row.pending_sync) == ("archived", False)

    async def test_a_queued_delete_is_pushed_when_the_file_is_still_the_moved_version(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        original_delete = world.bucket.delete

        async def unreachable_delete(key):
            raise BucketUnavailable("delete failed")

        world.bucket.delete = unreachable_delete  # type: ignore[method-assign]
        await world.store.archive(world.user, created.note_id)
        world.bucket.delete = original_delete  # type: ignore[method-assign]
        (queued,) = world.outbox()
        assert (queued.op, queued.path) == ("delete", created.path)
        assert created.path in world.bucket.objects

        report = await world.store.flush_outbox()

        assert report.pushed == 1 and created.path not in world.bucket.objects and world.outbox() == []

    async def test_a_queued_delete_is_dropped_when_the_owner_changed_the_file(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        original_delete = world.bucket.delete

        async def unreachable_delete(key):
            raise BucketUnavailable("delete failed")

        world.bucket.delete = unreachable_delete  # type: ignore[method-assign]
        await world.store.archive(world.user, created.note_id)
        world.bucket.delete = original_delete  # type: ignore[method-assign]
        world.bucket.seed(created.path, "the owner reused this name")

        report = await world.store.flush_outbox()

        assert report.dropped == 1 and report.pushed == 0 and world.outbox() == []
        assert world.bucket.objects[created.path].body == b"the owner reused this name"

    async def test_a_changed_bucket_version_goes_to_the_conflict_path(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        bucket.down = True
        await world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="Maggie's v2"))
        bucket.down = False
        owner_etag = bucket.seed(created.path, "---\nid: %s\ntitle: A\n---\n\nowner v2\n" % created.note_id)
        bucket.puts.clear()
        world.publisher.clear()

        report = await world.store.flush_outbox()

        assert (report.pushed, report.conflicts, report.remaining) == (0, 1, 0)
        assert bucket.objects[created.path].etag == owner_etag, "the owner's version stays in place"
        assert not [p for p in bucket.puts if p[0] == created.path], "nothing is pushed over his file"
        (copy_key,) = [k for k in bucket.objects if "/conflits/" in k]
        assert parse_note(bucket.objects[copy_key].body.decode(), "x").body == "Maggie's v2"
        event = world.events(kind="conflict")[0]
        assert event.detail["conflict_path"] == copy_key and event.detail["flushed"] is True
        assert world.publisher.kinds() == ["conflict"]
        assert world.outbox() == []
        assert world.row(created.note_id).pending_sync is False

        await world.reconciler.reconcile()
        assert world.row(created.note_id).body == "owner v2"

    async def test_a_creation_whose_name_was_taken_meanwhile_moves_to_the_next_suffix(self, world):
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="A", body="mine"))
        world.bucket.down = False
        world.bucket.seed(queued.path, "the owner made an A.md too")

        first = await world.store.flush_outbox()

        assert first.pushed == 0 and first.remaining == 1
        (item,) = world.outbox()
        assert item.path == f"{world.user}/A-2.md"
        assert world.row(queued.note_id).path == f"{world.user}/A-2.md"
        assert world.bucket.objects[queued.path].body == b"the owner made an A.md too"

        second = await world.store.flush_outbox()

        assert second.pushed == 1 and second.remaining == 0
        assert stored(world, f"{world.user}/A-2.md").body == "mine"

    async def test_a_put_that_landed_but_timed_out_is_recognised(self, world):
        world.bucket.down = True
        queued = await world.store.save(world.user, NoteDoc(title="A", body="landed"))
        world.bucket.down = False
        (item,) = world.outbox()
        landed_etag = world.bucket.seed(queued.path, item.content)

        report = await world.store.flush_outbox()

        assert (report.pushed, report.conflicts) == (1, 0)
        assert world.row(queued.note_id).etag == landed_etag and not [k for k in world.bucket.objects if "conflits" in k]

    async def test_the_flush_stops_at_the_first_outage_and_leaves_the_rest(self, world):
        world.bucket.down = True
        first = await world.store.save(world.user, NoteDoc(title="One", body="1"))
        second = await world.store.save(world.user, NoteDoc(title="Two", body="2"))
        third = await world.store.save(world.user, NoteDoc(title="Three", body="3"))
        world.bucket.down = False
        original_put = world.bucket.put

        async def put_then_fail(key, body, **kwargs):
            etag = await original_put(key, body, **kwargs)
            world.bucket.down = True
            return etag

        world.bucket.put = put_then_fail  # type: ignore[method-assign]
        report = await world.store.flush_outbox()

        assert report.pushed == 1 and report.remaining == 2
        assert first.path in world.bucket.objects
        assert second.path not in world.bucket.objects and third.path not in world.bucket.objects
        assert sorted(o.path for o in world.outbox()) == sorted([second.path, third.path])
        assert world.row(first.note_id).pending_sync is False
        assert world.row(second.note_id).pending_sync is True

    async def test_an_outage_leaves_the_whole_outbox_queued(self, world):
        world.bucket.down = True
        await world.store.save(world.user, NoteDoc(title="One", body="1"))

        report = await world.store.flush_outbox()

        assert report.pushed == 0 and report.remaining == 1

    async def test_a_newer_write_to_the_same_path_replaces_the_older_outbox_row(self, world):
        world.bucket.down = True
        first = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        await world.store.save(world.user, NoteDoc(id=first.note_id, title="A", body="v2"))
        await world.store.save(world.user, NoteDoc(id=first.note_id, title="A", body="v3"))
        world.bucket.down = False
        assert len(world.outbox()) == 1

        report = await world.store.flush_outbox()

        assert report.pushed == 1
        assert stored(world, first.path).body == "v3"
        assert [k for k in world.bucket.objects if k.endswith(".md") and "SOMMAIRE" not in k] == [first.path]

    async def test_a_write_after_a_queued_one_goes_behind_it_even_when_the_bucket_is_back(self, world):
        world.bucket.down = True
        first = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        world.bucket.down = False

        second = await world.store.save(world.user, NoteDoc(id=first.note_id, title="A", body="v2"))

        assert second.status == "pending", "the order of the writes is kept: the flush pushes both as one"
        report = await world.store.flush_outbox()
        assert report.pushed == 1 and stored(world, first.path).body == "v2"

    async def test_an_outbox_row_for_a_path_that_is_no_note_is_dropped(self, world):
        from app.db.memory_note_model import MemoryOutbox

        with world.factory() as session:
            session.add(MemoryOutbox(user_id=world.user, path=f"{world.user}/conflits/x.md", op="put", content="x"))
            session.commit()

        report = await world.store.flush_outbox()

        assert report.dropped == 1 and world.outbox() == []


@pytest.mark.parametrize("op", ["flush", "reconcile"])
async def test_a_pending_note_is_never_lost_whichever_runs_first(world, op):
    world.bucket.down = True
    queued = await world.store.save(world.user, NoteDoc(title="A", body="offline"))
    world.bucket.down = False

    if op == "reconcile":
        await world.reconciler.reconcile()
        assert world.row(queued.note_id).deleted_at is None
    await world.store.flush_outbox()
    await world.reconciler.reconcile()

    row = world.row(queued.note_id)
    assert row.deleted_at is None and row.body == "offline" and world.bucket.objects[queued.path]

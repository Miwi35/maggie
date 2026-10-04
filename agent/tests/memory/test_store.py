import asyncio
import re
import pytest

from app.db.memory_note_repository import as_utc
from app.memory.bucket import FakeBucket
from app.memory.frontmatter import NoteDoc, parse_note
from app.memory.store import NoteNotFound
from app.memory.paths import UnsafePath
from app.memory.summary import HEADER
from tests.memory.conftest import RacyBucket

CONFLICT_COPY = re.compile(r"^[0-9A-Z]{26}/conflits/(?P<slug>.+)-\d{8}T\d{12}Z\.md$")


def stored(world, key: str) -> NoteDoc:
    return parse_note(world.bucket.objects[key].body.decode(), "x")


class TestCreate:
    async def test_a_note_becomes_a_file_a_row_an_event_and_a_change(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        doc = NoteDoc(title="Allergies", body="Arachide", tags=["santé"], summary="Allergie")

        result = await world.store.save(world.user, doc)

        key = f"{world.user}/Allergies.md"
        assert (result.status, result.path) == ("written", key)
        assert bucket.puts[0] == (key, None, True), "a creation must be If-None-Match"
        written = stored(world, key)
        assert written.id == result.note_id and written.body == "Arachide" and written.tags == ["santé"]
        assert written.created_at is not None
        row = world.row(result.note_id)
        assert row.etag == result.etag == bucket.objects[key].etag
        assert (row.title, row.status, row.pending_sync) == ("Allergies", "active", False)
        event = world.events(kind="created")[0]
        assert event.note_id == result.note_id and event.detail["by"] == "maggie"
        assert world.publisher.published == [
            (
                f"/memory/{world.user}",
                {"type": "created", "noteId": result.note_id, "path": key, "title": "Allergies", "status": "active"},
            )
        ]

    async def test_the_summary_is_generated_with_a_do_not_edit_header_and_no_counters(self, world):
        first = await world.store.save(world.user, NoteDoc(title="Zèbre", body="b", summary="rayé", tags=["zoo"]))
        await world.store.save(world.user, NoteDoc(title="Allergies", body="b"))

        text = world.bucket.objects[f"{world.user}/SOMMAIRE.md"].body.decode()

        assert text.startswith(HEADER) and "do not edit" in text
        assert "[Allergies](Allergies.md)" in text and "[Zèbre](Zebre.md) — rayé (zoo)" in text
        assert text.index("Allergies") < text.index("Zèbre")
        await world.repo.mark_used(world.user, [first.note_id])
        assert await world.summary.render(world.user) == text
        assert "use_count" not in text and "last_used" not in text

    async def test_the_summary_is_not_rewritten_when_nothing_changed(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        await world.store.save(world.user, NoteDoc(title="A", body="b"))
        puts = len(bucket.puts)

        assert await world.summary.refresh(world.user) is False
        assert len(bucket.puts) == puts

    async def test_the_summary_does_not_break_the_write_when_the_bucket_refuses_it(self, world):
        world.summary.bucket = FakeBucket()
        world.summary.bucket.down = True

        result = await world.store.save(world.user, NoteDoc(title="A", body="b"))

        assert result.status == "written"

    async def test_a_name_taken_in_the_index_gets_a_numeric_suffix(self, world):
        first = await world.store.save(world.user, NoteDoc(title="Café", body="1"))
        second = await world.store.save(world.user, NoteDoc(title="Café", body="2"))
        third = await world.store.save(world.user, NoteDoc(title="Café", body="3"))

        assert [first.path, second.path, third.path] == [
            f"{world.user}/Cafe.md",
            f"{world.user}/Cafe-2.md",
            f"{world.user}/Cafe-3.md",
        ]
        assert len({first.note_id, second.note_id, third.note_id}) == 3

    async def test_a_name_taken_in_the_bucket_by_the_owner_is_never_overwritten(self, world):
        etag = world.bucket.seed(f"{world.user}/Notes.md", "the owner's file, not indexed yet")

        result = await world.store.save(world.user, NoteDoc(title="Notes", body="Maggie's"))

        assert result.path == f"{world.user}/Notes-2.md"
        assert world.bucket.objects[f"{world.user}/Notes.md"].etag == etag

    async def test_an_unknown_id_creates_a_note_with_a_fresh_id(self, world):
        result = await world.store.save(world.user, NoteDoc(id="01ARZ3NDEKTSV4RRFFQ69G5FAV", title="X", body="b"))

        assert result.status == "written" and result.note_id != "01ARZ3NDEKTSV4RRFFQ69G5FAV"

    async def test_a_title_cannot_escape_the_users_prefix(self, world):
        result = await world.store.save(world.user, NoteDoc(title="../../other/evil", body="b"))

        assert result.path == f"{world.user}/otherevil.md"
        assert all(key.startswith(f"{world.user}/") for key in world.bucket.objects)

    @pytest.mark.parametrize("user_id", ["..", "a/b", "", "/root"])
    async def test_a_user_id_that_is_not_a_ulid_is_refused_before_any_io(self, world, user_id):
        with pytest.raises(UnsafePath):
            await world.store.save(user_id, NoteDoc(title="X", body="b"))

        assert world.bucket.calls == []


class TestUpdate:
    async def test_an_update_keeps_the_file_name_and_is_if_match(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        created = await world.store.save(world.user, NoteDoc(title="Allergies", body="v1", tags=["a"]))
        bucket.puts.clear()

        result = await world.store.save(
            world.user, NoteDoc(id=created.note_id, title="Allergies alimentaires", body="v2")
        )

        assert result.status == "written" and result.path == created.path
        assert bucket.puts[0] == (created.path, created.etag, False)
        assert stored(world, created.path).title == "Allergies alimentaires"
        row = world.row(created.note_id)
        assert row.title == "Allergies alimentaires" and row.body == "v2" and row.etag == result.etag
        assert world.events(kind="updated")
        assert len(world.rows()) == 1 and created.path in world.bucket.objects

    async def test_an_update_keeps_the_creation_date(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        before = as_utc(world.row(created.note_id).created_at)

        await world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v2"))

        assert as_utc(world.row(created.note_id).created_at) == before

    async def test_a_note_edited_by_the_owner_and_reconciled_is_updated_on_his_version(self, world):
        seeded = world.seed(f"{world.user}/a.md", title="A", body="v1")
        await world.reconciler.reconcile()

        result = await world.store.save(world.user, NoteDoc(id=seeded.id, title="A", body="v2"))

        assert result.status == "written" and stored(world, seeded.key).body == "v2"

    async def test_a_deleted_note_is_recreated_rather_than_updated(self, world):
        seeded = world.seed(f"{world.user}/a.md")
        world.seed(f"{world.user}/b.md")
        await world.reconciler.reconcile()
        world.bucket.remove(seeded.key)
        await world.reconciler.reconcile()

        result = await world.store.save(world.user, NoteDoc(id=seeded.id, title="A", body="back"))

        assert result.status == "written" and result.note_id != seeded.id


class TestArchive:
    async def test_archive_and_restore_move_the_file(self, world):
        created = await world.store.save(world.user, NoteDoc(title="Plan", body="b"))

        archived = await world.store.archive(world.user, created.note_id, "obsolète")

        archive_path = f"{world.user}/archive/Plan.md"
        assert (archived.status, archived.path) == ("written", archive_path)
        assert created.path not in world.bucket.objects
        doc = stored(world, archive_path)
        assert doc.id == created.note_id and doc.archive_reason == "obsolète" and doc.archived_at is not None
        row = world.row(created.note_id)
        assert (row.status, row.path, row.archive_reason) == ("archived", archive_path, "obsolète")
        assert world.events(kind="archived")

        restored = await world.store.restore(world.user, created.note_id)

        assert restored.path == created.path and archive_path not in world.bucket.objects
        assert stored(world, created.path).archived_at is None
        row = world.row(created.note_id)
        assert (row.status, row.archived_at, row.archive_reason) == ("active", None, None)
        assert world.events(kind="restored")
        assert [r.id for r in world.rows()] == [created.note_id]

    async def test_a_taken_archive_name_gets_a_suffix(self, world):
        world.bucket.seed(f"{world.user}/archive/Plan.md", "older archive")
        created = await world.store.save(world.user, NoteDoc(title="Plan", body="b"))

        archived = await world.store.archive(world.user, created.note_id)

        assert archived.path == f"{world.user}/archive/Plan-2.md"

    async def test_archiving_an_archived_note_changes_nothing(self, world):
        created = await world.store.save(world.user, NoteDoc(title="Plan", body="b"))
        await world.store.archive(world.user, created.note_id)
        puts = world.bucket.count("put")

        again = await world.store.archive(world.user, created.note_id)

        assert again.status == "written" and world.bucket.count("put") == puts

    async def test_an_unknown_or_foreign_note_is_not_found(self, world):
        mine = await world.store.save(world.user, NoteDoc(title="Plan", body="b"))

        with pytest.raises(NoteNotFound):
            await world.store.archive(world.other_user, mine.note_id)
        with pytest.raises(NoteNotFound):
            await world.store.restore(world.user, "01ARZ3NDEKTSV4RRFFQ69G5FAV")

    async def test_the_original_is_left_when_the_owner_changed_it_during_the_move(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1")
        await world.reconciler.reconcile()
        world.bucket.seed(seeded.key, "the owner edited it just now")  # not reconciled yet

        # The move reads the index's ETag, which is now stale: the original must not be deleted.
        await world.store.archive(world.user, seeded.id)

        assert seeded.key in world.bucket.objects
        assert world.events(kind="move_left_original")


class TestDegraded:
    async def test_a_bucket_outage_queues_the_write_and_serves_the_new_content(self, world):
        world.bucket.down = True

        result = await world.store.save(world.user, NoteDoc(title="Hors ligne", body="queued"))

        assert result.status == "pending" and result.etag is None
        row = world.row(result.note_id)
        assert row.pending_sync is True and row.body == "queued"
        (queued,) = world.outbox()
        assert (queued.op, queued.path, queued.note_id, queued.create_only) == ("put", result.path, result.note_id, True)
        assert parse_note(queued.content, "x").body == "queued"
        assert world.events(kind="queued") and world.publisher.kinds() == ["created"]
        assert [n.id for n in await world.repo.notes_for_prompt(world.user, 5)] == [result.note_id]

    async def test_the_row_and_the_outbox_row_are_one_transaction(self, world, monkeypatch):
        world.bucket.down = True
        real_add_event = world.repo.add_event

        async def failing_add_event(session, user_id, kind, *args, **kwargs):
            if kind == "queued":
                raise RuntimeError("database went away before the commit")
            await real_add_event(session, user_id, kind, *args, **kwargs)

        monkeypatch.setattr(world.repo, "add_event", failing_add_event)

        with pytest.raises(RuntimeError):
            await world.store.save(world.user, NoteDoc(title="Atomic", body="b"))

        assert world.rows() == [] and world.outbox() == []

    async def test_an_update_while_down_is_queued_against_the_version_it_read(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        world.bucket.down = True

        result = await world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v2"))

        assert result.status == "pending"
        (queued,) = world.outbox()
        assert queued.base_etag == created.etag and queued.create_only is False
        row = world.row(created.note_id)
        assert row.body == "v2" and row.pending_sync is True and row.etag == created.etag

    async def test_a_second_write_to_a_queued_note_replaces_the_first(self, world):
        world.bucket.down = True
        first = await world.store.save(world.user, NoteDoc(title="A", body="v1"))

        second = await world.store.save(world.user, NoteDoc(id=first.note_id, title="A", body="v2"))

        assert second.status == "pending" and second.path == first.path
        (queued,) = world.outbox()
        assert parse_note(queued.content, "x").body == "v2"
        assert world.row(first.note_id).body == "v2"

    async def test_a_title_already_queued_gets_a_suffix(self, world):
        world.bucket.down = True
        first = await world.store.save(world.user, NoteDoc(title="A", body="1"))
        second = await world.store.save(world.user, NoteDoc(title="A", body="2"))

        assert second.path != first.path and len(world.outbox()) == 2

    async def test_an_archive_while_down_is_queued_as_a_move(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        world.bucket.down = True

        result = await world.store.archive(world.user, created.note_id)

        assert result.status == "pending"
        (queued,) = world.outbox()
        assert queued.path == f"{world.user}/archive/A.md"
        assert (queued.move_from, queued.move_from_etag) == (created.path, created.etag)
        assert world.row(created.note_id).status == "archived"

    async def test_a_put_slower_than_the_turn_bound_is_queued(self, make_world):
        bucket = RacyBucket()
        bucket.put_gate = asyncio.Event()
        world = make_world(bucket)
        world.store.timeout_seconds = 0.01

        result = await world.store.save(world.user, NoteDoc(title="Slow", body="b"))

        assert result.status == "pending"
        assert world.row(result.note_id).pending_sync is True
        assert len(world.outbox()) == 1
        assert result.path not in bucket.objects

        bucket.put_gate = None
        flushed = await world.store.flush_outbox()
        assert flushed.pushed == 1 and result.path in bucket.objects


class TestConflict:
    async def test_an_owner_edit_wins_and_maggies_version_goes_to_conflits(self, world):
        seeded = world.seed(f"{world.user}/a.md", title="A", body="v1")
        await world.reconciler.reconcile()
        owner_etag = world.bucket.seed(seeded.key, "---\nid: %s\ntitle: A\n---\n\nthe owner's v2\n" % seeded.id)
        world.publisher.clear()

        result = await world.store.save(world.user, NoteDoc(id=seeded.id, title="A", body="Maggie's v2"))

        assert result.status == "conflict" and result.etag is None
        match = CONFLICT_COPY.match(result.conflict_path or "")
        assert match and match["slug"] == "a" and result.conflict_path.startswith(f"{world.user}/conflits/")
        assert world.bucket.objects[seeded.key].etag == owner_etag, "the owner's file must stay as it is"
        assert stored(world, result.conflict_path).body == "Maggie's v2"
        event = world.events(kind="conflict")[0]
        assert event.detail["conflict_path"] == result.conflict_path and event.detail["owner_etag"] == owner_etag
        assert world.publisher.kinds() == ["conflict"]
        assert world.row(seeded.id).pending_sync is False

        await world.reconciler.reconcile()
        row = world.row(seeded.id)
        assert row.body == "the owner's v2" and row.etag == owner_etag
        assert len(world.rows()) == 1, "the conflict copy must not become a note"

    async def test_an_injected_412_takes_the_same_path(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        world.bucket.fail_next_puts_with_412 = 1

        result = await world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v2"))

        assert result.status == "conflict"
        assert stored(world, created.path).body == "v1"

    async def test_a_file_the_owner_deleted_is_recreated_not_conflicted(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1")
        await world.reconciler.reconcile()
        world.bucket.remove(seeded.key)

        result = await world.store.save(world.user, NoteDoc(id=seeded.id, title="Titre", body="v2"))

        assert result.status == "written" and stored(world, seeded.key).body == "v2"
        assert not world.events(kind="conflict")

    async def test_a_note_whose_index_etag_was_cleared_by_a_conflict_does_not_crash_the_next_save(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1")
        await world.reconciler.reconcile()
        world.bucket.seed(seeded.key, "the owner's version")
        first = await world.store.save(world.user, NoteDoc(id=seeded.id, title="A", body="mine"))
        assert first.status == "conflict" and world.row(seeded.id).etag == ""

        second = await world.store.save(world.user, NoteDoc(id=seeded.id, title="A", body="mine again"))

        assert second.status == "conflict"
        assert world.bucket.objects[seeded.key].body == b"the owner's version"

    async def test_a_conflict_during_an_outage_is_queued(self, world):
        created = await world.store.save(world.user, NoteDoc(title="A", body="v1"))
        original_put = world.bucket.put
        calls = {"n": 0}

        async def put(key, body, **kwargs):
            calls["n"] += 1
            if calls["n"] == 1:
                world.bucket.fail_next_puts_with_412 = 1
            if calls["n"] == 2:
                world.bucket.down = True
            return await original_put(key, body, **kwargs)

        world.bucket.put = put  # type: ignore[method-assign]
        result = await world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v2"))

        assert result.status == "pending" and len(world.outbox()) == 1


class TestConcurrency:
    async def test_two_saves_of_one_note_are_serialised_not_conflicted(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        created = await world.store.save(world.user, NoteDoc(title="A", body="v0"))
        bucket.puts.clear()
        bucket.put_gate = asyncio.Event()

        first = asyncio.ensure_future(world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v1")))
        await bucket.put_started.wait()
        second = asyncio.ensure_future(world.store.save(world.user, NoteDoc(id=created.note_id, title="A", body="v2")))
        for _ in range(20):
            await asyncio.sleep(0)
        assert [p[0] for p in bucket.puts] == [created.path], "the second save must wait for the note's lock"

        bucket.put_gate.set()
        results = await asyncio.gather(first, second)

        assert [r.status for r in results] == ["written", "written"]
        assert stored(world, created.path).body == "v2"
        assert world.row(created.note_id).body == "v2"
        assert not world.events(kind="conflict")
        assert not [k for k in bucket.objects if "conflits" in k]

    async def test_two_creations_with_one_title_get_two_names(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        bucket.put_gate = asyncio.Event()

        first = asyncio.ensure_future(world.store.save(world.user, NoteDoc(title="Same", body="1")))
        await bucket.put_started.wait()
        second = asyncio.ensure_future(world.store.save(world.user, NoteDoc(title="Same", body="2")))
        for _ in range(20):
            await asyncio.sleep(0)
        bucket.put_gate.set()
        results = await asyncio.gather(first, second)

        assert sorted(r.path for r in results) == [f"{world.user}/Same-2.md", f"{world.user}/Same.md"]
        assert len({r.note_id for r in results}) == 2 and len(world.rows()) == 2


import logging
from datetime import UTC, datetime

import pytest

from app.db.memory_note_repository import as_utc
from app.memory.bucket import BucketUnavailable, FakeBucket, ObjectNotFound
from app.memory.frontmatter import NoteDoc, parse_note, render_note
from app.memory.ulid import new_ulid
from tests.memory.conftest import RacyBucket

EARLY = datetime(2026, 1, 1, 8, 0, tzinfo=UTC)
LATER = datetime(2026, 1, 2, 9, 30, tzinfo=UTC)


class TestIndexing:
    async def test_a_new_file_is_indexed(self, world):
        seeded = world.seed(f"{world.user}/Allergies.md", title="Allergies", body="Arachide", at=EARLY, tags=["santé"])

        report = await world.reconciler.reconcile()

        row = world.row(seeded.id)
        assert (row.user_id, row.path, row.status) == (world.user, seeded.key, "active")
        assert (row.title, row.body, row.tags) == ("Allergies", "Arachide", ["santé"])
        assert row.etag == seeded.etag and as_utc(row.updated_at) == EARLY
        assert row.deleted_at is None and row.pending_sync is False and row.unreadable is False
        assert report.listed == 1 and report.fetched == 1 and report.indexed == 1
        assert [e.kind for e in world.events(world.user)] == ["created"]
        assert world.publisher.published[0][0] == f"/memory/{world.user}"
        assert world.publisher.kinds() == ["created"]

    async def test_an_unchanged_etag_costs_no_get(self, world):
        world.seed(f"{world.user}/a.md")
        world.seed(f"{world.user}/archive/b.md")
        await world.reconciler.reconcile()
        gets = world.bucket.count("get")
        assert gets == 2
        world.publisher.clear()

        report = await world.reconciler.reconcile()

        assert world.bucket.count("get") == gets
        assert report.fetched == 0 and report.listed == 2
        assert world.publisher.published == []
        assert len(world.events()) == 2

    async def test_an_edited_body_updates_the_row_and_takes_the_object_clock(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1", at=EARLY)
        await world.reconciler.reconcile()

        world.seed(seeded.key, body="v2", note_id=seeded.id, at=LATER)
        await world.reconciler.reconcile()

        row = world.row(seeded.id)
        assert row.body == "v2" and as_utc(row.updated_at) == LATER
        assert [e.kind for e in world.events()] == ["created", "updated"]
        assert len(world.rows()) == 1

    async def test_a_retitle_updates_the_same_row(self, world):
        seeded = world.seed(f"{world.user}/a.md", title="Old")
        await world.reconciler.reconcile()

        world.seed(seeded.key, title="New", note_id=seeded.id)
        await world.reconciler.reconcile()

        assert world.row(seeded.id).title == "New"
        assert world.events(kind="updated")

    async def test_an_etag_only_change_is_not_announced(self, world):
        seeded = world.seed(f"{world.user}/a.md", title="T", body="B")
        await world.reconciler.reconcile()
        world.publisher.clear()

        # Same content, different bytes (an extra blank line): the ETag moves, nothing a reader sees does.
        world.bucket.seed(seeded.key, render_note(NoteDoc(id=seeded.id, title="T", body="B")) + "\n\n")
        await world.reconciler.reconcile()

        assert world.publisher.published == []
        assert world.row(seeded.id).etag == world.bucket.objects[seeded.key].etag

    async def test_a_rename_updates_one_row_instead_of_deleting_and_creating(self, world):
        seeded = world.seed(f"{world.user}/Old name.md", title="Old name")
        await world.reconciler.reconcile()
        world.publisher.clear()

        world.bucket.remove(seeded.key)
        new_key = f"{world.user}/New name.md"
        world.seed(new_key, title="Old name", note_id=seeded.id)
        report = await world.reconciler.reconcile()

        rows = world.rows()
        assert len(rows) == 1 and rows[0].id == seeded.id
        assert rows[0].path == new_key and rows[0].deleted_at is None
        assert report.soft_deleted == 0
        assert world.events(kind="renamed") and not world.events(kind="deleted")
        assert world.publisher.kinds() == ["renamed"]

    async def test_a_move_to_archive_keeps_the_note_and_marks_it_archived(self, world):
        seeded = world.seed(f"{world.user}/a.md")
        await world.reconciler.reconcile()

        world.bucket.remove(seeded.key)
        archived_key = f"{world.user}/archive/a.md"
        world.seed(archived_key, note_id=seeded.id, at=LATER)
        await world.reconciler.reconcile()

        rows = world.rows()
        assert len(rows) == 1 and rows[0].id == seeded.id
        assert (rows[0].status, rows[0].path) == ("archived", archived_key)
        assert as_utc(rows[0].archived_at) == LATER
        assert world.events(kind="archived")

    async def test_moving_back_restores_it(self, world):
        seeded = world.seed(f"{world.user}/archive/a.md")
        await world.reconciler.reconcile()

        world.bucket.remove(seeded.key)
        world.seed(f"{world.user}/a.md", note_id=seeded.id)
        await world.reconciler.reconcile()

        row = world.row(seeded.id)
        assert row.status == "active" and row.archived_at is None
        assert world.events(kind="restored")

    async def test_usage_fields_survive_an_update_and_a_rename(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1")
        await world.reconciler.reconcile()
        await world.repo.mark_used(world.user, [seeded.id])
        await world.repo.mark_used(world.user, [seeded.id])
        before = world.row(seeded.id)
        assert before.use_count == 2 and before.last_used_at is not None

        world.seed(seeded.key, body="v2", note_id=seeded.id)
        await world.reconciler.reconcile()
        world.bucket.remove(seeded.key)
        world.seed(f"{world.user}/b.md", body="v2", note_id=seeded.id)
        await world.reconciler.reconcile()

        after = world.row(seeded.id)
        assert after.path == f"{world.user}/b.md" and after.body == "v2"
        assert after.use_count == 2 and as_utc(after.last_used_at) == as_utc(before.last_used_at)


class TestIdentity:
    async def test_two_files_with_one_id_keep_it_on_the_first_path_and_restamp_the_other(self, world):
        note_id = new_ulid()
        world.seed(f"{world.user}/a.md", title="A", note_id=note_id)
        world.seed(f"{world.user}/b.md", title="B", note_id=note_id)

        report = await world.reconciler.reconcile()

        assert report.duplicates == 1
        assert world.row(note_id).path == f"{world.user}/a.md"
        other = world.row_at(f"{world.user}/b.md")
        assert other.id != note_id
        written = parse_note(world.bucket.objects[f"{world.user}/b.md"].body.decode(), "b")
        assert written.id == other.id and written.title == "B"
        assert parse_note(world.bucket.objects[f"{world.user}/a.md"].body.decode(), "a").id == note_id
        assert world.events(kind="duplicate_id")

    async def test_the_file_the_index_already_tracks_keeps_the_id_whatever_the_order(self, world):
        seeded = world.seed(f"{world.user}/z.md", title="Z")
        await world.reconciler.reconcile()

        world.seed(f"{world.user}/a.md", title="Copy of Z", note_id=seeded.id)
        await world.reconciler.reconcile()

        assert world.row(seeded.id).path == f"{world.user}/z.md"
        assert world.row_at(f"{world.user}/a.md").id != seeded.id
        assert len(world.rows()) == 2

    async def test_an_id_held_by_another_user_is_not_taken_over(self, world):
        note_id = new_ulid()
        mine = world.seed(f"{world.user}/a.md", note_id=note_id)
        theirs = world.seed(f"{world.other_user}/a.md", note_id=note_id)

        await world.reconciler.reconcile()

        rows = world.rows()
        assert len(rows) == 2 and len({r.id for r in rows}) == 2
        assert note_id in {r.id for r in rows}
        assert {r.path for r in rows} == {mine.key, theirs.key}
        assert world.events(kind="duplicate_id")

    async def test_a_hand_made_file_gets_an_id_written_back_with_if_match(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        key = f"{world.user}/Idée.md"
        original_etag = bucket.seed(key, "# Une idée\n\nÉcrite à la main.\n")

        report = await world.reconciler.reconcile()

        row = world.row_at(key)
        assert report.stamped == 1 and row.title == "Idée" and "Écrite à la main." in row.body
        written = parse_note(bucket.objects[key].body.decode(), "x")
        assert written.id == row.id and "Écrite à la main." in written.body
        assert bucket.puts == [(key, original_etag, False)]
        assert row.etag == bucket.objects[key].etag
        assert world.events(kind="id_stamped")

        gets = bucket.count("get")
        await world.reconciler.reconcile()
        assert bucket.count("get") == gets, "the stamped version must not be read back"

    async def test_an_owner_edit_between_the_read_and_the_stamp_is_never_overwritten(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        key = f"{world.user}/hand.md"
        bucket.seed(key, "first draft")
        bucket.after_get = lambda _key: bucket.seed(key, "the owner kept typing")

        report = await world.reconciler.reconcile()

        assert bucket.objects[key].body == b"the owner kept typing"
        assert world.rows() == [] and report.stamped == 0

        bucket.after_get = None
        await world.reconciler.reconcile()
        assert world.row_at(key).body == "the owner kept typing"

    async def test_stamping_is_retried_when_the_bucket_refuses_the_write(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        key = f"{world.user}/hand.md"
        bucket.seed(key, "text")
        bucket.fail_next_puts_with_412 = 1

        await world.reconciler.reconcile()
        assert world.rows() == []

        await world.reconciler.reconcile()
        assert world.row_at(key).body == "text"

    async def test_an_owner_who_strips_the_header_keeps_the_note_identity(self, world):
        seeded = world.seed(f"{world.user}/a.md", title="A", body="body")
        await world.reconciler.reconcile()

        world.bucket.seed(seeded.key, "just text now")
        await world.reconciler.reconcile()

        rows = world.rows()
        assert len(rows) == 1 and rows[0].id == seeded.id and rows[0].body == "just text now"
        assert parse_note(world.bucket.objects[seeded.key].body.decode(), "a").id == seeded.id


class TestUnreadable:
    BROKEN = "---\ntitle: [unclosed\n---\nbody"

    async def test_a_broken_file_is_flagged_not_dropped_and_not_hidden(self, world):
        key = f"{world.user}/broken.md"
        world.bucket.seed(key, self.BROKEN)

        report = await world.reconciler.reconcile()

        row = world.row_at(key)
        assert row.unreadable is True and "YAML" in str(row.unreadable_reason) and row.deleted_at is None
        assert report.unreadable == 1
        assert world.events(kind="unreadable") and world.publisher.kinds() == ["unreadable"]
        text = await world.summary.render(world.user)
        assert "Fichiers illisibles" in text and "broken" in text
        assert await world.repo.notes_for_prompt(world.user, 10) == []

    async def test_a_note_that_becomes_unreadable_keeps_its_row_and_content(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="still here")
        await world.reconciler.reconcile()

        world.bucket.seed(seeded.key, self.BROKEN)
        await world.reconciler.reconcile()

        row = world.row(seeded.id)
        assert row.unreadable is True and row.body == "still here" and row.deleted_at is None

        gets = world.bucket.count("get")
        await world.reconciler.reconcile()
        assert world.bucket.count("get") == gets

    async def test_a_file_that_is_not_utf8_is_unreadable(self, world):
        key = f"{world.user}/bin.md"
        world.bucket.seed(key, b"\xff\xfe\x00bad")

        await world.reconciler.reconcile()

        assert "UTF-8" in str(world.row_at(key).unreadable_reason)

    async def test_fixing_the_file_clears_the_flag(self, world):
        key = f"{world.user}/broken.md"
        world.bucket.seed(key, self.BROKEN)
        await world.reconciler.reconcile()

        world.seed(key, title="Fixed", body="ok")
        await world.reconciler.reconcile()

        rows = world.rows()
        assert len(rows) == 1 and rows[0].unreadable is False and rows[0].title == "Fixed"

    async def test_an_unreadable_file_that_is_removed_is_soft_deleted(self, world):
        key = f"{world.user}/broken.md"
        world.bucket.seed(key, self.BROKEN)
        await world.reconciler.reconcile()

        world.bucket.remove(key)
        await world.reconciler.reconcile()

        assert world.rows()[0].deleted_at is not None


class TestDeletion:
    async def test_a_removed_file_is_soft_deleted_never_hard_deleted(self, world):
        seeded = world.seed(f"{world.user}/a.md")
        world.seed(f"{world.user}/b.md")
        await world.reconciler.reconcile()
        world.publisher.clear()

        world.bucket.remove(seeded.key)
        report = await world.reconciler.reconcile()

        row = world.row(seeded.id)
        assert row.deleted_at is not None and report.soft_deleted == 1
        assert len(world.rows(live_only=True)) == 1
        assert world.events(kind="deleted") and world.publisher.kinds() == ["deleted"]
        assert await world.repo.notes_for_prompt(world.user, 10) != [row]

    async def test_a_deleted_file_that_comes_back_is_restored(self, world):
        seeded = world.seed(f"{world.user}/a.md")
        world.seed(f"{world.user}/b.md")
        await world.reconciler.reconcile()
        world.bucket.remove(seeded.key)
        await world.reconciler.reconcile()

        world.seed(seeded.key, note_id=seeded.id)
        await world.reconciler.reconcile()

        assert world.row(seeded.id).deleted_at is None
        assert world.events(kind="restored")

    async def test_losing_more_than_half_at_once_is_treated_as_a_broken_listing(self, world, caplog):
        seeded = world.seed_many(world.user, 10)
        await world.reconciler.reconcile()

        for item in seeded[:6]:
            world.bucket.remove(item.key)
        with caplog.at_level(logging.CRITICAL):
            report = await world.reconciler.reconcile()

        assert report.blocked_deletions == {world.user: 6} and report.soft_deleted == 0
        assert len(world.rows(live_only=True)) == 10
        event = world.events(kind="mass_deletion_blocked")[0]
        assert event.detail == {"missing": 6, "of": 10}
        assert any(r.levelno == logging.CRITICAL for r in caplog.records)

    @pytest.mark.parametrize(("total", "removed", "deleted"), [(10, 5, 5), (10, 4, 4), (6, 4, 4), (5, 5, 0), (4, 4, 4)])
    async def test_the_guard_thresholds(self, world, total, removed, deleted):
        seeded = world.seed_many(world.user, total)
        await world.reconciler.reconcile()

        for item in seeded[:removed]:
            world.bucket.remove(item.key)
        await world.reconciler.reconcile()

        assert len(world.rows(live_only=True)) == total - deleted
        assert len(world.rows()) == total

    async def test_the_guard_is_per_user(self, world):
        mine = world.seed_many(world.user, 10)
        theirs = world.seed_many(world.other_user, 3)
        await world.reconciler.reconcile()

        for item in mine[:6] + theirs[:1]:
            world.bucket.remove(item.key)
        report = await world.reconciler.reconcile()

        assert report.blocked_deletions == {world.user: 6}
        assert len(world.rows(world.user, live_only=True)) == 10
        assert len(world.rows(world.other_user, live_only=True)) == 2

    async def test_a_listing_that_fails_on_page_two_deletes_nothing(self, make_world):
        bucket = FakeBucket(page_size=2)
        world = make_world(bucket)
        seeded = world.seed_many(world.user, 6)
        await world.reconciler.reconcile()

        bucket.remove(seeded[0].key)
        bucket.fail_listing_after_pages = 1
        with pytest.raises(BucketUnavailable):
            await world.reconciler.reconcile()

        assert len(world.rows(live_only=True)) == 6
        assert not world.events(kind="deleted")

        bucket.fail_listing_after_pages = None
        await world.reconciler.reconcile()
        assert len(world.rows(live_only=True)) == 5

    async def test_an_outage_during_the_pass_deletes_nothing(self, world):
        seeded = world.seed_many(world.user, 3)
        await world.reconciler.reconcile()

        world.bucket.remove(seeded[0].key)
        world.bucket.down = True
        with pytest.raises(BucketUnavailable):
            await world.reconciler.reconcile()

        assert len(world.rows(live_only=True)) == 3

    async def test_a_file_that_vanishes_between_the_listing_and_the_read_skips_the_deletions(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        gone = world.seed(f"{world.user}/gone.md")
        await world.reconciler.reconcile()
        world.bucket.remove(gone.key)
        fresh = world.seed(f"{world.user}/fresh.md")
        original_get = bucket.get

        async def vanishing_get(key: str):
            if key == fresh.key:
                bucket.remove(key)
                raise ObjectNotFound(key)
            return await original_get(key)

        bucket.get = vanishing_get  # type: ignore[method-assign]
        report = await world.reconciler.reconcile()

        assert report.incomplete is True
        assert world.row(gone.id).deleted_at is None

        bucket.get = original_get  # type: ignore[method-assign]
        await world.reconciler.reconcile()
        assert world.row(gone.id).deleted_at is not None


class TestScope:
    async def test_only_notes_and_archived_notes_are_read(self, world):
        user = world.user
        world.bucket.seed(f"{user}/SOMMAIRE.md", "# generated")
        world.bucket.seed(f"{user}/conflits/a-20260101T000000000000Z.md", "conflict copy")
        world.bucket.seed("competences/skill.md", "a skill")
        world.bucket.seed(f"{user}/archive/deeper/x.md", "too deep")
        world.bucket.seed(f"{user}/readme.txt", "not markdown")
        world.bucket.seed("stray.md", "no user prefix")
        world.bucket.seed("not-a-user/a.md", "unknown prefix")

        report = await world.reconciler.reconcile()

        assert world.rows() == []
        assert world.bucket.count("get") == 0
        assert report.listed == 7 and report.fetched == 0

    async def test_the_generated_summary_never_comes_back_as_a_note(self, world):
        seeded = world.seed(f"{world.user}/a.md", title="A")
        await world.reconciler.reconcile()
        await world.summary.refresh(world.user)
        assert f"{world.user}/SOMMAIRE.md" in world.bucket.objects

        await world.reconciler.reconcile()

        assert [r.id for r in world.rows()] == [seeded.id]

    async def test_two_users_do_not_see_each_other(self, world):
        mine = world.seed(f"{world.user}/same.md", title="Mine")
        theirs = world.seed(f"{world.other_user}/same.md", title="Theirs")

        await world.reconciler.reconcile()

        assert [r.id for r in world.rows(world.user)] == [mine.id]
        assert [r.id for r in world.rows(world.other_user)] == [theirs.id]
        assert {e.user_id for e in world.events()} == {world.user, world.other_user}
        topics = {t for t, _ in world.publisher.published}
        assert topics == {f"/memory/{world.user}", f"/memory/{world.other_user}"}

        world.bucket.remove(mine.key)
        await world.reconciler.reconcile()
        assert world.row(mine.id).deleted_at is not None
        assert world.row(theirs.id).deleted_at is None


class TestOwnWrites:
    async def test_the_write_doors_own_put_is_not_read_back(self, world):
        result = await world.store.save(world.user, NoteDoc(title="Mine", body="written by Maggie"))
        world.publisher.clear()
        events_before = len(world.events())

        report = await world.reconciler.reconcile()

        assert world.bucket.count("get") == 0
        assert report.fetched == 0
        assert world.publisher.published == []
        assert len(world.events()) == events_before
        assert world.row(result.note_id).etag == result.etag

    async def test_a_pending_sync_row_is_neither_deleted_nor_reverted(self, world):
        world.bucket.down = True
        result = await world.store.save(world.user, NoteDoc(title="Queued", body="only in the index"))
        assert result.status == "pending"
        world.bucket.down = False

        report = await world.reconciler.reconcile()

        row = world.row(result.note_id)
        assert row.deleted_at is None and row.pending_sync is True and row.body == "only in the index"
        assert report.soft_deleted == 0

    async def test_a_pending_sync_note_is_not_reverted_by_the_old_file(self, world):
        seeded = world.seed(f"{world.user}/a.md", body="v1")
        await world.reconciler.reconcile()
        world.bucket.down = True
        result = await world.store.save(world.user, NoteDoc(id=seeded.id, title="Titre", body="v2 queued"))
        world.bucket.down = False
        assert result.status == "pending"

        await world.reconciler.reconcile()

        row = world.row(seeded.id)
        assert row.body == "v2 queued" and row.pending_sync is True

    async def test_quiet_mode_writes_no_event_and_publishes_nothing(self, world):
        world.seed(f"{world.user}/a.md")

        report = await world.reconciler.reconcile(quiet=True)

        assert len(world.rows()) == 1 and world.events() == [] and world.publisher.published == []
        assert report.changed_users == {world.user}



class TestChangedDuringThePass:
    async def test_a_write_recorded_between_the_fetch_and_the_apply_is_not_reverted(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        key = f"{world.user}/a.md"
        seeded = world.seed(key, title="A", body="v0")
        await world.reconciler.reconcile()
        bucket.seed(key, render_note(NoteDoc(id=seeded.id, title="A", body="the owner's edit")))

        def maggie_writes(_key: str) -> None:
            etag = bucket.seed(key, render_note(NoteDoc(id=seeded.id, title="A", body="Maggie's version")))
            world.update_row(seeded.id, etag=etag, body="Maggie's version")

        bucket.after_get = maggie_writes
        await world.reconciler.reconcile()
        bucket.after_get = None

        row = world.row(seeded.id)
        assert row.body == "Maggie's version" and row.etag == bucket.objects[key].etag

        result = await world.store.save(world.user, NoteDoc(id=seeded.id, title="A", body="next"))

        assert result.status == "written"
        assert not [k for k in bucket.objects if "/conflits/" in k]

    async def test_a_row_that_moved_on_by_id_is_left_alone_too(self, make_world):
        bucket = RacyBucket()
        world = make_world(bucket)
        old, new = f"{world.user}/old.md", f"{world.user}/new.md"
        seeded = world.seed(old, title="A", body="v0")
        await world.reconciler.reconcile()
        bucket.remove(old)
        bucket.seed(new, render_note(NoteDoc(id=seeded.id, title="A", body="moved by the owner")))

        def maggie_writes(_key: str) -> None:
            etag = bucket.seed(old, render_note(NoteDoc(id=seeded.id, title="A", body="Maggie's version")))
            world.update_row(seeded.id, etag=etag, body="Maggie's version", path=old)

        bucket.after_get = maggie_writes
        await world.reconciler.reconcile()
        bucket.after_get = None

        row = world.row(seeded.id)
        assert (row.path, row.body) == (old, "Maggie's version")


class TestOneBadFile:
    async def test_a_file_whose_apply_raises_does_not_stall_the_pass(self, world, caplog):
        gone = world.seed(f"{world.user}/gone.md", title="Gone")
        await world.reconciler.reconcile()
        world.bucket.remove(gone.key)
        bad = world.seed(f"{world.user}/bad.md", title="Bad")
        good = world.seed(f"{world.user}/good.md", title="Good")
        real = world.reconciler._apply

        async def flaky(item, *args, **kwargs):
            if item.owned.key == bad.key:
                raise RuntimeError("database said no")
            return await real(item, *args, **kwargs)

        world.reconciler._apply = flaky  # type: ignore[method-assign]
        with caplog.at_level(logging.ERROR):
            report = await world.reconciler.reconcile()

        assert world.row(good.id).title == "Good"
        assert report.errors == 1 and report.incomplete is True and report.soft_deleted == 0
        assert world.row(gone.id).deleted_at is None, "deletions wait for a pass without errors"
        assert any("bad.md" in r.getMessage() for r in caplog.records)

        world.reconciler._apply = real  # type: ignore[method-assign]
        report = await world.reconciler.reconcile()
        assert report.errors == 0 and world.row(bad.id).title == "Bad" and world.row(gone.id).deleted_at is not None

    async def test_a_body_with_a_nul_byte_is_unreadable_and_never_reaches_the_insert(self, world):
        world.bucket.seed(f"{world.user}/nul.md", "# Title\n\nbefore\x00after")
        good = world.seed(f"{world.user}/good.md", title="Good")

        report = await world.reconciler.reconcile()

        assert report.errors == 0 and report.unreadable == 1 and report.incomplete is False
        assert world.row(good.id).title == "Good"
        nul = world.row_at(f"{world.user}/nul.md")
        assert nul.unreadable is True and "NUL" in nul.unreadable_reason

    async def test_a_key_longer_than_the_path_column_is_skipped_and_counted(self, world):
        long_key = f"{world.user}/{'x' * 600}.md"
        world.bucket.seed(long_key, "# Long\n\nbody")
        good = world.seed(f"{world.user}/good.md", title="Good")

        report = await world.reconciler.reconcile()

        assert report.errors == 0 and report.unreadable == 1
        assert world.row(good.id).title == "Good"
        assert [r.path for r in world.rows()] == [good.key]

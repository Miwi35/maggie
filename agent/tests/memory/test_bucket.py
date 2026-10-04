from datetime import UTC, datetime

import pytest

from app.memory.bucket import (
    BucketUnavailable,
    FakeBucket,
    ObjectNotFound,
    PreconditionFailed,
    clean_etag,
)

AT = datetime(2026, 1, 1, tzinfo=UTC)


def test_clean_etag_drops_quotes_and_weak_marker():
    assert clean_etag('"abc"') == "abc"
    assert clean_etag('W/"abc"') == "abc"
    assert clean_etag(None) == ""


class TestRoundTrip:
    async def test_put_get_head_delete(self):
        bucket = FakeBucket(clock=lambda: AT)

        etag = await bucket.put("u/a.md", b"hello")

        got = await bucket.get("u/a.md")
        assert got.body == b"hello" and got.etag == etag and got.last_modified == AT
        info = await bucket.head("u/a.md")
        assert info is not None and info.etag == etag and info.size == 5
        await bucket.delete("u/a.md")
        assert await bucket.head("u/a.md") is None
        with pytest.raises(ObjectNotFound):
            await bucket.get("u/a.md")

    async def test_the_etag_follows_the_content(self):
        bucket = FakeBucket()

        first = await bucket.put("k", b"one")
        second = await bucket.put("k", b"two")

        assert first != second


class TestListing:
    async def test_pagination_returns_everything_sorted(self):
        bucket = FakeBucket(page_size=3)
        for i in range(10):
            bucket.seed(f"u/{i:02}.md", f"b{i}")
        bucket.seed("other/x.md", "x")

        listed = await bucket.list_objects("u/")

        assert [o.key for o in listed] == [f"u/{i:02}.md" for i in range(10)]

    async def test_a_listing_that_dies_on_page_two_returns_nothing(self):
        bucket = FakeBucket(page_size=3)
        for i in range(10):
            bucket.seed(f"u/{i:02}.md", "x")
        bucket.fail_listing_after_pages = 1

        with pytest.raises(BucketUnavailable):
            await bucket.list_objects("")

    async def test_a_listing_that_fits_in_the_allowed_pages_succeeds(self):
        bucket = FakeBucket(page_size=5)
        for i in range(5):
            bucket.seed(f"u/{i}.md", "x")
        bucket.fail_listing_after_pages = 1

        assert len(await bucket.list_objects("")) == 5


class TestConditions:
    async def test_if_none_match_refuses_an_existing_key(self):
        bucket = FakeBucket()
        bucket.seed("k", "x")

        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"y", if_none_match=True)
        await bucket.put("new", b"y", if_none_match=True)

    async def test_if_match_needs_the_current_etag(self):
        bucket = FakeBucket()
        etag = bucket.seed("k", "x")

        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"y", if_match="stale")
        with pytest.raises(PreconditionFailed):
            await bucket.put("absent", b"y", if_match=etag)
        await bucket.put("k", b"y", if_match=f'"{etag}"')
        assert (await bucket.get("k")).body == b"y"

    async def test_injected_412s_are_consumed_one_per_put(self):
        bucket = FakeBucket()
        bucket.fail_next_puts_with_412 = 2

        for _ in range(2):
            with pytest.raises(PreconditionFailed):
                await bucket.put("k", b"x")
        await bucket.put("k", b"x")
        assert bucket.fail_next_puts_with_412 == 0


class TestOutage:
    async def test_every_operation_fails_while_down_and_nothing_is_written(self):
        bucket = FakeBucket()
        bucket.seed("k", "x")
        bucket.down = True

        for call in (
            bucket.list_objects(),
            bucket.head("k"),
            bucket.get("k"),
            bucket.put("k", b"y"),
            bucket.delete("k"),
            bucket.versioning_status(),
        ):
            with pytest.raises(BucketUnavailable):
                await call
        assert bucket.objects["k"].body == b"x"

        bucket.down = False
        assert (await bucket.get("k")).body == b"x"

    async def test_calls_are_counted(self):
        bucket = FakeBucket()
        await bucket.list_objects()
        await bucket.head("x")

        assert bucket.count("list") == 1 and bucket.count("head") == 1 and bucket.count("get") == 0


class TestVersions:
    async def test_every_version_is_kept_even_after_a_delete(self):
        bucket = FakeBucket()
        await bucket.put("k", b"1")
        await bucket.put("k", b"2")
        bucket.seed("k", "3")
        await bucket.delete("k")

        assert [v.body for v in bucket.versions["k"]] == [b"1", b"2", b"3"]
        assert "k" not in bucket.objects

    async def test_versioning_is_reported_enabled(self):
        assert await FakeBucket().versioning_status() == "Enabled"

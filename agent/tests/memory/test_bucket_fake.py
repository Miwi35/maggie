import pytest

from app.memory.bucket import BucketUnavailable, FakeBucket, ObjectNotFound, PreconditionFailed, clean_etag
from app.memory.service import build_bucket


async def test_put_get_head_delete():
    bucket = FakeBucket()
    etag = await bucket.put("u/Mémoire/a.md", b"un")
    assert (await bucket.get("u/Mémoire/a.md")).body == b"un"
    assert (await bucket.head("u/Mémoire/a.md")).etag == etag
    await bucket.delete("u/Mémoire/a.md")
    assert await bucket.head("u/Mémoire/a.md") is None
    with pytest.raises(ObjectNotFound):
        await bucket.get("u/Mémoire/a.md")


async def test_conditions_are_enforced():
    bucket = FakeBucket()
    first = await bucket.put("k", b"1", if_none_match=True)
    with pytest.raises(PreconditionFailed):
        await bucket.put("k", b"2", if_none_match=True)
    with pytest.raises(PreconditionFailed):
        await bucket.put("k", b"2", if_match="mauvais")
    second = await bucket.put("k", b"2", if_match=f'"{first}"')
    assert second != first
    with pytest.raises(PreconditionFailed):
        await bucket.put("absent", b"x", if_match=first)


async def test_every_version_is_kept():
    bucket = FakeBucket()
    await bucket.put("k", b"1")
    await bucket.put("k", b"2")
    assert [v.body for v in bucket.versions["k"]] == [b"1", b"2"]


async def test_an_outage_fails_every_operation_until_it_ends():
    bucket = FakeBucket()
    bucket.down = True
    for call in (bucket.list_objects("u/"), bucket.list_prefixes(), bucket.head("k"), bucket.get("k"), bucket.put("k", b"x"), bucket.delete("k")):
        with pytest.raises(BucketUnavailable):
            await call
    bucket.down = False
    await bucket.put("k", b"x")


async def test_injected_412_then_it_works():
    bucket = FakeBucket()
    bucket.fail_next_puts_with_412 = 2
    for _ in range(2):
        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"x")
    await bucket.put("k", b"x")


async def test_listing_is_paged_and_can_die_half_way():
    bucket = FakeBucket(page_size=2)
    for index in range(5):
        bucket.seed(f"u/Mémoire/{index}.md", "x")
    assert len(await bucket.list_objects("u/Mémoire/")) == 5
    bucket.fail_listing_after_pages = 1
    with pytest.raises(BucketUnavailable):
        await bucket.list_objects("u/Mémoire/")


async def test_prefixes_are_the_first_segment():
    bucket = FakeBucket()
    bucket.seed("a/Mémoire/x.md", "x")
    bucket.seed("b/Mémoire/y.md", "y")
    assert await bucket.list_prefixes() == ["a", "b"]


def test_etag_quotes_are_stripped():
    assert clean_etag('"abc"') == "abc"


def test_provider_switch(monkeypatch):
    monkeypatch.setattr("app.memory.service.settings.memory_provider", "fake")
    assert isinstance(build_bucket(), FakeBucket)
    monkeypatch.setattr("app.memory.service.settings.memory_provider", "s3")
    monkeypatch.setattr("app.memory.service.settings.memory_bucket", "")
    assert build_bucket() is None

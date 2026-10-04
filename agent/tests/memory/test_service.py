from datetime import UTC, datetime
from typing import Any

import pytest
from botocore.exceptions import ClientError, ConnectTimeoutError, EndpointConnectionError

from app.config import settings
from app.memory import service as service_module
from app.memory.bucket import (
    BucketUnavailable,
    FakeBucket,
    ObjectNotFound,
    PreconditionFailed,
    S3Bucket,
)
from app.memory.service import build_bucket, build_service, configure, get_service

AT = datetime(2026, 1, 1, tzinfo=UTC)


def error(code: str, status: int, operation: str = "PutObject") -> ClientError:
    return ClientError({"Error": {"Code": code}, "ResponseMetadata": {"HTTPStatusCode": status}}, operation)


class _Body:
    def __init__(self, data: bytes) -> None:
        self.data = data

    async def __aenter__(self) -> "_Body":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    async def read(self) -> bytes:
        return self.data


class StubS3:
    """What aioboto3's client does for the calls the bucket makes — no network, no aioboto3."""

    def __init__(self) -> None:
        self.objects: dict[str, tuple[bytes, str]] = {}
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.put_errors: list[Exception] = []
        self.pages: list[dict[str, Any]] = []

    async def __aenter__(self) -> "StubS3":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    async def head_object(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("head", kwargs))
        if kwargs["Key"] not in self.objects:
            raise error("404", 404, "HeadObject")
        body, etag = self.objects[kwargs["Key"]]
        return {"ETag": f'"{etag}"', "LastModified": AT, "ContentLength": len(body)}

    async def put_object(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("put", kwargs))
        if self.put_errors:
            raise self.put_errors.pop(0)
        etag = f"etag{len(self.calls)}"
        self.objects[kwargs["Key"]] = (kwargs["Body"], etag)
        return {"ETag": f'"{etag}"'}

    async def get_object(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("get", kwargs))
        if kwargs["Key"] not in self.objects:
            raise error("NoSuchKey", 404, "GetObject")
        body, etag = self.objects[kwargs["Key"]]
        return {"Body": _Body(body), "ETag": f'"{etag}"', "LastModified": AT}

    async def delete_object(self, **kwargs: Any) -> None:
        self.calls.append(("delete", kwargs))
        self.objects.pop(kwargs["Key"], None)

    async def list_objects_v2(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(("list", kwargs))
        return self.pages.pop(0)

    async def get_bucket_versioning(self, **kwargs: Any) -> dict[str, Any]:
        return {"Status": "Enabled"}


@pytest.fixture()
def s3(monkeypatch):
    stub = StubS3()
    bucket = S3Bucket("maggie", "https://s3.example", "gra", "key", "secret", timeout_seconds=1)
    monkeypatch.setattr(bucket, "_client", lambda: stub)
    return bucket, stub


class TestBuildBucket:
    def test_nothing_configured_means_no_bucket(self):
        assert build_bucket(settings.model_copy(update={"memory_bucket": ""})) is None

    def test_fake_is_the_in_memory_bucket_on_the_e2e_stack(self):
        config = settings.model_copy(update={"memory_bucket": "fake", "tts_provider": "fake"})
        assert isinstance(build_bucket(config), FakeBucket)

    def test_fake_is_refused_outside_the_e2e_stack(self, caplog):
        config = settings.model_copy(update={"memory_bucket": "fake", "tts_provider": "edge"})
        with caplog.at_level("ERROR"):
            assert build_bucket(config) is None
        assert any("fake" in r.getMessage() for r in caplog.records)

    def test_anything_else_is_s3_and_builds_without_touching_the_network(self):
        config = settings.model_copy(
            update={
                "memory_bucket": "maggie-notes",
                "memory_bucket_endpoint": "https://s3.gra.io.cloud.ovh.net",
                "memory_bucket_region": "gra",
                "memory_bucket_key": "k",
                "memory_bucket_secret": "s",
                "memory_bucket_timeout_seconds": 3.5,
                "memory_bucket_conditional_writes": False,
            }
        )

        bucket = build_bucket(config)

        assert isinstance(bucket, S3Bucket)
        assert (bucket.bucket, bucket.endpoint_url, bucket.region) == ("maggie-notes", config.memory_bucket_endpoint, "gra")
        assert bucket.timeout_seconds == 3.5 and bucket.conditional_writes is False


class TestConfigure:
    @pytest.fixture(autouse=True)
    def restore(self, monkeypatch):
        monkeypatch.setattr(service_module, "memory_service", None)

    def test_without_a_bucket_there_is_no_service(self):
        assert configure(settings.model_copy(update={"memory_bucket": ""})) is None
        assert get_service() is None

    def test_with_the_fake_bucket_the_service_is_wired(self):
        config = settings.model_copy(
            update={
                "memory_bucket": "fake",
                "tts_provider": "fake",
                "memory_sync_interval_seconds": 7,
                "memory_turn_sync_max_age_seconds": 11,
                "memory_turn_sync_timeout_seconds": 1.5,
            }
        )

        service = configure(config)

        assert service is not None and get_service() is service
        assert isinstance(service.bucket, FakeBucket)
        assert service.store.bucket is service.bucket and service.reconciler.bucket is service.bucket
        assert service.store.locks is service.reconciler.locks, "one lock per path across the write door and the reconciler"
        assert service.sync.interval_seconds == 7
        assert service.sync.turn_max_age.total_seconds() == 11
        assert service.sync.turn_timeout_seconds == 1.5 and service.store.timeout_seconds == 1.5

    def test_build_service_shares_the_summary_writer(self):
        service = build_service(FakeBucket())

        assert service.store.summary is service.summary and service.sync.summary is service.summary


class TestErrorTranslation:
    @pytest.mark.parametrize(
        ("raised", "expected"),
        [
            (error("NoSuchKey", 404), ObjectNotFound),
            (error("404", 0), ObjectNotFound),
            (error("Whatever", 404), ObjectNotFound),
            (error("PreconditionFailed", 412), PreconditionFailed),
            (error("ConditionalRequestConflict", 409), PreconditionFailed),
            (error("InternalError", 500), BucketUnavailable),
            (error("SlowDown", 503), BucketUnavailable),
            (error("AccessDenied", 403), BucketUnavailable),
            (EndpointConnectionError(endpoint_url="https://x"), BucketUnavailable),
            (ConnectTimeoutError(endpoint_url="https://x"), BucketUnavailable),
            (TimeoutError("slow"), BucketUnavailable),
            (OSError("reset"), BucketUnavailable),
            (RuntimeError("?"), BucketUnavailable),
        ],
    )
    def test_each_failure_lands_in_one_of_the_three_outcomes(self, raised, expected):
        assert isinstance(S3Bucket._translate(raised), expected)

    async def test_a_timeout_inside_a_call_is_an_outage(self, s3):
        bucket, stub = s3

        async def hang(**kwargs):
            raise TimeoutError()

        stub.get_object = hang  # type: ignore[method-assign]

        with pytest.raises(BucketUnavailable):
            await bucket.get("k")


class TestS3Calls:
    async def test_conditions_are_sent_as_headers_with_quoted_etags(self, s3):
        bucket, stub = s3

        await bucket.put("u/a.md", b"x", if_match="abc")
        await bucket.put("u/b.md", b"x", if_none_match=True)
        await bucket.put("u/c.md", b"x")

        (_, with_match), (_, with_none), (_, plain) = stub.calls
        assert with_match["IfMatch"] == '"abc"' and "IfNoneMatch" not in with_match
        assert with_none["IfNoneMatch"] == "*" and "IfMatch" not in with_none
        assert "IfMatch" not in plain and "IfNoneMatch" not in plain
        assert plain["ContentType"].startswith("text/markdown")

    async def test_a_412_is_a_lost_race(self, s3):
        bucket, stub = s3
        stub.put_errors.append(error("PreconditionFailed", 412))

        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"x", if_match="abc")

    async def test_get_head_delete_and_missing_keys(self, s3):
        bucket, stub = s3
        etag = await bucket.put("k", b"hello")

        got = await bucket.get("k")
        info = await bucket.head("k")
        assert got.body == b"hello" and got.etag == etag and info is not None and info.size == 5
        assert await bucket.head("absent") is None
        with pytest.raises(ObjectNotFound):
            await bucket.get("absent")
        await bucket.delete("k")
        assert "k" not in stub.objects

    async def test_a_listing_follows_the_continuation_token_through_every_page(self, s3):
        bucket, stub = s3
        stub.pages = [
            {
                "Contents": [{"Key": "u/a.md", "ETag": '"1"', "LastModified": AT, "Size": 3}],
                "IsTruncated": True,
                "NextContinuationToken": "t1",
            },
            {"Contents": [{"Key": "u/b.md", "ETag": '"2"', "LastModified": AT, "Size": 4}], "IsTruncated": False},
        ]

        listed = await bucket.list_objects("u/")

        assert [(o.key, o.etag, o.size) for o in listed] == [("u/a.md", "1", 3), ("u/b.md", "2", 4)]
        assert [c[1].get("ContinuationToken") for c in stub.calls] == [None, "t1"]

    async def test_a_listing_that_fails_on_a_later_page_returns_nothing(self, s3):
        bucket, stub = s3
        stub.pages = [
            {"Contents": [{"Key": "u/a.md", "LastModified": AT}], "IsTruncated": True, "NextContinuationToken": "t"},
        ]

        with pytest.raises(BucketUnavailable):
            await bucket.list_objects()

    async def test_an_empty_bucket_lists_empty(self, s3):
        bucket, stub = s3
        stub.pages = [{"IsTruncated": False}]

        assert await bucket.list_objects() == []

    async def test_versioning_status(self, s3):
        bucket, _ = s3

        assert await bucket.versioning_status() == "Enabled"


class TestConditionalWriteFallback:
    async def test_without_conditional_writes_the_etag_is_compared_by_hand(self, s3):
        bucket, stub = s3
        bucket.conditional_writes = False
        stub.objects["k"] = (b"old", "e1")

        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"new", if_match="stale")
        assert stub.objects["k"] == (b"old", "e1")

        await bucket.put("k", b"new", if_match="e1")
        assert stub.objects["k"][0] == b"new"
        puts = [c[1] for c in stub.calls if c[0] == "put"]
        assert len(puts) == 1 and "IfMatch" not in puts[0] and "IfNoneMatch" not in puts[0]

    async def test_check_precondition_decisions(self, s3):
        bucket, stub = s3
        stub.objects["here"] = (b"x", "e1")

        await bucket._check_precondition("here", "e1", False)
        await bucket._check_precondition("here", '"e1"', False)
        await bucket._check_precondition("absent", None, True)
        await bucket._check_precondition("absent", None, False)
        with pytest.raises(PreconditionFailed):
            await bucket._check_precondition("here", None, True)
        with pytest.raises(PreconditionFailed):
            await bucket._check_precondition("here", "e2", False)
        with pytest.raises(PreconditionFailed):
            await bucket._check_precondition("absent", "e1", False)

    async def test_an_endpoint_that_refuses_the_headers_switches_to_the_fallback_for_good(self, s3, caplog):
        bucket, stub = s3
        stub.put_errors.append(error("NotImplemented", 501))

        await bucket.put("k", b"first", if_none_match=True)

        assert bucket.conditional_writes is False
        sent = [c[1] for c in stub.calls if c[0] == "put"]
        assert sent[0].get("IfNoneMatch") == "*" and "IfNoneMatch" not in sent[1]
        assert stub.objects["k"][0] == b"first"

        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"again", if_none_match=True)

    async def test_an_endpoint_without_support_still_refuses_a_lost_race(self, s3):
        bucket, stub = s3
        stub.objects["k"] = (b"owner", "e9")
        stub.put_errors.append(error("InvalidArgument", 400))

        with pytest.raises(PreconditionFailed):
            await bucket.put("k", b"mine", if_match="e1")

        assert stub.objects["k"] == (b"owner", "e9")

    async def test_a_plain_outage_is_not_mistaken_for_missing_support(self, s3):
        bucket, stub = s3
        stub.put_errors.append(error("InternalError", 500))

        with pytest.raises(BucketUnavailable):
            await bucket.put("k", b"x", if_match="e1")

        assert bucket.conditional_writes is True

    async def test_an_unconditional_write_never_triggers_the_fallback(self, s3):
        bucket, stub = s3
        stub.put_errors.append(error("NotImplemented", 501))

        with pytest.raises(BucketUnavailable):
            await bucket.put("k", b"x")

        assert bucket.conditional_writes is True

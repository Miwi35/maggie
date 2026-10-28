from datetime import UTC, datetime

import pytest
from botocore.exceptions import ClientError, ConnectTimeoutError

from app.memory.bucket import BucketUnavailable, ObjectNotFound, PreconditionFailed, S3Bucket

NOW = datetime(2026, 10, 13, tzinfo=UTC)


def client_error(code: str, status: int) -> ClientError:
    return ClientError({"Error": {"Code": code}, "ResponseMetadata": {"HTTPStatusCode": status}}, "Op")


class Body:
    def __init__(self, data: bytes) -> None:
        self.data = data

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def read(self) -> bytes:
        return self.data


class StubS3:
    """The few calls of the S3 protocol the bucket makes, recorded."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        self.objects: dict[str, bytes] = {}
        self.fail: Exception | None = None
        self.pages: list[dict] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def _enter(self, name: str, kwargs: dict) -> None:
        self.calls.append((name, kwargs))
        if self.fail:
            raise self.fail

    async def head_object(self, **kwargs):
        self._enter("head", kwargs)
        if kwargs["Key"] not in self.objects:
            raise client_error("404", 404)
        return {"ETag": '"e1"', "ContentLength": 3, "LastModified": NOW}

    async def get_object(self, **kwargs):
        self._enter("get", kwargs)
        if kwargs["Key"] not in self.objects:
            raise client_error("NoSuchKey", 404)
        return {"ETag": '"e1"', "Body": Body(self.objects[kwargs["Key"]]), "LastModified": NOW}

    async def put_object(self, **kwargs):
        self._enter("put", kwargs)
        self.objects[kwargs["Key"]] = kwargs["Body"]
        return {"ETag": '"e2"'}

    async def delete_object(self, **kwargs):
        self._enter("delete", kwargs)

    async def list_objects_v2(self, **kwargs):
        self._enter("list", kwargs)
        return self.pages.pop(0)

    async def get_bucket_versioning(self, **kwargs):
        self._enter("versioning", kwargs)
        return {"Status": "Enabled"}


@pytest.fixture()
def s3(monkeypatch):
    stub = StubS3()
    monkeypatch.setattr(S3Bucket, "_client", lambda self: stub)
    return stub


def bucket(conditional: bool = False) -> S3Bucket:
    return S3Bucket("b", "http://stockage.local", "r", "id", "secret", timeout_seconds=1, conditional_writes=conditional)


async def test_get_and_missing_key(s3):
    s3.objects["k"] = b"texte"
    data = await bucket().get("k")
    assert (data.body, data.etag) == (b"texte", "e1")
    with pytest.raises(ObjectNotFound):
        await bucket().get("absent")
    assert await bucket().head("absent") is None


async def test_without_conditional_writes_the_etag_is_compared_before_the_put(s3):
    s3.objects["k"] = b"v1"
    with pytest.raises(PreconditionFailed):
        await bucket().put("k", b"v2", if_match="autre")
    with pytest.raises(PreconditionFailed):
        await bucket().put("k", b"v2", if_none_match=True)
    assert not [c for c in s3.calls if c[0] == "put"]

    assert await bucket().put("k", b"v2", if_match='"e1"') == "e2"
    [(_, sent)] = [c for c in s3.calls if c[0] == "put"]
    assert "IfMatch" not in sent and sent["Body"] == b"v2"


async def test_with_conditional_writes_the_conditions_travel_to_the_server(s3):
    await bucket(conditional=True).put("k", b"v", if_match="e1")
    await bucket(conditional=True).put("n", b"v", if_none_match=True)

    sent = [c[1] for c in s3.calls if c[0] == "put"]
    assert sent[0]["IfMatch"] == '"e1"' and sent[1]["IfNoneMatch"] == "*"
    assert not [c for c in s3.calls if c[0] == "head"]


async def test_a_412_from_the_server_is_a_precondition_failure(s3):
    s3.fail = client_error("PreconditionFailed", 412)
    with pytest.raises(PreconditionFailed):
        await bucket(conditional=True).put("k", b"v", if_match="e1")


@pytest.mark.parametrize(
    "error",
    [
        client_error("InternalError", 500),
        client_error("SlowDown", 503),
        ConnectTimeoutError(endpoint_url="http://stockage.local"),
        TimeoutError(),
        OSError("réseau"),
        RuntimeError("autre"),
    ],
)
async def test_everything_else_is_an_outage(s3, error):
    s3.fail = error
    with pytest.raises(BucketUnavailable):
        await bucket().list_objects("u/")


async def test_listing_follows_the_pages(s3):
    item = {"ETag": '"x"', "LastModified": NOW, "Size": 1}
    s3.pages = [
        {"Contents": [{"Key": "u/a.md", **item}], "IsTruncated": True, "NextContinuationToken": "t"},
        {"Contents": [{"Key": "u/b.md", **item}], "IsTruncated": False},
    ]
    assert [o.key for o in await bucket().list_objects("u/")] == ["u/a.md", "u/b.md"]
    assert s3.calls[1][1]["ContinuationToken"] == "t"


async def test_prefixes_and_versioning(s3):
    s3.pages = [{"CommonPrefixes": [{"Prefix": "01A/"}, {"Prefix": "01B/"}], "IsTruncated": False}]
    assert await bucket().list_prefixes() == ["01A", "01B"]
    assert await bucket().versioning_status() == "Enabled"


async def test_one_boto_session_serves_every_call(monkeypatch):
    # A session per call reloads botocore's models each time: +130 MB per worker, and production's
    # agent pod was OOM-killed on its first sync passes (MAG-309, deploy of 1d92172 rolled back).
    import aioboto3

    created: list[object] = []

    class Session:
        def __init__(self) -> None:
            created.append(self)

        def client(self, *args, **kwargs):
            return StubS3()

    monkeypatch.setattr(aioboto3, "Session", Session)
    b = bucket()
    for _ in range(3):
        assert await b.head("absent") is None
    assert len(created) == 1


async def test_an_audio_put_says_it_is_audio(s3):
    """MAG-685: a voice message's MP3 is not Markdown, and the signed URL serves it with its type."""
    await bucket().put("audio/u/m.mp3", b"\xff\xfb", content_type="audio/mpeg")
    await bucket().put("u/Mémoire/a.md", b"texte")

    sent = [c[1] for c in s3.calls if c[0] == "put"]
    assert [s["ContentType"] for s in sent] == ["audio/mpeg", "text/markdown; charset=utf-8"]


async def test_a_signed_url_is_presigned_offline_and_expires():
    """The real client, no stub: presigning is local computation, so this proves aiobotocore's signature."""
    url = await bucket().signed_url("audio/u/m.mp3", 900)

    assert url.startswith("http://stockage.local/b/audio/u/m.mp3?")
    assert "X-Amz-Expires=900" in url and "X-Amz-Signature=" in url

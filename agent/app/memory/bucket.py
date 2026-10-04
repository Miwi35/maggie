"""The bucket behind one interface: `S3Bucket` against OVH (or any S3), `FakeBucket` in memory.

The bucket is where the notes live, so everything above this file talks to `Bucket` and never to
boto. Three outcomes are told apart, because each one is handled differently upstream:

- `BucketUnavailable`: the bucket could not be reached or answered with a server error. Degraded
  mode: serve the index as stale, queue the writes.
- `PreconditionFailed`: the object is not the version the writer had read (HTTP 412). A conflict.
- `ObjectNotFound`: the key does not exist.

ETags are handled without their quotes everywhere in the agent.
"""

import asyncio
import hashlib
import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol

logger = logging.getLogger(__name__)


class BucketError(Exception):
    pass


class BucketUnavailable(BucketError):
    """Unreachable, timed out, or a 5xx: nothing was learned about the object."""


class PreconditionFailed(BucketError):
    """`If-Match` / `If-None-Match` did not hold: someone else wrote the object first."""


class ObjectNotFound(BucketError):
    pass


@dataclass(frozen=True)
class ObjectInfo:
    key: str
    etag: str
    last_modified: datetime
    size: int = 0


@dataclass(frozen=True)
class ObjectData:
    key: str
    body: bytes
    etag: str
    last_modified: datetime


def clean_etag(etag: str | None) -> str:
    return (etag or "").strip().removeprefix("W/").strip('"')


class Bucket(Protocol):
    async def list_objects(self, prefix: str = "") -> list[ObjectInfo]:
        """Every object under `prefix`, or `BucketUnavailable`: a partial listing is never returned."""
        ...

    async def head(self, key: str) -> ObjectInfo | None: ...

    async def get(self, key: str) -> ObjectData: ...

    async def put(self, key: str, body: bytes, *, if_match: str | None = None, if_none_match: bool = False) -> str:
        """Write `key`, return its new ETag. `if_match` / `if_none_match` raise `PreconditionFailed` on a lost race."""
        ...

    async def delete(self, key: str) -> None: ...

    async def versioning_status(self) -> str:
        """`Enabled`, `Suspended` or `Off`: the bucket is only a safe source of truth when versioned."""
        ...


class S3Bucket:
    """aioboto3 against an S3-compatible endpoint. One short-lived client per call: no stale connection to repair."""

    def __init__(
        self,
        bucket: str,
        endpoint_url: str,
        region: str,
        access_key: str,
        secret_key: str,
        timeout_seconds: float = 10.0,
        conditional_writes: bool = True,
    ) -> None:
        self.bucket = bucket
        self.endpoint_url = endpoint_url or None
        self.region = region or None
        self._access_key = access_key
        self._secret_key = secret_key
        self.timeout_seconds = timeout_seconds
        # False = the endpoint ignores or refuses If-Match / If-None-Match: the store re-reads the ETag before writing.
        self.conditional_writes = conditional_writes

    def _client(self) -> Any:
        import aioboto3
        from botocore.config import Config

        return aioboto3.Session().client(
            "s3",
            endpoint_url=self.endpoint_url,
            region_name=self.region,
            aws_access_key_id=self._access_key,
            aws_secret_access_key=self._secret_key,
            config=Config(
                connect_timeout=self.timeout_seconds,
                read_timeout=self.timeout_seconds,
                retries={"max_attempts": 2, "mode": "standard"},
                signature_version="s3v4",
            ),
        )

    @staticmethod
    def _translate(error: Exception) -> BucketError:
        from botocore.exceptions import BotoCoreError, ClientError

        if isinstance(error, ClientError):
            code = str(error.response.get("Error", {}).get("Code", ""))
            status = int(error.response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0) or 0)
            if code in ("NoSuchKey", "404", "NotFound") or status == 404:
                return ObjectNotFound(code or "not found")
            if code in ("PreconditionFailed", "ConditionalRequestConflict") or status in (409, 412):
                return PreconditionFailed(code or "precondition failed")
            return BucketUnavailable(f"{code or status}: {error}")
        if isinstance(error, BotoCoreError | TimeoutError | OSError):
            return BucketUnavailable(str(error))
        return BucketUnavailable(repr(error))

    async def _call(self, operation: Callable[[Any], Any]) -> Any:
        try:
            async with self._client() as s3:
                return await asyncio.wait_for(operation(s3), timeout=self.timeout_seconds * 3)
        except BucketError:
            raise
        except Exception as e:
            raise self._translate(e) from e

    async def list_objects(self, prefix: str = "") -> list[ObjectInfo]:
        async def run(s3: Any) -> list[ObjectInfo]:
            found: list[ObjectInfo] = []
            token: str | None = None
            while True:
                kwargs: dict[str, Any] = {"Bucket": self.bucket, "Prefix": prefix}
                if token:
                    kwargs["ContinuationToken"] = token
                page = await s3.list_objects_v2(**kwargs)
                for item in page.get("Contents", []):
                    found.append(
                        ObjectInfo(
                            key=item["Key"],
                            etag=clean_etag(item.get("ETag")),
                            last_modified=item["LastModified"],
                            size=int(item.get("Size", 0)),
                        )
                    )
                if not page.get("IsTruncated"):
                    return found
                token = page.get("NextContinuationToken")

        return await self._call(run)

    async def head(self, key: str) -> ObjectInfo | None:
        async def run(s3: Any) -> ObjectInfo:
            head = await s3.head_object(Bucket=self.bucket, Key=key)
            size = int(head.get("ContentLength", 0))
            return ObjectInfo(key, clean_etag(head.get("ETag")), head["LastModified"], size)

        try:
            return await self._call(run)
        except ObjectNotFound:
            return None

    async def get(self, key: str) -> ObjectData:
        async def run(s3: Any) -> ObjectData:
            response = await s3.get_object(Bucket=self.bucket, Key=key)
            async with response["Body"] as stream:
                body = await stream.read()
            return ObjectData(key, body, clean_etag(response.get("ETag")), response["LastModified"])

        return await self._call(run)

    async def put(self, key: str, body: bytes, *, if_match: str | None = None, if_none_match: bool = False) -> str:
        if not self.conditional_writes:
            await self._check_precondition(key, if_match, if_none_match)

        async def run(s3: Any) -> str:
            kwargs: dict[str, Any] = {
                "Bucket": self.bucket,
                "Key": key,
                "Body": body,
                "ContentType": "text/markdown; charset=utf-8",
            }
            if self.conditional_writes and if_match:
                kwargs["IfMatch"] = f'"{clean_etag(if_match)}"'
            if self.conditional_writes and if_none_match:
                kwargs["IfNoneMatch"] = "*"
            response = await s3.put_object(**kwargs)
            return clean_etag(response.get("ETag"))

        try:
            return await self._call(run)
        except BucketUnavailable as e:
            if self.conditional_writes and (if_match or if_none_match) and _is_unsupported(e):
                logger.warning(f"Bucket endpoint refuses conditional writes ({e}); falling back to read-then-write")
                self.conditional_writes = False
                return await self.put(key, body, if_match=if_match, if_none_match=if_none_match)
            raise

    async def _check_precondition(self, key: str, if_match: str | None, if_none_match: bool) -> None:
        """The fallback: compare the current ETag ourselves. Narrower than a real condition, not absent."""
        current = await self.head(key)
        if if_none_match and current is not None:
            raise PreconditionFailed(f"{key} already exists")
        if if_match and (current is None or current.etag != clean_etag(if_match)):
            raise PreconditionFailed(f"{key} is no longer at ETag {clean_etag(if_match)}")

    async def delete(self, key: str) -> None:
        async def run(s3: Any) -> None:
            await s3.delete_object(Bucket=self.bucket, Key=key)

        await self._call(run)

    async def versioning_status(self) -> str:
        async def run(s3: Any) -> str:
            response = await s3.get_bucket_versioning(Bucket=self.bucket)
            return str(response.get("Status") or "Off")

        return await self._call(run)


def _is_unsupported(error: BucketUnavailable) -> bool:
    text = str(error)
    return any(marker in text for marker in ("NotImplemented", "501", "InvalidArgument", "MalformedHeader"))


class FakeBucket:
    """The bucket in memory, for tests and the e2e stack (MAG-195: no MinIO is added to the stack).

    Same interface as `S3Bucket`, with the three things the sync code must survive made injectable:
    an outage (`down`), a lost race (`fail_next_puts_with_412`) and a listing that dies half-way
    (`fail_listing_after_pages`). It keeps every version of every key, like the versioned bucket.
    """

    def __init__(self, clock: Callable[[], datetime] | None = None, page_size: int = 1000) -> None:
        self._clock = clock or (lambda: datetime.now(UTC))
        self.page_size = page_size
        self.objects: dict[str, ObjectData] = {}
        self.versions: dict[str, list[ObjectData]] = {}
        self.down = False
        self.fail_next_puts_with_412 = 0
        self.fail_listing_after_pages: int | None = None
        self.conditional_writes = True
        self.calls: list[str] = []

    def _guard(self, operation: str) -> None:
        self.calls.append(operation)
        if self.down:
            raise BucketUnavailable("fake bucket is down")

    @staticmethod
    def _etag(body: bytes) -> str:
        return hashlib.md5(body, usedforsecurity=False).hexdigest()

    def seed(self, key: str, body: str | bytes, at: datetime | None = None) -> str:
        """Put an object as the owner would (his editor, his sync client): no outage, no condition."""
        data = body.encode("utf-8") if isinstance(body, str) else body
        obj = ObjectData(key, data, self._etag(data), at or self._clock())
        self.objects[key] = obj
        self.versions.setdefault(key, []).append(obj)
        return obj.etag

    def remove(self, key: str) -> None:
        self.objects.pop(key, None)

    def count(self, operation: str) -> int:
        return self.calls.count(operation)

    async def list_objects(self, prefix: str = "") -> list[ObjectInfo]:
        self._guard("list")
        keys = sorted(key for key in self.objects if key.startswith(prefix))
        found: list[ObjectInfo] = []
        for page, start in enumerate(range(0, len(keys), self.page_size)):
            if self.fail_listing_after_pages is not None and page >= self.fail_listing_after_pages:
                raise BucketUnavailable("fake listing died half-way")
            found.extend(
                ObjectInfo(k, self.objects[k].etag, self.objects[k].last_modified, len(self.objects[k].body))
                for k in keys[start : start + self.page_size]
            )
        return found

    async def head(self, key: str) -> ObjectInfo | None:
        self._guard("head")
        obj = self.objects.get(key)
        return ObjectInfo(key, obj.etag, obj.last_modified, len(obj.body)) if obj else None

    async def get(self, key: str) -> ObjectData:
        self._guard("get")
        if key not in self.objects:
            raise ObjectNotFound(key)
        return self.objects[key]

    async def put(self, key: str, body: bytes, *, if_match: str | None = None, if_none_match: bool = False) -> str:
        self._guard("put")
        if self.fail_next_puts_with_412 > 0:
            self.fail_next_puts_with_412 -= 1
            raise PreconditionFailed("injected 412")
        current = self.objects.get(key)
        if if_none_match and current is not None:
            raise PreconditionFailed(f"{key} already exists")
        if if_match and (current is None or current.etag != clean_etag(if_match)):
            raise PreconditionFailed(f"{key} is no longer at ETag {clean_etag(if_match)}")
        return self.seed(key, body)

    async def delete(self, key: str) -> None:
        self._guard("delete")
        self.objects.pop(key, None)

    async def versioning_status(self) -> str:
        self._guard("versioning")
        return "Enabled"

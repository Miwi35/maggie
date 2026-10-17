"""The e2e-only surface of the agent: what a journey needs to read and cannot see on screen.

Mounted only when `TTS_PROVIDER=fake`, i.e. on the e2e stack — in dev and prod the routes do
not exist, so there is nothing to protect them with beyond the token (MAG-205). The pendant of
the API's `E2eSurfaceAbsenceTest`: `tests/test_e2e_surface.py`.
"""

import hmac
from datetime import UTC, datetime, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel

from app.config import settings
from app.db.memory_index_repository import memory_index_repo, memory_outbox_repo
from app.llm.fake import images_received, reset_images_received
from app.llm.transcription import cleanup_requests, reset_cleanup_requests
from app.memory import paths as memory_paths
from app.memory.bucket import BucketError, FakeBucket
from app.memory.night import memory_night
from app.memory.service import memory_service
from app.queue import scheduler
from app.tts.synthesis import fake_synthesis_requests, reset_fake_synthesis_requests


def require_e2e_token(x_e2e_token: str = Header(default="")) -> None:
    expected = settings.e2e_login_token
    if not expected or not hmac.compare_digest(expected.encode(), x_e2e_token.encode()):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid e2e token")


router = APIRouter(prefix="/e2e", dependencies=[Depends(require_e2e_token)])


@router.get("/tts/syntheses")
async def tts_syntheses() -> dict[str, int]:
    return {"count": fake_synthesis_requests()}


@router.delete("/tts/syntheses")
async def reset_tts_syntheses() -> dict[str, int]:
    reset_fake_synthesis_requests()
    return {"count": 0}


@router.get("/transcription/cleanups")
async def transcription_cleanups() -> dict[str, int]:
    """How many transcript cleanups the model was asked for since the last reset.

    The voice journey's proof that talking to Maggie no longer pays for one (MAG-222):
    an absence, which no screen shows.
    """
    return {"count": cleanup_requests()}


@router.delete("/transcription/cleanups")
async def reset_transcription_cleanups() -> dict[str, int]:
    reset_cleanup_requests()
    return {"count": 0}


@router.get("/llm/images")
async def llm_images() -> dict[str, int]:
    """How many questions reached the fake model with a picture since the last reset (MAG-214)."""
    return {"count": images_received()}


@router.delete("/llm/images")
async def reset_llm_images() -> dict[str, int]:
    reset_images_received()
    return {"count": 0}


class MemoryText(BaseModel):
    text: str


class MemoryOutage(BaseModel):
    down: bool


class MemoryWrite(BaseModel):
    path: str
    text: str


def _fake_bucket() -> FakeBucket:
    bucket = memory_service.bucket
    if not isinstance(bucket, FakeBucket):
        raise HTTPException(status.HTTP_409_CONFLICT, detail="MEMORY_PROVIDER is not fake")
    return bucket


def memory_user(user_id: str) -> str:
    try:
        return memory_paths.check_user_id(user_id)
    except memory_paths.UnsafePath as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail=str(e)) from e


MemoryUser = Annotated[str, Depends(memory_user)]


def _key(user_id: str, path: str) -> str:
    try:
        return memory_paths.bucket_key(user_id, path)
    except memory_paths.UnsafePath as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail=str(e)) from e


@router.put("/memory/bucket/{user_id}/{path:path}")
async def memory_bucket_put(user_id: str, path: str, body: MemoryText) -> dict[str, str]:
    """The owner edits a file in the bucket, with his own tools: no condition, no outage."""
    return {"etag": _fake_bucket().seed(_key(user_id, path), body.text)}


@router.get("/memory/bucket/{user_id}/{path:path}")
async def memory_bucket_get(user_id: str, path: str) -> dict[str, str]:
    """What the bucket holds, read around the outage: the journey's proof that a write arrived."""
    obj = _fake_bucket().objects.get(_key(user_id, path))
    if obj is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="not in the bucket")
    return {"text": obj.body.decode("utf-8"), "etag": obj.etag}


@router.delete("/memory/bucket/{user_id}/{path:path}")
async def memory_bucket_delete(user_id: str, path: str) -> dict[str, bool]:
    _fake_bucket().remove(_key(user_id, path))
    return {"deleted": True}


@router.put("/memory/outage")
async def memory_outage(body: MemoryOutage) -> dict[str, bool]:
    _fake_bucket().down = body.down
    return {"down": body.down}


@router.post("/memory/{user_id}/sync")
async def memory_sync(user_id: MemoryUser) -> dict[str, object]:
    """Run one synchronisation pass now, instead of waiting for the loop."""
    try:
        report = await memory_service.sync_user(user_id)
    except BucketError as e:
        return {"ok": False, "error": str(e)}
    return {
        "ok": True,
        "pulled": report.pulled,
        "pushed": report.pushed,
        "deleted": report.deleted,
        "conflicts": report.conflicts,
        "reindexed": report.reindexed,
    }


@router.post("/memory/{user_id}/encode")
async def memory_encode(user_id: MemoryUser) -> dict[str, int]:
    """Run the encoding pass of one user as if the silence had gone past its delay (MAG-15).

    The stack's clock barely ticks (MAG-234), so waiting `memory_encode_after_seconds` would never
    end: the pass is given a later instant instead, and looks at that user's threads only.
    """
    later = datetime.now(UTC) + timedelta(seconds=settings.memory_encode_after_seconds + 5)
    return {"encoded": await scheduler.run_memory_encoding(later, user_id)}


@router.post("/memory/{user_id}/night")
async def memory_night_run(user_id: MemoryUser) -> dict[str, object]:
    """Run the night's upkeep of one user now, at an instant past the encoding delay (MAG-159).

    The pass starts whatever the hour and carries on until it ends or spends its calls; the answer is
    its account, as `memory_night_run.report` keeps it.
    """
    later = datetime.now(UTC) + timedelta(seconds=settings.memory_encode_after_seconds + 5)
    run = await memory_night.run(user_id, later, force=True)
    for _ in range(20):
        if run is None or run.status == "done":
            break
        run = await memory_night.run(user_id, later) or run
    if run is None:
        return {"status": "none"}
    return {"status": run.status, "night": run.night, "calls": run.calls, "report": run.report}


@router.post("/memory/{user_id}/write")
async def memory_write(user_id: MemoryUser, body: MemoryWrite) -> dict[str, str]:
    """Write a file as Maggie would, to set up a state (a late write in an outage) without the model."""
    try:
        return {"path": await memory_service.write(user_id, body.path, body.text)}
    except memory_paths.UnsafePath as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail=str(e)) from e


@router.get("/memory/{user_id}/state")
async def memory_state(user_id: MemoryUser) -> dict[str, object]:
    return {
        "degraded": memory_service.degraded(user_id),
        "local": memory_service.folder(user_id).files(),
        "indexed": sorted(await memory_index_repo.hashes(user_id)),
        "outbox": [e.path for e in await memory_outbox_repo.pending(user_id)],
    }


@router.get("/memory/{user_id}/search")
async def memory_search(user_id: MemoryUser, q: str) -> dict[str, list[str]]:
    return {"paths": [f.path for f in await memory_service.search(user_id, q)]}


@router.post("/memory/{user_id}/rebuild")
async def memory_rebuild(user_id: MemoryUser) -> dict[str, int]:
    report = await memory_service.rebuild(user_id)
    return {"users": report.users, "files": report.files}


@router.delete("/memory/{user_id}")
async def memory_reset(user_id: MemoryUser) -> dict[str, bool]:
    """Start a journey from nothing: no object, no local file, no row, no queued write."""
    bucket = _fake_bucket()
    for key in [k for k in bucket.objects if k.startswith(memory_paths.bucket_prefix(user_id))]:
        bucket.remove(key)
    bucket.down = False
    memory_service.folder(user_id).wipe()
    await memory_index_repo.clear_user(user_id)
    for entry in await memory_outbox_repo.pending(user_id):
        await memory_outbox_repo.done(user_id, entry.path)
    memory_service.reset_status(user_id)
    return {"reset": True}


def setup_e2e(app: FastAPI) -> None:
    if settings.tts_provider == "fake":
        app.include_router(router)

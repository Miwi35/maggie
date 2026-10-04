"""The e2e-only surface of the agent: what a journey needs to read and cannot see on screen.

Mounted only when `TTS_PROVIDER=fake`, i.e. on the e2e stack — in dev and prod the routes do
not exist, so there is nothing to protect them with beyond the token (MAG-205). The pendant of
the API's `E2eSurfaceAbsenceTest`: `tests/test_e2e_surface.py`.
"""

import hmac

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel

from app.config import settings
from app.db.memory_note_repository import memory_note_repo
from app.memory.bucket import FakeBucket
from app.memory.frontmatter import NoteDoc
from app.memory.service import get_service
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


class BucketOutage(BaseModel):
    down: bool


class BucketObject(BaseModel):
    key: str
    content: str


def _fake_bucket() -> FakeBucket:
    service = get_service()
    if service is None or not isinstance(service.bucket, FakeBucket):
        raise HTTPException(status_code=409, detail="The memory bucket is not the simulated one")
    return service.bucket


@router.put("/memory/bucket/outage")
async def memory_bucket_outage(body: BucketOutage) -> dict[str, bool]:
    _fake_bucket().down = body.down
    return {"down": body.down}


@router.put("/memory/bucket/object")
async def memory_bucket_put(body: BucketObject) -> dict[str, str]:
    """The owner edits a note in his bucket: no outage, no condition, nothing goes through Maggie."""
    return {"etag": _fake_bucket().seed(body.key, body.content)}


@router.get("/memory/bucket/object")
async def memory_bucket_get(key: str) -> dict[str, str | None]:
    found = _fake_bucket().objects.get(key)
    return {"content": found.body.decode("utf-8") if found else None}


@router.get("/memory/bucket/keys")
async def memory_bucket_keys() -> dict[str, list[str]]:
    return {"keys": sorted(_fake_bucket().objects)}


class NoteWrite(BaseModel):
    user_id: str
    title: str
    body: str


@router.post("/memory/notes")
async def memory_note_write(body: NoteWrite) -> dict[str, str | None]:
    """Maggie writes a note through the write door (the note tools themselves are MAG-17)."""
    service = get_service()
    if service is None:
        raise HTTPException(status_code=409, detail="The memory bucket is not configured")
    result = await service.store.save(body.user_id, NoteDoc(title=body.title, body=body.body))
    return {"status": result.status, "noteId": result.note_id, "path": result.path}


@router.post("/memory/sync")
async def memory_sync_now() -> dict[str, bool]:
    service = get_service()
    if service is None:
        raise HTTPException(status_code=409, detail="The memory bucket is not configured")
    outcome = await service.sync.run_pass("e2e")
    return {"ok": outcome.ok}


@router.get("/memory/outbox")
async def memory_outbox() -> dict[str, int]:
    return {"pending": await memory_note_repo.outbox_size()}


def setup_e2e(app: FastAPI) -> None:
    if settings.tts_provider == "fake":
        app.include_router(router)

"""The memory folder, for the web (MAG-20): browse, open, correct, move, delete, export, and the link with the bucket.

Every route is the signed-in user's own and reads their folder alone: a path that is not in it — another user's
included — is an unknown path (404). Changes go through `MemoryService`, so they are written to the file first, then
indexed, then journaled, like Maggie's own.
"""

import contextlib
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import Response
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.memory import inspector, paths
from app.memory.bucket import BucketError
from app.memory.service import memory_service
from app.memory.ulid import is_ulid

router = APIRouter(prefix="/memory")


def memory_user(user_id: str = Depends(get_current_user_id)) -> str:
    if not is_ulid(user_id):
        raise HTTPException(status_code=404, detail="No memory folder for this account")
    return user_id


class FileUpdate(BaseModel):
    text: str = Field(max_length=500_000)
    # The hash of the text the owner opened: a file that changed meanwhile is refused, not overwritten.
    hash: str | None = None


class FileMove(BaseModel):
    to: str = Field(min_length=1, max_length=paths.MAX_RELATIVE_BYTES)


def _failure(error: Exception) -> HTTPException:
    if isinstance(error, inspector.NotFound):
        return HTTPException(status_code=404, detail="Unknown file")
    if isinstance(error, inspector.Conflict):
        return HTTPException(status_code=409, detail="The file changed or the name is taken")
    if isinstance(error, inspector.Refused):
        return HTTPException(status_code=409, detail=str(error))
    return HTTPException(status_code=400, detail=str(error))


async def _guarded(call: Callable[[], Any]) -> Any:
    try:
        return await call()
    except (inspector.NotFound, inspector.Conflict, inspector.Refused, inspector.Invalid) as e:
        raise _failure(e) from e


@router.get("/tree")
async def get_tree(user_id: str = Depends(memory_user)):
    return {"files": await inspector.tree(memory_service, user_id)}


@router.get("/file")
async def get_file(path: str = Query(min_length=1), user_id: str = Depends(memory_user)):
    return await _guarded(lambda: inspector.open_file(memory_service, user_id, path))


@router.put("/file")
async def put_file(data: FileUpdate, path: str = Query(min_length=1), user_id: str = Depends(memory_user)):
    return await _guarded(lambda: inspector.save_file(memory_service, user_id, path, data.text, data.hash))


@router.post("/file/move")
async def move_file(data: FileMove, path: str = Query(min_length=1), user_id: str = Depends(memory_user)):
    return await _guarded(lambda: inspector.move_file(memory_service, user_id, path, data.to))


@router.delete("/file", status_code=204)
async def delete_file(path: str = Query(min_length=1), user_id: str = Depends(memory_user)):
    await _guarded(lambda: inspector.delete_file(memory_service, user_id, path))
    return Response(status_code=204)


@router.get("/journal")
async def get_journal(
    limit: int = Query(200, ge=1, le=inspector.MAX_JOURNAL_ENTRIES),
    user_id: str = Depends(memory_user),
):
    return {"entries": inspector.journal(memory_service, user_id, limit)}


@router.get("/sync")
async def get_sync(user_id: str = Depends(memory_user)):
    return await inspector.sync_state(memory_service, user_id)


@router.post("/sync")
async def run_sync(user_id: str = Depends(memory_user)):
    """One pass now. A bucket that does not answer is a state to show, not an error."""
    if not memory_service.enabled:
        raise HTTPException(status_code=409, detail="The memory folder has no bucket")
    with contextlib.suppress(BucketError):
        await memory_service.sync_user(user_id)
    return await inspector.sync_state(memory_service, user_id)


@router.post("/rebuild")
async def rebuild_index(user_id: str = Depends(memory_user)):
    """Drop this user's index and rebuild it from the files: nothing is lost but the usage counters' order."""
    report = await memory_service.rebuild(user_id)
    sync = await inspector.sync_state(memory_service, user_id)
    return {"files": report.files, "skipped": report.skipped, "sync": sync}


@router.get("/misses")
async def get_misses(user_id: str = Depends(memory_user)):
    return {"misses": await inspector.missed_recalls(memory_service, user_id)}


@router.get("/export")
async def export_memory(user_id: str = Depends(memory_user)):
    archive = inspector.export_archive(memory_service, user_id)
    name = f"memoire-maggie-{datetime.now(UTC):%Y-%m-%d}.zip"
    return Response(
        content=archive,
        media_type="application/zip",
        headers={"Content-Disposition": f"attachment; filename=\"{name}\"; filename*=UTF-8''{quote(name)}"},
    )

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from app.auth import get_current_user_id
from app.db.memory_note_repository import memory_note_repo
from app.memory.service import MemoryService, get_service
from app.memory.sync import RebuildBlocked

router = APIRouter(prefix="/memory")


def _service() -> MemoryService:
    service = get_service()
    if service is None:
        raise HTTPException(status_code=503, detail="The memory bucket is not configured")
    return service


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


@router.get("/status")
async def memory_status(_user_id: str = Depends(get_current_user_id)) -> dict[str, Any]:
    sync = _service().sync
    age = sync.stale_age()
    return {
        "degraded": sync.degraded,
        "failedPasses": sync.failures,
        "lastAttemptAt": _iso(sync.last_attempt_at),
        "lastSuccessAt": _iso(sync.last_success_at),
        "indexAgeSeconds": int(age.total_seconds()) if age else None,
        "outboxPending": await memory_note_repo.outbox_size(),
    }


@router.post("/sync")
async def memory_sync(_user_id: str = Depends(get_current_user_id)) -> dict[str, Any]:
    """Manual trigger: one reconciliation pass now, outbox flushed first."""
    sync = _service().sync
    outcome = await sync.run_pass("manual")
    body = outcome.to_dict()
    if outcome.report is not None:
        body["report"] = outcome.report.to_counts()  # the pass covers every user: aggregates only
    return {**body, "degraded": sync.degraded}


@router.post("/rebuild")
async def memory_rebuild(user_id: str = Depends(get_current_user_id)) -> dict[str, Any]:
    """Drop the caller's index and read it back from the bucket (a user only rebuilds their own notes)."""
    try:
        report = await _service().sync.rebuild(user_id)
    except RebuildBlocked as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except Exception as error:
        raise HTTPException(status_code=503, detail=f"Rebuild failed, index untouched or partial: {error}") from error
    return {"report": report.to_counts()}


@router.get("/events")
async def memory_events(user_id: str = Depends(get_current_user_id), limit: int = 100) -> list[dict[str, Any]]:
    events = await memory_note_repo.events(user_id, min(max(limit, 1), 500))
    return [
        {"id": e.id, "kind": e.kind, "noteId": e.note_id, "path": e.path, "detail": e.detail, "at": _iso(e.at)}
        for e in events
    ]

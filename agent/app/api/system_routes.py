from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from app.auth import get_current_user_id, require_service_token
from app.db.provider_state_repository import provider_state_repo
from app.db.user_data import purge_user_data

router = APIRouter()


@router.get("/health")
async def health():
    return {"status": "ok", "service": "maggie-agent-hub"}


class ProviderStatusResponse(BaseModel):
    status: Literal["ok", "credit", "auth"]
    since: datetime | None = None


@router.get("/provider-status", response_model=ProviderStatusResponse)
async def provider_status(user_id: str = Depends(get_current_user_id)):
    """Whether Maggie can reach the model: the banner of the apps reads this (MAG-727). Global to the account."""
    status, since = await provider_state_repo.get()
    return ProviderStatusResponse(status=status, since=since)


class RecetteResetRequest(BaseModel):
    userId: str = Field(min_length=1)
    dryRun: bool = False


@router.post("/internal/recette/reset")
async def reset_recette_data(request: RecetteResetRequest, _: None = Depends(require_service_token)):
    """Wipe the agent's rows of one user — called by `app:recette:reset` with the service token (MAG-249).

    The user is the API command's constant, never a client input: no user token reaches this route.
    """
    return {"deleted": await purge_user_data(request.userId, dry_run=request.dryRun)}

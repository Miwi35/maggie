"""The e2e-only surface of the agent: what a journey needs to read and cannot see on screen.

Mounted only when `TTS_PROVIDER=fake`, i.e. on the e2e stack — in dev and prod the routes do
not exist, so there is nothing to protect them with beyond the token (MAG-205). The pendant of
the API's `E2eSurfaceAbsenceTest`: `tests/test_e2e_surface.py`.
"""

import hmac

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, status

from app.config import settings
from app.llm.transcription import cleanup_requests, reset_cleanup_requests
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


def setup_e2e(app: FastAPI) -> None:
    if settings.tts_provider == "fake":
        app.include_router(router)

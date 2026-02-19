import logging
from pathlib import Path

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import settings

logger = logging.getLogger(__name__)

security = HTTPBearer()

_public_key: str | None = None


def _get_public_key() -> str:
    """Load the RS256 public key from disk (cached after first load)."""
    global _public_key
    if _public_key is None:
        key_path = Path(settings.jwt_public_key_path)
        if not key_path.exists():
            raise RuntimeError(f"JWT public key not found at {key_path}")
        _public_key = key_path.read_text()
    return _public_key


def get_current_user_id(
    credentials: HTTPAuthorizationCredentials = Depends(security),
) -> str:
    """FastAPI dependency: validate Bearer JWT and return user_id from 'sub' claim."""
    token = credentials.credentials
    try:
        payload = jwt.decode(
            token,
            _get_public_key(),
            algorithms=["RS256"],
            options={"verify_exp": True, "verify_aud": False},
        )
    except jwt.ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has expired",
        )
    except jwt.InvalidTokenError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid token: {e}",
        )

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token missing 'sub' claim",
        )
    return user_id

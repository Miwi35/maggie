import hmac
import logging
from dataclasses import dataclass
from pathlib import Path

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import settings

logger = logging.getLogger(__name__)

security = HTTPBearer()

PROACTION_TRIGGER_ROLE = "ROLE_PROACTION_TRIGGER"

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


@dataclass(frozen=True)
class Principal:
    """Who is calling: the user ULID (`sub`) and the roles the API put in the JWT."""

    user_id: str
    roles: frozenset[str]


def get_current_principal(
    credentials: HTTPAuthorizationCredentials = Depends(security),  # noqa: B008
) -> Principal:
    """FastAPI dependency: validate Bearer JWT and return who it identifies."""
    token = credentials.credentials
    try:
        payload = jwt.decode(
            token,
            _get_public_key(),
            algorithms=["RS256"],
            options={"verify_exp": True, "verify_aud": False},
            leeway=settings.jwt_leeway_seconds,
        )
    except jwt.ExpiredSignatureError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has expired",
        ) from e
    except jwt.InvalidTokenError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid token: {e}",
        ) from e

    user_id = payload.get("sub") or payload.get("username")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token missing 'sub' claim",
        )
    roles = payload.get("roles")
    granted = frozenset(r for r in roles if isinstance(r, str)) if isinstance(roles, list) else frozenset()
    return Principal(user_id=user_id, roles=granted)


def get_current_user_id(principal: Principal = Depends(get_current_principal)) -> str:  # noqa: B008
    """FastAPI dependency: validate Bearer JWT and return user_id from 'sub' claim."""
    return principal.user_id


def require_proaction_trigger(principal: Principal = Depends(get_current_principal)) -> str:  # noqa: B008
    """The caller's user id, provided the API granted them `ROLE_PROACTION_TRIGGER` (MAG-249).

    The permission is read from the JWT, so a grant or a revoke applies to the next token, not the current one.
    """
    if PROACTION_TRIGGER_ROLE not in principal.roles:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Missing permission: proaction trigger")
    return principal.user_id


def require_service_token(credentials: HTTPAuthorizationCredentials = Depends(security)) -> None:  # noqa: B008
    """Service-to-service calls (the API's console commands): bearer `SERVICE_TOKEN`; closed when it is unset."""
    expected = settings.service_token
    if not expected or not hmac.compare_digest(credentials.credentials.encode(), expected.encode()):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid service token")

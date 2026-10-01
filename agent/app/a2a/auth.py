import hmac
import logging

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.config import settings

logger = logging.getLogger(__name__)

A2A_RPC_PATH = "/a2a"


class A2ABearerMiddleware:
    """Requires `Authorization: Bearer <A2A_TOKEN>` on the A2A RPC route.

    The agent card stays public: it is how a peer discovers where and how to call.
    An unset A2A_TOKEN refuses every call rather than letting everyone in.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or self._route_path(scope) != A2A_RPC_PATH:
            await self.app(scope, receive, send)
            return

        if not self._authorized(scope):
            logger.warning("A2A call refused: missing or invalid bearer token")
            response = JSONResponse(
                {"detail": "Not authenticated"}, status_code=401, headers={"WWW-Authenticate": "Bearer"}
            )
            await response(scope, receive, send)
            return

        await self.app(scope, receive, send)

    @staticmethod
    def _route_path(scope: Scope) -> str:
        path = scope["path"]
        root_path = scope.get("root_path", "")
        if root_path and path.startswith(root_path):
            path = path[len(root_path) :]
        return path.rstrip("/") or "/"

    @staticmethod
    def _authorized(scope: Scope) -> bool:
        expected = settings.a2a_token
        if not expected:
            return False
        header = dict(scope["headers"]).get(b"authorization", b"").decode("latin-1")
        scheme, _, presented = header.partition(" ")
        if scheme.lower() != "bearer" or not presented:
            return False
        return hmac.compare_digest(presented.encode(), expected.encode())

"""Which e2e journey is the agent working for — the coverage per journey of the nightly.

Off unless `E2E_COVERAGE=1`, which only the nightly sets on the e2e stack: then the agent runs
under coverage.py (see `coveragerc` here and the agent's command in docker-compose.e2e.yml), each
request carries `X-E2E-Journey: <the journey's repo path>`, and every line it executes is recorded
under that journey (spec « Sélection e2e par couverture », part A).

The journey travels in a context variable, not in a global: Playwright runs two journeys at once,
and a chat turn outlives its request in a task of its own (MAG-344). `asyncio` copies the variable
into every task created while it is set, so the turn keeps its journey; `plugin.py` reads it each
time a task resumes, so coverage.py attributes the lines to the task's journey rather than to
whichever request came last. Calls to the API carry it on (`journey_headers`), so the API's lines
land on the same journey.

Without `E2E_COVERAGE=1` the middleware is not installed, the variable is never set and
`journey_headers` returns nothing: no cost, no change.
"""

import os
import re
from contextvars import ContextVar

from fastapi import FastAPI
from starlette.types import ASGIApp, Receive, Scope, Send

HEADER = "X-E2E-Journey"

# A repo path (`e2e/web/tests/chat.spec.ts`, `e2e/mobile/flows/01-login-chat.yaml`): anything else
# is ignored rather than written into a context label or a file name.
_JOURNEY = re.compile(r"^[A-Za-z0-9_./-]{1,200}$")

current_journey: ContextVar[str | None] = ContextVar("e2e_journey", default=None)


def enabled() -> bool:
    return os.environ.get("E2E_COVERAGE") == "1"


def valid_journey(value: str | None) -> str | None:
    """The journey id if it is one, None otherwise."""
    if value and _JOURNEY.match(value) and ".." not in value:
        return value
    return None


def slug(journey: str) -> str:
    """The raw file's name for a journey: `/` and `.` become `_` (the spec's contract)."""
    return journey.replace("/", "_").replace(".", "_")


def journey_headers() -> dict[str, str]:
    """The header to send on to the API, when the current work belongs to a journey."""
    journey = current_journey.get()
    return {HEADER: journey} if journey else {}


class JourneyMiddleware:
    """Puts the request's `X-E2E-Journey` into `current_journey` for everything it runs.

    Pure ASGI rather than `BaseHTTPMiddleware`, so the variable is set in the very task that runs
    the endpoint and the streaming body, and inherited by the tasks they start.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return

        journey = None
        wanted = HEADER.lower().encode()
        for name, value in scope.get("headers", []):
            if name == wanted:
                journey = valid_journey(value.decode("latin-1"))
                break

        token = current_journey.set(journey)
        try:
            await self.app(scope, receive, send)
        finally:
            current_journey.reset(token)


def setup_journey_coverage(app: FastAPI) -> None:
    if enabled():
        app.add_middleware(JourneyMiddleware)

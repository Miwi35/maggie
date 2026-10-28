"""Maggie's proactions, read and written through the API (ADR-012, MAG-327).

A proaction is an event of the user's internal « Maggie » agenda, served by the API's MCP
tools (MAG-326). The model reaches `schedule_proaction`, `list_proactions` and
`cancel_proaction` like any other MCP tool; this module is what the agent's own code needs
— the executor, the manual triggers and the move out of the old `proaction` table.
"""

import json
import logging
from dataclasses import dataclass
from datetime import datetime

from app.mcp.client import NO_RESPONSE_ERROR, mcp_client
from app.trips import is_silent_trip_run

logger = logging.getLogger(__name__)

CLAIM_TOOL = "claim_due_proactions"
RECORD_TOOL = "record_proaction_result"
LIST_TOOL = "list_proactions"
SCHEDULE_TOOL = "schedule_proaction"


class ProactionApiError(RuntimeError):
    """The API answered with an error, or not at all: nothing was claimed, read or recorded."""


class ProactionApiUnreachable(ProactionApiError):
    """The API did not answer: the same call may work a minute later."""


@dataclass(frozen=True)
class Proaction:
    """One proaction as the API describes it (`Proactions::describe`)."""

    id: str
    user_id: str
    prompt: str
    status: str
    scheduled_at: str | None = None
    summary: str | None = None
    response: str | None = None
    error: str | None = None
    completed_at: str | None = None

    @classmethod
    def from_api(cls, data: dict, user_id: str | None = None) -> "Proaction":
        return cls(
            id=str(data["id"]),
            user_id=str(data.get("userId") or user_id or ""),
            prompt=str(data.get("prompt") or ""),
            status=str(data.get("status") or ""),
            scheduled_at=data.get("scheduledAt"),
            summary=data.get("summary"),
            response=data.get("response"),
            error=data.get("error"),
            completed_at=data.get("completedAt"),
        )

    def to_dict(self) -> dict:
        """What `GET /agent/proactions` and the `proactions` Mercure topic carry: their former shape, but
        `createdAt`, which the API does not describe (the screens move to the agenda in MAG-328).

        `silent` (MAG-680) tells the screens the run is a log, not a message: a trip check or replan."""
        return {
            "id": self.id,
            "userId": self.user_id,
            "prompt": self.prompt,
            "summary": self.summary,
            "status": self.status,
            "scheduledAt": self.scheduled_at,
            "response": self.response,
            "error": self.error,
            "completedAt": self.completed_at,
            "silent": is_silent_trip_run(self.summary),
        }


async def _call(tool: str, arguments: dict, user_id: str | None) -> dict:
    # The executor may be the only caller for hours: an API that was down at startup, or a
    # session gone stale overnight, is reconnected here rather than by the next chat.
    await mcp_client.ensure_connected()
    raw = await mcp_client.call_tool(tool, arguments, user_id=user_id)
    try:
        data = json.loads(raw)
    except (TypeError, ValueError) as e:
        raise ProactionApiError(f"{tool}: unreadable answer {raw!r}") from e
    if not isinstance(data, dict):
        raise ProactionApiError(f"{tool}: unexpected answer {raw!r}")
    if data.get("error") == NO_RESPONSE_ERROR:
        raise ProactionApiUnreachable(f"{tool}: {NO_RESPONSE_ERROR}")
    if "error" in data:
        raise ProactionApiError(f"{tool}: {data['error']}")
    return data


async def claim_due(proaction_id: str | None = None, user_id: str | None = None) -> list[Proaction]:
    """Claim the due proactions (pending → running, atomically on the API's side).

    Without `user_id`, the service token claims every user's. With `proaction_id`, that one
    pending proaction is claimed now, due or not — the manual trigger (MAG-249).
    """
    arguments = {"id": proaction_id} if proaction_id else {}
    data = await _call(CLAIM_TOOL, arguments, user_id)
    return [Proaction.from_api(p) for p in data.get("proactions", [])]


async def record(
    proaction: Proaction, status: str, *, response: str | None = None, error: str | None = None
) -> Proaction:
    """Record how a claimed run went: `completed` with Maggie's message, or `failed` with the error."""
    arguments: dict = {"id": proaction.id, "status": status}
    if response is not None:
        arguments["response"] = response
    if error is not None:
        arguments["error"] = error
    data = await _call(RECORD_TOOL, arguments, proaction.user_id or None)
    return Proaction.from_api(data.get("proaction") or {"id": proaction.id}, proaction.user_id)


async def list_for_user(user_id: str) -> list[Proaction]:
    """Every one of the user's proactions, by time."""
    data = await _call(LIST_TOOL, {}, user_id)
    return [Proaction.from_api(p, user_id) for p in data.get("proactions", [])]


async def schedule(user_id: str, prompt: str, scheduled_at: datetime) -> Proaction:
    data = await _call(SCHEDULE_TOOL, {"prompt": prompt, "scheduled_at": scheduled_at.isoformat()}, user_id)
    return Proaction.from_api(data["proaction"], user_id)

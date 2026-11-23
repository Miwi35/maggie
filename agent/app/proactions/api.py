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
CANCEL_TOOL = "cancel_proaction"
RENDEZVOUS_TOOL = "list_daily_rendezvous"


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
    # A reminder always interrupts; a check is triaged (MAG-886), knowing the topics it already told.
    kind: str = "reminder"
    delivery: str | None = None
    signaled_topics: tuple[dict, ...] = ()
    checked_values: tuple[dict, ...] = ()
    # The owner's words it was asked in (MAG-977): `{kind, ref}`, read by the checker of its run.
    origin: dict | None = None

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
            kind=str(data.get("kind") or "reminder"),
            delivery=data.get("delivery"),
            signaled_topics=tuple(t for t in data.get("signaledTopics") or () if isinstance(t, dict)),
            checked_values=tuple(v for v in data.get("checkedValues") or () if isinstance(v, dict)),
            origin=data.get("origin") if isinstance(data.get("origin"), dict) else None,
        )

    def to_dict(self) -> dict:
        """What `GET /agent/proactions` and the `proactions` Mercure topic carry: their former shape, but
        `createdAt`, which the API does not describe (the screens move to the agenda in MAG-328).

        `silent` (MAG-680) tells the screens the run is a log, not a message: a trip check or replan —
        or a run whose message waits in the thread, or that said nothing (MAG-886): no bubble, no push.

        `kind`, `signaledTopics`, `checkedValues` and `origin` travel with the claimed proaction through the queue to its run."""
        data = {
            "id": self.id,
            "userId": self.user_id,
            "prompt": self.prompt,
            "summary": self.summary,
            "status": self.status,
            "scheduledAt": self.scheduled_at,
            "response": self.response,
            "error": self.error,
            "completedAt": self.completed_at,
            "kind": self.kind,
            "delivery": self.delivery,
            "silent": is_silent_trip_run(self.summary) or (self.delivery is not None and self.delivery != "interrupt"),
        }
        if self.signaled_topics:
            data["signaledTopics"] = list(self.signaled_topics)
        if self.checked_values:
            data["checkedValues"] = list(self.checked_values)
        if self.origin is not None:
            data["origin"] = self.origin
        return data


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
    proaction: Proaction,
    status: str,
    *,
    response: str | None = None,
    error: str | None = None,
    delivery: str | None = None,
    topic_key: str | None = None,
    verdict: dict | None = None,
    notice: str | None = None,
    message_link: str | None = None,
) -> Proaction:
    """Record how a claimed run went: `completed` with Maggie's message, or `failed` with the error.

    A completed run also keeps how it reached the owner, its topic and verdict (MAG-886); an
    interruption with its `notice` and `message_link` is what makes the API notify the owner.
    """
    arguments: dict = {"id": proaction.id, "status": status}
    optional = {
        "response": response,
        "error": error,
        "delivery": delivery,
        "topic_key": topic_key,
        "notice": notice,
        "message_link": message_link,
        **(verdict or {}),
    }
    arguments.update({key: value for key, value in optional.items() if value is not None})
    data = await _call(RECORD_TOOL, arguments, proaction.user_id or None)
    return Proaction.from_api(data.get("proaction") or {"id": proaction.id}, proaction.user_id)


async def list_for_user(user_id: str, from_: datetime | None = None, to: datetime | None = None) -> list[Proaction]:
    """The user's proactions by time: every one, or those scheduled between `from_` and `to`."""
    arguments = {}
    if from_ is not None:
        arguments["from"] = from_.isoformat()
    if to is not None:
        arguments["to"] = to.isoformat()
    data = await _call(LIST_TOOL, arguments, user_id)
    return [Proaction.from_api(p, user_id) for p in data.get("proactions", [])]


async def schedule(user_id: str, prompt: str, scheduled_at: datetime, summary: str | None = None) -> Proaction:
    arguments = {"prompt": prompt, "scheduled_at": scheduled_at.isoformat()}
    if summary is not None:
        arguments["summary"] = summary
    data = await _call(SCHEDULE_TOOL, arguments, user_id)
    return Proaction.from_api(data["proaction"], user_id)


async def cancel(user_id: str, proaction_id: str) -> None:
    await _call(CANCEL_TOOL, {"id": proaction_id}, user_id)


async def list_daily_rendezvous(user_id: str | None = None) -> list[dict]:
    """Every user's morning digest and evening review settings (MAG-787), read with the service token; or one's."""
    data = await _call(RENDEZVOUS_TOOL, {}, user_id)
    return [u for u in data.get("users", []) if isinstance(u, dict)]

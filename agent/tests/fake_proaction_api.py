"""The API's proaction tools, simulated in memory (MAG-327) — and the agenda the planning reads.

Stands in for the MCP server behind `mcp_client`: `schedule_proaction`, `list_proactions`,
`cancel_proaction`, `claim_due_proactions` and `record_proaction_result` behave as the
API's do (`api/modules/calendar/src/Service/Proactions.php`) — user-scoped, a claim taken
once, a result only for a running one, the same pending proaction never scheduled twice.
`down = True` answers like an API that cannot be reached.
"""

import itertools
import json
from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from app.mcp.client import NO_RESPONSE_ERROR

PARIS = ZoneInfo("Europe/Paris")


class FakeProactionApi:
    def __init__(self, now: datetime | None = None):
        self.now = now or datetime(2030, 11, 13, 11, 0, tzinfo=UTC)
        self.proactions: dict[str, dict] = {}
        self.calls: list[tuple[str, dict, str | None]] = []
        self.down = False
        # `get_events_by_date`'s answer, by day: the agenda the planning reads.
        self.agenda: dict[str, list[dict]] = {}
        self._ids = (f"01JPROACTION{n:014d}" for n in itertools.count(1))

    # What `McpClient` offers the code under test.

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments), user_id))
        if self.down:
            return json.dumps({"error": NO_RESPONSE_ERROR})
        handler = getattr(self, f"_{name}", None)
        if handler is None:
            return json.dumps({"error": f"unknown tool {name}"})
        return json.dumps(handler(arguments, user_id))

    # Test helpers.

    def seed(self, user_id: str, *, prompt: str = "Rappelle-moi le plombier", at: str = "2030-11-13T12:30:00+01:00", status: str = "pending") -> str:
        proaction_id = next(self._ids)
        self.proactions[proaction_id] = {
            "id": proaction_id,
            "prompt": prompt,
            "summary": prompt[:60],
            "scheduledAt": at,
            "status": status,
            "response": None,
            "error": None,
            "completedAt": None,
            "userId": user_id,
        }
        return proaction_id

    def of(self, user_id: str) -> list[dict]:
        return sorted((p for p in self.proactions.values() if p["userId"] == user_id), key=lambda p: p["scheduledAt"])

    def snapshot(self) -> dict:
        return json.loads(json.dumps(self.proactions))

    # The tools.

    @staticmethod
    def _instant(value: str) -> datetime:
        # Read in Paris without an offset, like the API (`Proaction::FALLBACK_TIME_ZONE`).
        at = datetime.fromisoformat(value)
        return at if at.tzinfo else at.replace(tzinfo=PARIS)

    def _schedule_proaction(self, arguments: dict, user_id: str | None) -> dict:
        if not user_id:
            return {"error": "No user bound to this MCP call."}
        prompt = str(arguments.get("prompt", "")).strip()
        if not prompt:
            return {"error": "prompt is required: what Maggie does when the proaction runs."}
        try:
            at = self._instant(str(arguments.get("scheduled_at", "")))
        except ValueError:
            return {"error": f"Not a date and time: \"{arguments.get('scheduled_at')}\"."}
        if at <= self.now:
            return {"error": f"scheduled_at {at.isoformat()} has already passed: a proaction runs in the future."}
        for twin in self.of(user_id):
            if twin["status"] == "pending" and twin["prompt"] == prompt and self._instant(twin["scheduledAt"]) == at:
                return {"success": True, "proaction": twin, "agenda": "Maggie"}
        proaction_id = self.seed(user_id, prompt=prompt, at=at.isoformat())
        return {"success": True, "proaction": self.proactions[proaction_id], "agenda": "Maggie"}

    def _list_proactions(self, arguments: dict, user_id: str | None) -> dict:
        if not user_id:
            return {"error": "No user bound to this MCP call."}
        listed = [
            p
            for p in self.of(user_id)
            if (not arguments.get("from") or p["scheduledAt"][:10] >= arguments["from"][:10])
            and (not arguments.get("to") or p["scheduledAt"][:10] <= arguments["to"][:10])
            and (not arguments.get("status") or p["status"] == arguments["status"])
        ]
        return {"proactions": listed, "count": len(listed)}

    def _get_events_by_date(self, arguments: dict, user_id: str | None) -> dict:
        events = self.agenda.get(str(arguments.get("date")), [])
        return {"date": arguments.get("date"), "events": events, "count": len(events)}

    def _cancel_proaction(self, arguments: dict, user_id: str | None) -> dict:
        proaction = self.proactions.get(str(arguments.get("id")))
        if proaction is None or proaction["userId"] != user_id:
            return {"error": f"Proaction not found: {arguments.get('id')}"}
        del self.proactions[proaction["id"]]
        return {"success": True, "message": "Proaction cancelled."}

    def _claim_due_proactions(self, arguments: dict, user_id: str | None) -> dict:
        wanted = arguments.get("id")
        claimed = []
        for proaction in sorted(self.proactions.values(), key=lambda p: self._instant(p["scheduledAt"])):
            if proaction["status"] != "pending" or (user_id and proaction["userId"] != user_id):
                continue
            if wanted and proaction["id"] != wanted:
                continue
            if not wanted and self._instant(proaction["scheduledAt"]) > self.now:
                continue
            proaction["status"] = "running"
            claimed.append(dict(proaction))
        return {"proactions": claimed, "count": len(claimed)}

    def _record_proaction_result(self, arguments: dict, user_id: str | None) -> dict:
        proaction = self.proactions.get(str(arguments.get("id")))
        if proaction is None or (user_id and proaction["userId"] != user_id):
            return {"error": f"Proaction not found: {arguments.get('id')}"}
        if proaction["status"] != "running":
            return {"error": f"Proaction {proaction['id']} is {proaction['status']}, not running."}
        status = arguments.get("status")
        if status not in ("completed", "failed"):
            return {"error": "status is completed or failed."}
        proaction["status"] = status
        proaction["response"] = arguments.get("response") if status == "completed" else None
        proaction["error"] = (arguments.get("error") or "Unknown error.") if status == "failed" else None
        proaction["completedAt"] = self.now.isoformat()
        return {"success": True, "proaction": dict(proaction)}

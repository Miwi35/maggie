"""The app's data as the MCP server answers it, for the habits pass (MAG-18): the same shapes, none of the network."""

import asyncio
import json
from typing import Any

HABITS_TOOLS = ("get_events_by_date", "get_upcoming_events", "list_past_errands", "manage_meals", "manage_transactions")


class FakeMcp:
    """`events` by day (ISO date), `upcoming` events, `errands`, `meals`, `transactions`; `down` = tools that fail."""

    def __init__(
        self,
        *,
        events: dict[str, list[dict[str, Any]]] | None = None,
        upcoming: list[dict[str, Any]] | None = None,
        errands: list[dict[str, Any]] | None = None,
        meals: list[dict[str, Any]] | None = None,
        transactions: list[dict[str, Any]] | None = None,
        down: tuple[str, ...] = (),
    ):
        self.events = events or {}
        self.upcoming = upcoming or []
        self.errands = errands or []
        self.meals = meals or []
        self.transactions = transactions or []
        self.down = set(down)
        self.calls: list[tuple[str, dict[str, Any], str | None]] = []
        self.in_flight = 0
        self.overlapped = False
        self.forks: list[FakeMcp] = []

    async def fork(self) -> "FakeMcp":
        other = FakeMcp(
            events=self.events,
            upcoming=self.upcoming,
            errands=self.errands,
            meals=self.meals,
            transactions=self.transactions,
            down=tuple(self.down),
        )
        other.calls = self.calls
        self.forks.append(other)
        return other

    async def disconnect(self) -> None:
        return None

    async def ensure_connected(self) -> None:
        return None

    async def call_tool(self, name: str, arguments: dict[str, Any], user_id: str | None = None) -> str:
        self.calls.append((name, arguments, user_id))
        self.overlapped = self.overlapped or self.in_flight > 0
        self.in_flight += 1
        try:
            await asyncio.sleep(0)
            return self._answer(name, arguments)
        finally:
            self.in_flight -= 1

    def _answer(self, name: str, arguments: dict[str, Any]) -> str:
        if name in self.down:
            return json.dumps({"error": "No response from MCP server"})
        if name == "get_events_by_date":
            day = arguments["date"]
            return json.dumps({"date": day, "events": self.events.get(day, [])})
        if name == "get_upcoming_events":
            return json.dumps({"events": self.upcoming, "count": len(self.upcoming)})
        if name == "list_past_errands":
            return json.dumps({"errands": self.errands, "count": len(self.errands)})
        if name == "manage_meals":
            return json.dumps({"meals": self.meals})
        if name == "manage_transactions":
            return json.dumps({"transactions": self.transactions, "total": len(self.transactions)})
        return json.dumps({"error": f"unknown tool {name}"})

    def called(self, name: str) -> int:
        return sum(1 for call in self.calls if call[0] == name)


def timed_event(day: str, summary: str, *, hour: str = "20:00", agenda: str = "Défaut", location: str | None = None):
    return {
        "id": f"{agenda}-{day}-{summary}",
        "summary": summary,
        "location": location,
        "allDay": False,
        "startAt": f"{day}T{hour}:00+02:00",
        "endAt": f"{day}T23:00:00+02:00",
        "status": "confirmed",
        "agenda": agenda,
        "recurring": False,
        "rrule": None,
    }


def all_day_event(day: str, summary: str, *, agenda: str = "Concerts", location: str | None = None):
    return {
        "id": f"{agenda}-{day}-{summary}",
        "summary": summary,
        "location": location,
        "allDay": True,
        "startDate": day,
        "endDate": day,
        "status": "confirmed",
        "agenda": agenda,
        "recurring": False,
        "rrule": None,
    }


def errand(ended_at: str, store: str = "Carrefour", items: tuple[str, ...] = ("Lait", "Pain")):
    return {
        "id": f"errand-{ended_at}",
        "storeName": store,
        "endedAt": ended_at,
        "itemCount": len(items),
        "items": [{"label": label} for label in items],
    }

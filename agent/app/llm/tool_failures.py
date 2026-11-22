"""When a tool keeps failing, and what the model is told about a call it got wrong (MAG-872).

The loop does not speak for Maggie: at the third failure in a row of the same tool it hands
the model a note and one last call without tools, and the answer is the model's own.
"""

import json
from typing import Any

from app.error_tracking import TOOL_GAVE_UP, capture_signal

PENDING_APPROVAL_STATUS = "pending_approval"
TOOL_FAILURE_LIMIT = 3
_REASON_MAX_CHARS = 300


def tool_result_status(result: str) -> str:
    """How a tool call ended, as the Mind panel and the thread's activity log read it.

    `pending_approval` is a third outcome, not a failure: the policy held the call back
    and the user has the card in front of them (MAG-4). Drawing it as an error would say
    Maggie tried and could not, which is the opposite of « Propose, never impose ».
    """
    try:
        data = json.loads(result)
    except (json.JSONDecodeError, TypeError):
        return "error" if '"error"' in result else "success"

    if not isinstance(data, dict):
        return "success"
    if data.get("status") == PENDING_APPROVAL_STATUS:
        return PENDING_APPROVAL_STATUS
    return "error" if "error" in data else "success"


def invalid_input(tool: str, fields: list[dict], schema: dict | None) -> str:
    """The result of a call whose arguments are wrong: the faulty fields and the expected form."""
    result: dict[str, Any] = {"error": "invalid_input", "tool": tool, "fields": fields}
    if schema is not None:
        result["expected"] = schema
    return json.dumps(result, ensure_ascii=False)


def _reason(result: str) -> str:
    try:
        data = json.loads(result)
    except (json.JSONDecodeError, TypeError):
        data = None
    if isinstance(data, dict) and "error" in data:
        text = str(data["error"])
        if data.get("fields"):
            text += " " + json.dumps(data["fields"], ensure_ascii=False)
    else:
        text = str(result)
    return text[:_REASON_MAX_CHARS]


class FailureStreak:
    """Consecutive failures per tool in one turn: a success resets that tool's count only."""

    def __init__(self) -> None:
        self._counts: dict[str, int] = {}
        self._reasons: dict[str, str] = {}

    def record(self, name: str, result: str) -> None:
        if tool_result_status(result) == "error":
            self._counts[name] = self._counts.get(name, 0) + 1
            self._reasons[name] = _reason(result)
        elif name in self._counts:
            self._counts[name] = 0

    def gave_up(self) -> str | None:
        for name, count in self._counts.items():
            if count >= TOOL_FAILURE_LIMIT:
                return name
        return None

    def note(self, user_id: str | None, source: str) -> dict:
        """The note to the model, as a text block of the round's results, and the signal."""
        tool = self.gave_up() or ""
        capture_signal(TOOL_GAVE_UP, tool=tool, user_id=user_id, source=source)
        text = (
            f"L'outil {tool} a échoué {self._counts.get(tool, 0)} fois de suite dans ce tour. "
            f"Dernière erreur : {self._reasons.get(tool, '')}. Ne l'appelle plus. "
            "Réponds maintenant au propriétaire, avec tes mots et sans détail technique : "
            "ce qui n'a pas pu être fait, et pourquoi."
        )
        return {"type": "text", "text": text}

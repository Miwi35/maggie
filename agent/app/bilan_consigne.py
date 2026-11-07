"""The Consignes step of the evening review, done by code instead of by the model's memory (MAG-753).

Three rounds of skill text could not make the model add the provenance, call `add_instruction` or
hand `targetId` to `save_bilan_step`. One tool now classes the consigne, creates it with its
provenance and writes it into the bilan's `instructions`.
"""

import json
import logging

from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

PROVENANCE = "bilan-soir"
CONSIGNE_KINDS = ["reminder", "task", "instruction"]

BILAN_CONSIGNE_TOOL = {
    "name": "add_bilan_consigne",
    "description": (
        "Evening review, Consignes step: record ONE consigne the user dictated, in one call. "
        "kind 'reminder' = something to do or remember at a given time tomorrow (needs scheduled_at); "
        "'task' = something to do tomorrow without a time (needs due_date), e.g. 'acheter du pain' "
        "(a task, never a grocery item); 'instruction' = a rule that lasts or comes back "
        "('le mardi, je pars plus tôt', 'toujours…'). The tool creates the reminder, the task or the learned "
        "instruction with the provenance 'bilan-soir' and writes it into the review's status: call it once per "
        "consigne, do NOT call schedule_proaction, create_task, add_instruction or save_bilan_step for it."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "day": {"type": "string", "description": "Day of the review, YYYY-MM-DD (today)"},
            "kind": {"type": "string", "enum": CONSIGNE_KINDS},
            "text": {
                "type": "string",
                "description": "The consigne in the user's words, short ('Appeler le garage', 'Acheter du pain')",
            },
            "scheduled_at": {
                "type": "string",
                "description": "reminder only: tomorrow, ISO 8601 with offset, e.g. 2030-11-15T09:00:00+01:00",
            },
            "due_date": {"type": "string", "description": "task only: tomorrow, YYYY-MM-DD"},
            "instruction_kind": {
                "type": "string",
                "enum": [kind.value for kind in InstructionKind],
                "description": "instruction only: 'planning' (default) for a date rule, 'behavior' for tone",
            },
        },
        "required": ["day", "kind", "text"],
    },
}


def _loads(raw: str) -> dict:
    try:
        data = json.loads(raw)
    except (TypeError, ValueError):
        return {"error": f"unreadable answer: {raw!r}"}
    return data if isinstance(data, dict) else {}


async def _create(arguments: dict, user_id: str, kind: str, text: str) -> tuple[str | None, str | None]:
    """The id of what was created, or the error."""
    if kind == "reminder":
        scheduled_at = arguments.get("scheduled_at")
        if not scheduled_at:
            return None, "'scheduled_at' is required for a reminder"
        created = _loads(
            await mcp_client.call_tool(
                "schedule_proaction",
                {
                    "prompt": f"Rappel à délivrer : {text} ({PROVENANCE})",
                    "scheduled_at": scheduled_at,
                    "summary": f"{text} ({PROVENANCE})",
                },
                user_id=user_id,
            )
        )
        target = (created.get("proaction") or {}).get("id")
    elif kind == "task":
        due_date = arguments.get("due_date")
        if not due_date:
            return None, "'due_date' is required for a task"
        created = _loads(
            await mcp_client.call_tool(
                "create_task",
                {"title": text, "dueDate": due_date, "description": f"Provenance : {PROVENANCE}"},
                user_id=user_id,
            )
        )
        target = (created.get("task") or {}).get("id")
    else:
        raw_kind = str(arguments.get("instruction_kind") or "planning").strip().lower()
        try:
            instruction_kind = InstructionKind(raw_kind)
        except ValueError:
            return None, f"'instruction_kind' must be one of {[k.value for k in InstructionKind]}"
        stored = await instruction_repo.store(user_id, f"{text} ({PROVENANCE})", kind=instruction_kind)
        return str(stored.to_dict()["id"]), None
    if created.get("error") or not target:
        return None, str(created.get("error") or "the API did not return an id")
    return str(target), None


async def handle_add_bilan_consigne(arguments: dict, user_id: str) -> str:
    day = str(arguments.get("day") or "").strip()
    kind = str(arguments.get("kind") or "").strip().lower()
    text = str(arguments.get("text") or "").strip()
    if not day or not text or kind not in CONSIGNE_KINDS:
        return json.dumps({"error": f"'day', 'text' and 'kind' (one of {CONSIGNE_KINDS}) are required"})

    target_id, error = await _create(arguments, user_id, kind, text)
    if error or target_id is None:
        return json.dumps({"error": error, "created": False})

    status = _loads(await mcp_client.call_tool("get_activity_status", {"activity": "bilan"}, user_id=user_id))
    state = status.get("state") or {}
    same_day = (status.get("origin") or {}).get("id") == day
    instructions = list(state.get("instructions") or []) if same_day else []
    entry = {"id": str(len(instructions) + 1), "text": text, "kind": kind, "targetId": target_id}
    instructions.append(entry)

    saved = _loads(
        await mcp_client.call_tool(
            "save_bilan_step",
            {"day": day, "step": "consignes", "instructions": instructions},
            user_id=user_id,
        )
    )
    if saved.get("error"):
        logger.warning(f"Consigne {target_id} created but the bilan status was refused: {saved['error']}")
        return json.dumps({"created": True, "consigne": entry, "error": f"status not saved: {saved['error']}"})
    return json.dumps({"created": True, "consigne": entry, "instructions": len(instructions)})

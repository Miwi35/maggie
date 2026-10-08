"""The sentence a validation card shows (MAG-7).

A held call is `delete_event` and a ULID: nothing a person can answer. The sentence is
built once, when the action is held, from the French label of the action and the title of
the object it aims at, read through a read tool. It is stored with the action, so the web
and the phone show the same words without resolving any identifier themselves.

Whatever happens here, no identifier and no tool name reaches the sentence: a title that
cannot be read leaves the label alone.
"""

import asyncio
import json
import logging
from datetime import datetime

from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

LOOKUP_TIMEOUT_SECONDS = 5

GENERIC_LABEL = "Action en attente de validation"
UNKNOWN_OBJECT = "un élément"

_MONTHS = (
    "janvier",
    "février",
    "mars",
    "avril",
    "mai",
    "juin",
    "juillet",
    "août",
    "septembre",
    "octobre",
    "novembre",
    "décembre",
)

# What each deletable thing is called, with its article.
_DELETE_TOOLS = {
    "delete_event": "l'événement",
    "delete_recipe": "la recette",
    "delete_task": "la tâche",
    "delete_skill": "la compétence",
}

_MANAGE_OBJECTS = {
    "manage_accounts": "le compte",
    "manage_agendas": "l'agenda",
    "manage_categories": "la catégorie",
    "manage_categorization_rules": "la règle de catégorisation",
    "manage_envelopes": "l'enveloppe",
    "manage_ingredients": "l'ingrédient",
    "manage_loans": "le prêt",
    "manage_meals": "le repas",
    "manage_notifications": "la notification",
    "manage_products": "le produit",
    "manage_recurring_groceries": "la course récurrente",
    "manage_recurring_operations": "l'opération récurrente",
    "manage_stores": "le magasin",
    "manage_transactions": "la transaction",
}

# `manage_*` tools whose `list` action answers without arguments and stays small enough to
# read. The others (a period to give, a whole history) keep to the label.
_NO_LIST_LOOKUP = frozenset({"manage_envelopes", "manage_meals", "manage_transactions"})

_TITLE_KEYS = ("name", "title", "label", "customLabel", "summary")


def _is_delete(tool_name: str, arguments: dict) -> bool:
    if tool_name.startswith("delete_"):
        return True
    return tool_name.startswith("manage_") and str(arguments.get("action", "")).lower() == "delete"


def _object_phrase(tool_name: str) -> str:
    return _DELETE_TOOLS.get(tool_name) or _MANAGE_OBJECTS.get(tool_name) or UNKNOWN_OBJECT


def action_label(tool_name: str, arguments: dict | None) -> str:
    """The action in French, naming no tool and no identifier."""
    if _is_delete(tool_name, arguments or {}):
        return f"Supprimer {_object_phrase(tool_name)}"
    return GENERIC_LABEL


def _target_id(arguments: dict) -> str | None:
    """The identifier the call aims at: `id`, or the first argument spelled `…Id`."""
    for key, value in arguments.items():
        if isinstance(value, str) and value and (key == "id" or key.endswith("Id")):
            return value
    return None


def _parse(raw: str) -> object:
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return None


def _find_title(node: object, wanted: str) -> str | None:
    """The title of the record carrying this id, wherever it sits in a tool's answer."""
    if isinstance(node, dict):
        found = node.get("id")
        if isinstance(found, str) and found.lower() == wanted.lower():
            for key in _TITLE_KEYS:
                title = node.get(key)
                if isinstance(title, str) and title.strip():
                    return title.strip()
        return next((t for child in node.values() if (t := _find_title(child, wanted))), None)
    if isinstance(node, list):
        return next((t for child in node if (t := _find_title(child, wanted))), None)
    return None


def _when(event: dict) -> str | None:
    start = event.get("startAt")
    if not isinstance(start, str):
        return None
    try:
        moment = datetime.fromisoformat(start)
    except ValueError:
        return None
    day = f"le {moment.day} {_MONTHS[moment.month - 1]}"
    if moment.year != datetime.now(moment.tzinfo).year:
        day += f" {moment.year}"
    return day if event.get("allDay") else f"{day} à {moment:%H:%M}"


async def _read(tool_name: str, arguments: dict, user_id: str) -> object:
    raw = await asyncio.wait_for(
        mcp_client.call_tool(tool_name, arguments, user_id=user_id), timeout=LOOKUP_TIMEOUT_SECONDS
    )
    return _parse(raw)


async def _lookup(tool_name: str, arguments: dict, user_id: str) -> tuple[str | None, str | None]:
    """(title, when) of the object a delete aims at, read through a read tool; (None, None) if unknown."""
    if tool_name == "delete_skill":
        name = arguments.get("name")
        return (name if isinstance(name, str) and name else None), None

    target = _target_id(arguments)
    if target is None:
        return None, None

    if tool_name == "delete_event":
        answer = await _read("get_event", {"id": target}, user_id)
        event = answer.get("event") if isinstance(answer, dict) else None
        if not isinstance(event, dict) or not isinstance(event.get("summary"), str):
            return None, None
        return event["summary"].strip() or None, _when(event)

    if tool_name == "delete_recipe":
        return _find_title(await _read("get_recipe", {"recipeId": target}, user_id), target), None

    if tool_name == "delete_task":
        return _find_title(await _read("get_tasks", {"status": "all"}, user_id), target), None

    if tool_name in _MANAGE_OBJECTS and tool_name not in _NO_LIST_LOOKUP:
        return _find_title(await _read(tool_name, {"action": "list"}, user_id), target), None

    return None, None


async def build_summary(tool_name: str, arguments: dict | None, user_id: str) -> str:
    """« Supprimer l'événement « Test validation » — le 8 octobre à 10:00 », or just the label.

    Never raises: a card without a title is still a card the user can answer.
    """
    arguments = arguments or {}
    label = action_label(tool_name, arguments)
    if not _is_delete(tool_name, arguments):
        return label

    try:
        title, when = await _lookup(tool_name, arguments, user_id)
    except Exception as e:
        logger.warning(f"Could not read the title of the object {tool_name} aims at: {e}")
        return label

    if not title:
        return label
    sentence = f"{label} « {title} »"
    return f"{sentence} — {when}" if when else sentence

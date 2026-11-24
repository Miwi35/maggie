"""The trace of origin of a proaction: the owner's words it was asked in (part D of the agent spec, MAG-977).

Only the agent's code sets it — a model that chose its source could quote any message. The model is never offered
the field, and whatever it writes there is dropped before the call leaves `ToolRouter`.
"""

import logging

from app.proactions.api import SCHEDULE_TOOL

logger = logging.getLogger(__name__)

ORIGIN_FIELD = "origin"
ORIGIN_TOOLS = frozenset({SCHEDULE_TOOL})
OWNER_MESSAGE = "owner_message"


def owner_message_origin(message_id: str) -> dict:
    return {"kind": OWNER_MESSAGE, "ref": message_id}


def hide_origin(name: str, schema: dict) -> dict:
    """The tool's schema as the model sees it: without `origin`."""
    if name not in ORIGIN_TOOLS or ORIGIN_FIELD not in (schema.get("properties") or {}):
        return schema
    hidden = {**schema, "properties": {k: v for k, v in schema["properties"].items() if k != ORIGIN_FIELD}}
    if "required" in schema:
        hidden["required"] = [f for f in schema["required"] if f != ORIGIN_FIELD]
    return hidden


def stamp_origin(name: str, arguments: dict, origin: dict | None) -> dict:
    """The arguments as they leave: the model's `origin` dropped, the code's set when there is one."""
    if name not in ORIGIN_TOOLS:
        return arguments
    stamped = {k: v for k, v in (arguments or {}).items() if k != ORIGIN_FIELD}
    if ORIGIN_FIELD in (arguments or {}):
        logger.warning("The model wrote an origin on %s: dropped", name)
    if origin is not None:
        stamped[ORIGIN_FIELD] = origin
    return stamped


class OriginToolRouter:
    """A ToolRouter whose calls carry the origin of the turn: the owner's message that opened it."""

    def __init__(self, router, origin: dict):
        self._router = router
        self._origin = origin

    async def get_tool_definitions(self, *args, **kwargs) -> list[dict]:
        return await self._router.get_tool_definitions(*args, **kwargs)

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
    ) -> str:
        return await self._router.call_tool(
            name, arguments, user_id=user_id, source=source, context_id=context_id, origin=self._origin
        )

"""Holds a write for the owner's « Autoriser / Refuser » card, the same answer as the policy's `ask` (MAG-943)."""

import json
import logging

from app.db.pending_action_repository import pending_action_repo
from app.llm.tools import PENDING_APPROVAL_MESSAGE
from app.policy.summary import GENERIC_LABEL, REASON_SEPARATOR, build_summary, title_of, write_sentence

logger = logging.getLogger(__name__)


async def hold_for_approval(
    name: str,
    arguments: dict,
    user_id: str | None,
    source: str,
    context_id: str | None,
    *,
    reason: str | None = None,
) -> str:
    if user_id is None:
        logger.error(f"Tool call needing approval has no user to ask: {name}")
        return json.dumps({"error": "user_id required to request approval"})

    try:
        summary = await build_summary(name, arguments or {}, user_id)
        if summary == GENERIC_LABEL:
            title = title_of(arguments or {})
            summary = write_sentence(name, arguments) or (f"{GENERIC_LABEL} — « {title} »" if title else summary)
        if reason:
            summary += REASON_SEPARATOR + reason
    except Exception as e:
        logger.warning(f"Could not build the summary of {name}: {e}")
        summary = None

    try:
        action = await pending_action_repo.create(
            user_id, name, arguments or {}, source=source, context_id=context_id, summary=summary
        )
    except Exception as e:
        logger.error(f"Could not hold {name} for approval: {e}")
        return json.dumps({"error": "L'action n'a pas pu être soumise à validation"})

    return json.dumps(
        {"status": "pending_approval", "approval_id": str(action.id), "message": PENDING_APPROVAL_MESSAGE}
    )

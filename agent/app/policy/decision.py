"""The trace of the user's answer to a request of Maggie's (MAG-336).

A held action is answered on the phone's card, the admin's card, the assistant window, by
voice or from a notification — and each of them removes its card. The agent is the one place
all of those pass through, so the line that keeps the answer in the thread is written here,
once, and published on the chat topic like any message: every client shows it, none writes it.
"""

import logging

from app.db.message_repository import message_repo
from app.db.models import DECISION_ROLE, Message
from app.db.pending_action_model import PendingAction, PendingActionStatus

logger = logging.getLogger(__name__)

CHANNELS = ("chat", "notification", "voice")
_CHANNEL_NAMES = {"chat": "chat", "notification": "notification", "voice": "voix"}

APPROVED = "approved"
DENIED = "denied"
EXPIRED = "expired"

_ANSWERS = {
    PendingActionStatus.APPROVED: APPROVED,
    PendingActionStatus.FAILED: APPROVED,
    PendingActionStatus.DENIED: DENIED,
    PendingActionStatus.EXPIRED: EXPIRED,
}


def _answer(status: PendingActionStatus) -> str:
    try:
        return _ANSWERS[status]
    except KeyError:
        raise ValueError(f"A pending action has no decision line yet: {status!r}") from None


def _lowered(summary: str) -> str:
    return summary[:1].lower() + summary[1:]


def decision_line(status: PendingActionStatus, summary: str) -> str:
    """What the thread shows: « Vous avez répondu : Autoriser — supprimer la série « … » »."""
    answer = _answer(status)
    if answer == EXPIRED:
        return f"Sans réponse : la demande a expiré — {_lowered(summary)}"
    word = "Autoriser" if answer == APPROVED else "Refuser"
    return f"Vous avez répondu : {word} — {_lowered(summary)}"


def decision_note(decision: dict, when: str) -> str:
    """What the next model call reads in the user's turn, from the stored `decision` and a local time label."""
    summary = _lowered(str(decision.get("summary") or "")).strip()
    answer = decision.get("answer")
    if answer == EXPIRED:
        return f"[Ta demande est restée sans réponse : elle a expiré — {summary} ({when})]"
    word = "Autoriser" if answer == APPROVED else "Refuser"
    channel = _CHANNEL_NAMES.get(str(decision.get("channel")), "chat")
    return f"[Réponse de l'utilisateur à ta demande : {word} — {summary} (via {channel}, {when})]"


async def record_decision(action: PendingAction, channel: str | None) -> Message | None:
    """Store the answer to `action` in the thread it was asked in; `channel` is None for an expiry.

    Never raises: the answer is already given and acted on, and a missing line must not look
    like a failed approval.
    """
    summary = action.display_summary
    try:
        return await message_repo.create(
            user_id=str(action.user_id),
            role=DECISION_ROLE,
            content=decision_line(action.status, summary),
            context_id=action.context_id,
            decision={
                "answer": _answer(action.status),
                "summary": summary,
                "channel": channel,
                "actionId": str(action.id),
            },
        )
    except Exception as e:
        logger.error(f"Could not record the decision on action {action.id}: {e}")
        return None

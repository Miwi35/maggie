"""The owner answers one of Maggie's messages by voice (MAG-683).

In the car the notification's « Répondre » takes a dictated sentence. It goes into the thread as
any message, with the message it answers: Maggie reads it as she would the phone's button
(« c'est fait » is « Fait », « oui, vas-y » is « Autoriser ») or as an instruction.

The message travels in its own field, like the screen (MAG-30): what is stored is what was said,
and the model reads the block in front of that turn.
"""

import json
import logging
import time

from fastapi import BackgroundTasks, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

HEADER = "[Réponse à un message de Maggie]"

REMINDER_BUTTONS = {
    "done": "Fait",
    "in10": "Dans 10 min",
    "tonight": "Ce soir",
    "tomorrow": "Demain",
    "start": "Je m'y mets",
    "open": "Voir",
}

_MAX_TEXT = 500

# What the owner has just said yes or no to, from his own device: the model may not decide a held action by itself,
# even one it triggered and whose id it saw. In memory, like a turn in flight: after a restart it says it could not.
_GRANT_TTL_S = 600
_grants: dict[tuple[str, str], float] = {}


class ReplyTo(BaseModel):
    """The message of Maggie the owner answers, as the phone shows it."""

    id: str = Field(min_length=1, max_length=64)
    type: str | None = Field(default=None, max_length=40)
    title: str | None = Field(default=None, max_length=300)
    text: str | None = Field(default=None, max_length=2000)
    reminder_id: str | None = Field(default=None, max_length=64)
    approval_id: str | None = Field(default=None, max_length=64)
    shortage_id: str | None = Field(default=None, max_length=64)
    # The keys of the buttons the phone shows (`data.actions`), when the message carries a reminder.
    buttons: list[str] = Field(default_factory=list, max_length=12)


def grant_approval_answer(user_id: str, reply: ReplyTo | None) -> None:
    """The owner answers this validation in the turn that starts now: `answer_approval` may decide it, once."""
    if reply is None or not reply.approval_id:
        return
    now = time.monotonic()
    for key in [key for key, until in _grants.items() if until <= now]:
        del _grants[key]
    _grants[(user_id, reply.approval_id)] = now + _GRANT_TTL_S


def _take_grant(user_id: str, approval_id: str) -> bool:
    until = _grants.pop((user_id, approval_id), None)
    return until is not None and until > time.monotonic()


def reply_block(reply: ReplyTo) -> str:
    """What the model reads in front of the owner's sentence."""
    said = " — ".join(part.strip()[:_MAX_TEXT] for part in (reply.title, reply.text) if part and part.strip())
    lines = [
        HEADER,
        "L'utilisateur répond à ce message que tu lui as envoyé (dicté en voiture, sur la notification) :",
        f"« {said or '(sans texte)'} »" + (f" — type : {reply.type}" if reply.type else ""),
    ]

    if reply.reminder_id:
        offered = [key for key in reply.buttons if key in REMINDER_BUTTONS] or list(REMINDER_BUTTONS)
        buttons = ", ".join(f"{key} = {REMINDER_BUTTONS[key]}" for key in offered)
        lines.append(
            f"C'est un rappel de tâche (reminderId : {reply.reminder_id}). Ses boutons sur le téléphone : {buttons}. "
            "Si sa phrase revient à presser l'un d'eux (« c'est fait » = done, « dans dix minutes » = in10, "
            "« ce soir » = tonight, « demain » = tomorrow, « je m'y mets » = start), appelle answer_task_reminder "
            "avec ce bouton, puis confirme en une phrase. Ne coche jamais la tâche autrement."
        )
    elif reply.approval_id:
        lines.append(
            f"C'est une demande de validation (approvalId : {reply.approval_id}). « oui », « vas-y », « autorise » : "
            "appelle answer_approval avec decision=approve ; « non », « laisse tomber », « refuse » : decision=deny. "
            "Dis ensuite en une phrase ce qui a été fait."
        )
    elif reply.shortage_id:
        lines.append(
            "C'est une alerte de produit manquant. S'il dit oui, ajoute le produit à la liste de courses "
            "avec les outils de courses ; s'il dit non, ne fais rien."
        )
    else:
        lines.append(
            "« Plus tard » ou « dans X minutes » : programme-le avec schedule_proaction. Un simple accusé "
            "(« ok », « noté ») : réponds par un mot."
        )

    lines.append(
        "Si sa phrase n'est pas une réponse à ce message, traite-la comme une consigne ordinaire. "
        "Dans le doute sur ce qu'il veut, demande-le plutôt que d'agir."
    )
    return "\n".join(lines)


def with_reply(screen: str | None, reply: ReplyTo | None) -> str | None:
    """The turn's extra context: the message answered, then the screen if there is one."""
    if reply is None:
        return screen
    return "\n\n".join(part for part in (reply_block(reply), screen) if part)


ANSWER_APPROVAL_TOOL = {
    "name": "answer_approval",
    "description": (
        "Approve or deny an action held for the user's validation (its approvalId is given with the message "
        "the user is answering), exactly as the « Autoriser » / « Refuser » buttons do. Only for an approval "
        "the user is answering in this message — never decide one on your own: any other call is refused."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "approval_id": {"type": "string", "description": "The approvalId of the message being answered"},
            "decision": {"type": "string", "enum": ["approve", "deny"]},
        },
        "required": ["approval_id", "decision"],
    },
}


async def handle_answer_approval(arguments: dict, user_id: str) -> str:
    # Imported here: the routes import the gateway, which imports the tools.
    from app.api import routes

    approval_id = arguments.get("approval_id")
    decision = arguments.get("decision")
    if not isinstance(approval_id, str) or decision not in {"approve", "deny"}:
        return json.dumps({"error": "approval_id and decision (approve or deny) are required"})

    if not _take_grant(user_id, approval_id):
        return json.dumps({"error": "Only the owner's answer to this validation can decide it; ask him."})

    try:
        if decision == "approve":
            # Not announced by a background task: the turn that called this answers the owner.
            decided = await routes.approve_action(
                approval_id, BackgroundTasks(), channel="notification", user_id=user_id
            )
        else:
            decided = await routes.deny_action(approval_id, channel="notification", user_id=user_id)
    except HTTPException as e:
        return json.dumps({"error": str(e.detail)})
    return json.dumps({"success": True, "approval": decided}, default=str)

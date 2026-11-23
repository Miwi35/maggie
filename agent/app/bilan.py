"""The evening review on the agent's side (MAG-749): the prompt of the proactions laid down for it from the
user's settings (`app/rendezvous.py`, MAG-787) and the block that draws its card (MAG-826). The conversation
itself is the skill `bilan-soir` (`app/skills/bilan-soir.md`); the state is the API's `activity-status/bilan`."""

from datetime import date

BILAN_SUMMARY = "Bilan de la journée"
RETIRED_RELANCE_SUMMARY = "Bilan de la journée · relance"
BILAN_PROMPT = (
    "Fais le bilan de la journée avec l'utilisateur (compétence bilan-soir, ouverture par le rendez-vous du soir)."
)
BILAN_AVAILABLE = "Bonsoir monsieur. Votre bilan de la journée vous attend."
"""The announcement the owner validated (22 Nov.), written by the code as the digest's."""
BILAN_ROUTE = "maggie://activity/bilan"
BILAN_PREPARE_PROMPT = (
    "Prépare l'étape Demain du bilan de la journée du {day} (compétence bilan-soir, section Préparer demain). "
    "Tu n'écris rien d'autre que save_bilan_step."
)
PREPARE_BLOCKED_TOOLS = frozenset(
    {
        "update_task",
        "create_task",
        "delete_task",
        "add_bilan_consigne",
        "schedule_proaction",
        "send_voice_message",
        "manage_notifications",
    }
)


def is_bilan(summary: str | None) -> bool:
    return summary == BILAN_SUMMARY


def is_retired_relance(summary: str | None) -> bool:
    return summary == RETIRED_RELANCE_SUMMARY


def bilan_block(day: date) -> dict:
    """The block that lets a client draw the review card, with the route that opens the activity."""
    return {"type": "bilan", "day": day.isoformat(), "route": BILAN_ROUTE}


async def prepare_bilan(gateway, user_id: str, day: date) -> dict:
    """One pass of the model that chooses tomorrow's tasks and writes them with `save_bilan_step` (MAG-958).

    Its text is never shown, only the bilan's state counts (owner, 22 Nov.): the message stays `BILAN_AVAILABLE`.
    """
    return await gateway.proaction(
        BILAN_PREPARE_PROMPT.format(day=day.isoformat()),
        user_id,
        summary=BILAN_SUMMARY,
        blocked_tools=PREPARE_BLOCKED_TOOLS,
    )

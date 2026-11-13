"""The evening review on the agent's side (MAG-749): the prompt of the proactions laid down for it from the
user's settings (`app/rendezvous.py`, MAG-787) and the block that draws its card (MAG-826). The conversation
itself is the skill `bilan-soir` (`app/skills/bilan-soir.md`); the state is the API's `activity-status/bilan`."""

from datetime import date

BILAN_SUMMARY = "Bilan de la journée"
RETIRED_RELANCE_SUMMARY = "Bilan de la journée · relance"
BILAN_PROMPT = (
    "Fais le bilan de la journée avec l'utilisateur (compétence bilan-soir, ouverture par le rendez-vous du soir)."
)
BILAN_ROUTE = "maggie://activity/bilan"


def is_bilan(summary: str | None) -> bool:
    return summary == BILAN_SUMMARY


def is_retired_relance(summary: str | None) -> bool:
    return summary == RETIRED_RELANCE_SUMMARY


def bilan_block(day: date) -> dict:
    """The block that lets a client draw the review card, with the route that opens the activity."""
    return {"type": "bilan", "day": day.isoformat(), "route": BILAN_ROUTE}

"""Which model each kind of run speaks to (MAG-731).

Judgement (conversation, morning planning, digest) goes to Sonnet, mechanical work (night upkeep, trips,
ordinary proactions, utilities) to Haiku. Every call asks `model_for`; reading `settings.anthropic_model`
directly is refused by `tests/test_model_by_run.py`.
"""

from app.bilan import BILAN_RELANCE_SUMMARY, BILAN_SUMMARY
from app.config import settings
from app.trips import is_silent_trip_run

CHAT = "chat"
PLANNING = "planning"
DIGEST = "digest"
TRIP = "trip"
NIGHT = "night"
PROACTION = "proaction"
UTILITY = "utility"

MODEL_BY_RUN: dict[str, str] = {
    CHAT: "sonnet",  # conversation, interruption, overlay, validated action
    PLANNING: "sonnet",
    DIGEST: "sonnet",  # morning digest, evening review
    TRIP: "haiku",
    NIGHT: "haiku",
    PROACTION: "haiku",
    UTILITY: "haiku",  # context summary, transcription, style, encoder
}


def model_for(run: str) -> str:
    return settings.model_aliases[MODEL_BY_RUN[run]]


def proaction_run(*, silent: bool, summary: str | None, prompt: str) -> str:
    """The kind of run a proaction is: the morning planning, a trip run, a digest or review, or any other."""
    if silent:
        return PLANNING
    if is_silent_trip_run(summary):
        return TRIP
    if summary in (BILAN_SUMMARY, BILAN_RELANCE_SUMMARY) or "digest" in f"{summary or ''} {prompt}".lower():
        return DIGEST
    return PROACTION

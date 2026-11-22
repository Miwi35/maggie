"""Which model each kind of run speaks to (MAG-731).

Judgement (conversation, morning planning, digest) goes to Sonnet, mechanical work (night upkeep, trips,
ordinary proactions, utilities) to Haiku. Every call asks `model_for`; reading `settings.anthropic_model`
directly is refused by `tests/test_model_by_run.py`.
"""

from app.bilan import BILAN_SUMMARY, RETIRED_RELANCE_SUMMARY
from app.config import settings
from app.trips import is_silent_trip_run

CHAT = "chat"
PLANNING = "planning"
DIGEST = "digest"
TRIP = "trip"
NIGHT = "night"
PROACTION = "proaction"
UTILITY = "utility"
APPROVAL_REPLY = "approval_reply"
CHECKER = "checker"

MODEL_BY_RUN: dict[str, str] = {
    CHAT: "sonnet",  # conversation, interruption, overlay, validated action
    PLANNING: "sonnet",
    DIGEST: "sonnet",  # morning digest, evening review
    TRIP: "haiku",
    NIGHT: "haiku",
    PROACTION: "haiku",
    UTILITY: "haiku",  # context summary, transcription, style, encoder
    APPROVAL_REPLY: "sonnet",  # owner's answer to a held action (blocking, irreversible)
    CHECKER: "sonnet",  # blocking check of an autonomous write
}


# How hard each kind of run thinks (`output_config.effort`). Judgement gets more, mechanical work less.
EFFORT_BY_RUN: dict[str, str] = {
    CHAT: "medium",
    PLANNING: "high",
    DIGEST: "medium",
    TRIP: "low",
    NIGHT: "low",
    PROACTION: "low",
    UTILITY: "low",
    APPROVAL_REPLY: "low",
    CHECKER: "low",
}

# Prefixes of the models that refuse `effort`.
NO_EFFORT_MODELS = ("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3")


def model_for(run: str) -> str:
    return settings.model_aliases[MODEL_BY_RUN[run]]


def effort_for(run: str) -> str | None:
    """The effort of this run, or `None` when its model refuses `effort` (Haiku 4.5)."""
    return None if model_for(run).startswith(NO_EFFORT_MODELS) else EFFORT_BY_RUN[run]


def effort_kwargs(run: str) -> dict:
    effort = effort_for(run)
    return {"output_config": {"effort": effort}} if effort else {}


def proaction_run(*, silent: bool, summary: str | None, prompt: str) -> str:
    """The kind of run a proaction is: the morning planning, a trip run, a digest or review, or any other."""
    if silent:
        return PLANNING
    if is_silent_trip_run(summary):
        return TRIP
    if summary in (BILAN_SUMMARY, RETIRED_RELANCE_SUMMARY) or "digest" in f"{summary or ''} {prompt}".lower():
        return DIGEST
    return PROACTION

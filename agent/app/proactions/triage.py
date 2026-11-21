"""How a proaction's message reaches the owner (MAG-886): the code chooses the channel, never the words.

A reminder the owner asked for always interrupts. A check is triaged on Maggie's verdict: what matters
interrupts, a detail waits in the thread, and a topic already told this week without anything new is
not told again.
"""

from datetime import datetime, timedelta
from enum import StrEnum

from app.proactions.verdict import Verdict

KIND_REMINDER = "reminder"
KIND_CHECK = "check"

DEDUP_WINDOW = timedelta(days=7)
DEADLINE_SOON = timedelta(hours=24)


class Delivery(StrEnum):
    INTERRUPT = "interrupt"
    THREAD = "thread"
    NONE = "none"


def _signaled_since(topic: str, signaled_topics: list[dict], since: datetime) -> bool:
    """Whether the topic was told (interruption or thread) after `since`."""
    for signaled in signaled_topics:
        if not isinstance(signaled, dict) or signaled.get("topic") != topic:
            continue
        try:
            at = datetime.fromisoformat(str(signaled.get("at")))
        except ValueError:
            continue
        if at.tzinfo is not None and at >= since:
            return True
    return False


def decide(kind: str, response: str, verdict: Verdict | None, signaled_topics: list[dict], now: datetime) -> Delivery:
    """The channel of one run's message. `now` must carry its time zone."""
    if not response.strip():
        return Delivery.NONE
    if kind != KIND_CHECK:
        # The owner asked to be told: the verdict is neither offered nor read.
        return Delivery.INTERRUPT
    if verdict is None:
        # Never hide by mistake: a check that gave no readable verdict speaks up.
        return Delivery.INTERRUPT
    if _signaled_since(verdict.topic, signaled_topics, now - DEDUP_WINDOW) and (
        verdict.changed is None or verdict.evidence == "weak"
    ):
        return Delivery.NONE
    if verdict.impact == "high":
        return Delivery.INTERRUPT
    deadline_soon = verdict.deadline is not None and verdict.deadline <= now + DEADLINE_SOON
    if verdict.impact == "medium" and (verdict.evidence == "solid" or deadline_soon):
        return Delivery.INTERRUPT
    return Delivery.THREAD

"""The two daily rendezvous — the morning digest and the evening review — laid down by code (MAG-787).

The user turns each on or off and sets its hour in the settings' Maggie page; the planning cycle brings the day's
proactions in line with those settings, for every non-technical account, with or without a planning
instruction. No model is involved: the proactions are recognised by their summary, so one the model laid
down from an instruction (« digest à 7 h ») is moved to the set hour rather than doubled.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.auth import TECHNICAL_ACCOUNT_EMAILS
from app.bilan import BILAN_PROMPT, BILAN_RELANCE_SUMMARY, BILAN_SUMMARY, RELANCE_DELAY_MINUTES
from app.config import settings
from app.proactions import api as proactions_api
from app.proactions.api import Proaction

logger = logging.getLogger(__name__)

DIGEST_SUMMARY = "Digest du matin"
DIGEST_PROMPT = "Fais le digest du matin (compétence digest)."
DEFAULT_DIGEST_TIME = "07:00"
DEFAULT_REVIEW_TIME = "21:30"


@dataclass(frozen=True)
class Rendezvous:
    """One proaction the settings ask for on a day: at `at`, or none at all when not `enabled`."""

    summary: str
    prompt: str
    at: datetime
    enabled: bool


def _hour(value: object, default: str) -> time:
    try:
        return time.fromisoformat(value) if isinstance(value, str) and len(value) == 5 else time.fromisoformat(default)
    except ValueError:
        return time.fromisoformat(default)


def _timezone(name: object) -> ZoneInfo:
    try:
        return ZoneInfo(str(name))
    except Exception:
        return ZoneInfo(settings.planning_timezone)


def wanted_rendezvous(user: dict, day: date, tz: ZoneInfo) -> list[Rendezvous]:
    """The digest, the review and its nudge `RELANCE_DELAY_MINUTES` later, on `day`'s wall clock."""
    digest_at = datetime.combine(day, _hour(user.get("morningDigestTime"), DEFAULT_DIGEST_TIME), tzinfo=tz)
    review_at = datetime.combine(day, _hour(user.get("eveningReviewTime"), DEFAULT_REVIEW_TIME), tzinfo=tz)
    digest = user.get("morningDigestEnabled") is not False
    review = user.get("eveningReviewEnabled") is not False
    return [
        Rendezvous(DIGEST_SUMMARY, DIGEST_PROMPT, digest_at, digest),
        Rendezvous(BILAN_SUMMARY, BILAN_PROMPT, review_at, review),
        Rendezvous(BILAN_RELANCE_SUMMARY, BILAN_PROMPT, review_at + timedelta(minutes=RELANCE_DELAY_MINUTES), review),
    ]


def _instant(proaction: Proaction) -> datetime | None:
    try:
        return datetime.fromisoformat(proaction.scheduled_at or "")
    except ValueError:
        return None


def _day_of(proaction: Proaction, tz: ZoneInfo) -> date | None:
    """The day a rendezvous belongs to: a nudge that falls after midnight is the evening before's."""
    at = _instant(proaction)
    if at is None:
        return None
    if proaction.summary == BILAN_RELANCE_SUMMARY:
        at -= timedelta(minutes=RELANCE_DELAY_MINUTES)
    return at.astimezone(tz).date()


async def sync_user(user: dict, now: datetime) -> dict[str, int]:
    """Bring one user's proactions of the day in line with their settings; how many were scheduled and cancelled.

    For each rendezvous: one that already ran today stays the day's; otherwise the pending one at the set hour
    is kept, the other pending ones are cancelled (an hour changed, a rendezvous turned off, a double), and the
    missing one is scheduled if its hour is still to come.
    """
    user_id = str(user["userId"])
    tz = _timezone(user.get("timezone"))
    day = now.astimezone(tz).date()
    start = datetime.combine(day, time(), tzinfo=tz)
    # Up to the end of the next day: a nudge set after midnight belongs to today's review.
    found = await proactions_api.list_for_user(user_id, start, start + timedelta(days=2))

    done = {"scheduled": 0, "cancelled": 0}
    for rendezvous in wanted_rendezvous(user, day, tz):
        mine = [p for p in found if p.summary == rendezvous.summary and _day_of(p, tz) == day]
        ran = any(p.status != "pending" for p in mine)
        keep = None
        if rendezvous.enabled and not ran:
            keep = next((p for p in mine if p.status == "pending" and _instant(p) == rendezvous.at), None)
        for proaction in mine:
            if proaction.status == "pending" and proaction is not keep:
                await proactions_api.cancel(user_id, proaction.id)
                done["cancelled"] += 1
        if rendezvous.enabled and not ran and keep is None and rendezvous.at > now:
            await proactions_api.schedule(user_id, rendezvous.prompt, rendezvous.at, summary=rendezvous.summary)
            done["scheduled"] += 1
    return done


async def sync_rendezvous(now: datetime, user_id: str | None = None) -> dict[str, int]:
    """Every non-technical account's rendezvous of the day, in line with their settings — or `user_id`'s alone.
    Never raises: a user whose proactions cannot be written now is tried again at the next cycle."""
    total = {"scheduled": 0, "cancelled": 0}
    try:
        users = await proactions_api.list_daily_rendezvous(user_id)
    except Exception as e:
        logger.error(f"Could not read the daily rendezvous settings: {e}")
        return total
    for user in users:
        email = user.get("email")
        if not user.get("userId") or (isinstance(email, str) and email.strip().lower() in TECHNICAL_ACCOUNT_EMAILS):
            continue
        try:
            done = await sync_user(user, now)
        except Exception as e:
            logger.error(f"Could not lay down the rendezvous of user {user.get('userId')}: {e}")
            continue
        total = {key: total[key] + done[key] for key in total}
    if total["scheduled"] or total["cancelled"]:
        logger.info(f"Daily rendezvous: {total['scheduled']} scheduled, {total['cancelled']} cancelled")
    return total

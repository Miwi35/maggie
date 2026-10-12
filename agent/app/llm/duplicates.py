"""The same message sent again while Maggie still answers it is the same message (MAG-386).

On 10 Oct. a slow answer made the owner send his request again: both turns ran, and the second
inversion of two labels undid the first. A retried message has its idempotency key (MAG-344);
a message typed or dictated again has none, so it is recognised by its text.
"""

import asyncio
from collections import defaultdict
from datetime import UTC, datetime, timedelta

from app.db.message_repository import message_repo
from app.db.models import TURN_RUNNING, Message
from app.llm.gateway import API_ERROR_MESSAGE
from app.llm.streaming import answer_message_id

# After the first turn ends, the same text this soon is still the same message; later, it is
# the owner asking again on purpose.
DUPLICATE_WINDOW = timedelta(seconds=60)

_locks: defaultdict[str, asyncio.Lock] = defaultdict(asyncio.Lock)


def normalize(text: str) -> str:
    return " ".join(text.split()).casefold()


def user_lock(user_id: str) -> asyncio.Lock:
    """Held from the look-up to the storing of a message: two requests at once cannot both open a turn."""
    return _locks[user_id]


def _within_window(moment: datetime) -> bool:
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return datetime.now(UTC) - moment < DUPLICATE_WINDOW


async def _last_question_if_same(user_id: str, said: str) -> Message | None:
    last = await message_repo.find_last(user_id, role="user")
    if last is None or normalize(last.content) != normalize(said):
        return None
    return last


def _stands(answer: Message | None) -> bool:
    """An answer a repeat may be attached to: not cut off by the owner, not the apology of a failed turn."""
    return answer is not None and not answer.interrupted and answer.content.strip() != API_ERROR_MESSAGE


async def find_duplicated_turn(user_id: str, said: str) -> Message | None:
    """The user's last message when `said` repeats it while its turn runs or just ended, else `None`.

    A turn that ended without an answer, with the apology of a failure, or whose answer the
    owner cut off is not a turn to attach to: sending the text again is how one tries once more.
    The answer is read first — a cut-off turn keeps running until it finds its row taken.
    """
    last = await _last_question_if_same(user_id, said)
    if last is None:
        return None
    answer = await message_repo.get(answer_message_id(last.id))
    if answer is not None:
        return last if _stands(answer) and _within_window(answer.created_at) else None
    return last if last.turn_status == TURN_RUNNING else None


async def find_settled_answer(user_id: str, said: str) -> tuple[Message, Message] | None:
    """The `(question, answer)` of `POST /chat` when `said` repeats a question answered less than a minute ago."""
    last = await _last_question_if_same(user_id, said)
    if last is None:
        return None
    answer = await message_repo.find_answer_to(user_id, last)
    if not _stands(answer) or not _within_window(answer.created_at):
        return None
    return last, answer

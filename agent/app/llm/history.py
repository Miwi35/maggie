"""The conversation the model is sent, built around the thread the message belongs to (MAG-13).

What went before was the user's last fifty messages, every thread mixed together, loaded
*before* the router had said which thread the current message belonged to. The routing
therefore decided nothing: a thread Maggie was in contributed exactly as much as one she
had left an hour ago, and the only thing that told them apart was luck of ordering.

This module is what replaces it, and it is shared by the streamed path and the
non-streamed one — the two copies of `_load_conversation_history` it supersedes had already
drifted apart, one of them sending the user's message twice.

Three things reach the model, and the split is deliberate:

  - **the thread's own messages**, verbatim and in full (up to
    `context_history_messages`). This is the conversation.
  - **a short global window** (`recent_history_messages`), so « et ça aussi » said a minute
    ago in a neighbouring thread is still there. A user message that comes in this way and
    belongs elsewhere is prefixed with its thread's label: unmarked, it is the very
    confusion this ticket exists to remove. Maggie's answers never are — she copied the
    label into her own (MAG-341). This window is also what keeps a change of thread from
    being amnesia: right after one, it still holds the last exchanges of the thread just
    left (`recent_history_messages`: 8 messages, the new one and the seven before it).
  - **nothing of the other threads but their summary**, and that lives in the system prompt
    where MAG-11 put it (`active_contexts_section`). A summary is written *about* a
    conversation, not *in* it, so it is not a turn anyone took.

And since MAG-211 the thread's last turns bring back **what their tools said**, not only
what Maggie made of it: the `tool_use` / `tool_result` rounds stored on the message
(`app.llm.tool_blocks`), replayed in front of its text. Only the last
`tool_replay_turns` of them, because a tool result is bulky and a thread is long — past
that window a turn is its text again, which is exactly what it was before.

The prompt cache is untouched by any of this: the cached prefix is the tools and the
stable system block (`app.llm.prompt_cache`), and the conversation has never been part of
it. Replaying blocks makes a call bigger, never less cacheable.

Nothing here raises. A database that will not answer costs the model the conversation; it
must not cost the user the answer.
"""

import logging
import re
from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from app.config import settings
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import TURN_EXPIRED, TURN_RUNNING, Message
from app.llm.screen_context import attach
from app.llm.tool_blocks import replay
from app.personality.engine import TZ_PARIS, french_date

logger = logging.getLogger(__name__)

# What a message of another thread is announced as. The label comes from the thread itself
# when it is still open; a closed or deleted one leaves the neutral form, which still says
# the one thing that matters — this was not said here.
UNKNOWN_THREAD = "[autre fil] "

# What a label looks like at the head of an answer, should the model copy one anyway: the
# two forms above, any number of them, and the spaces after (MAG-341).
_LEADING_LABELS = re.compile(r"^\s*(?:\[(?:fil «[^»\]\n]*»|autre fil)\]\s*)+")

# The longest a leading label can be before it is closed — a thread label is a few words.
# A streamed answer that starts with « [ » is held back this long at most.
MAX_LABEL_CHARS = 120


def _prefix(label: str | None) -> str:
    return f"[fil « {label} »] " if label else UNKNOWN_THREAD


def _now(tz: ZoneInfo) -> datetime:
    return datetime.now(tz)


def _day_marker(day: datetime, today: datetime) -> str:
    """The line that dates the messages after it (MAG-349): « — le mardi 6 octobre — ».

    On 8 Oct. Maggie read « demain vendredi 3 octobre », written on the 2nd, as tomorrow.
    Back on today after a past day, it says so: the messages that follow are not under the
    date above them any more.
    """
    if day.date() == today.date():
        return f"— aujourd'hui, {french_date(day, with_year=False)} —\n"
    return f"— le {french_date(day, with_year=day.year != today.year)} —\n"


def strip_thread_label(text: str) -> str:
    """`text` without the thread labels a model may have copied at its head (MAG-341).

    The labels are bookkeeping on the messages the history borrows from other threads. On
    7 Oct. they were on Maggie's own past answers too, and she took the pattern for hers:
    « [fil « Où noter l'information »] Ah, d'accord monsieur… ». The history no longer
    labels her answers; this is the net under an answer that copies one all the same.
    """
    return _LEADING_LABELS.sub("", text, count=1)


def label_settled(text: str) -> bool:
    """Whether the head of a streamed answer is known to be — or not to be — a thread label.

    Until it is, the stream holds the text back: once a delta is shown it cannot be taken
    out of the bubble. Settled as soon as the text does not open on « [ », once a « ] » has
    closed it, or past the length no label reaches.
    """
    if len(text.lstrip()) > MAX_LABEL_CHARS:
        return True
    rest = strip_thread_label(text).lstrip()
    if not rest:
        return False
    # Opening on « [ » and not yet closed may still become a label; closed, it was not one.
    return not rest.startswith("[") or "]" in rest


async def build_history(
    user_id: str,
    *,
    context_id: str | None = None,
    pending_message: str | None = None,
    fallback_message: str | None = None,
    current_message_id: str | None = None,
    screen_context: str | None = None,
    tz: ZoneInfo | None = None,
) -> list[dict]:
    """The `messages` list to send, for a message already routed into `context_id`.

    `context_id` is `None` when there is no thread — no model to route with, or a routing
    call that failed. The global window is then all there is, and nothing is labelled:
    with no current thread, no message is a foreign one.

    `pending_message` is the user's message when the caller has *not* stored it (the A2A
    path). The chat routes store it before calling, so the history already holds it and
    passing it again is how it used to be sent twice.

    `fallback_message` is that same message for a caller that *did* store it, used only if
    the history comes back empty. The API refuses a conversation with no message in it, so
    without this a database that would not answer turns into an API error the caller shows
    as « une erreur est survenue » — the promise above, broken on the one failure it was
    written for.

    `current_message_id` is the stored message being answered. A tag that could not be
    written leaves it untagged, and it would then come in through the global window and be
    announced as another thread's — Maggie reading the question she is answering as a
    neighbour's.

    `screen_context` is the screen the assistant was summoned from (MAG-30), and this is
    where it rejoins the conversation: on the turn being answered, and on that one only.
    It is deliberately not stored — the message is what every client displays — so this
    function is the single place the model's copy differs from the user's.

    `tz` is the user's timezone: a user message written on another day than today's opens
    with that day's date (MAG-349), counted in it. Paris when not given.
    """
    try:
        rows = await _rows(user_id, context_id)
    except Exception as exc:
        # Logged and swallowed: the alternative is a 500 on a chat because a thread would
        # not load, which trades the whole answer for part of its context.
        logger.warning(f"Failed to load the conversation history: {exc}")
        rows = []

    # Guarded on its own: labels are decoration on the messages of other threads, and
    # losing them must cost the prefix, not the conversation.
    labels: dict[str, str] = {}
    if _has_foreign(rows, context_id, current_message_id):
        try:
            labels = await _labels(user_id)
        except Exception as exc:
            logger.warning(f"Could not name the threads the history borrows from: {exc}")

    turns = _turns(rows, context_id, labels, current_message_id, screen_context, tz or TZ_PARIS)
    if pending_message:
        _append_user(turns, _dated_today(rows, pending_message, tz or TZ_PARIS))
    # The floor under a history that would not load is then the whole conversation, so the
    # screen has to come with it: without this the one turn the model gets is blind.
    if not turns and fallback_message:
        _append_user(turns, attach(fallback_message, screen_context))

    # The number of turns no longer matches the number of messages once blocks are
    # replayed, so both are logged: a thread whose history suddenly doubles in turns is a
    # replay window that has been raised, not messages appearing out of nowhere.
    logger.info(f"History: {len(turns)} turns from {len(rows)} messages (thread {context_id})")
    return turns


async def _rows(user_id: str, context_id: str | None) -> list[Message]:
    """The thread's messages and the global window, as one chronological list without repeats.

    Keyed by id before being ordered: the newest messages of the thread are in both
    queries, and sending them twice would read as the user having said the same thing
    twice.
    """
    found: dict[str, Message] = {}

    # Guarded one by one rather than together: they answer different needs, and a window
    # that will not load must not throw away a thread that just did.
    if context_id:
        try:
            thread = await message_repo.find_by_context(
                context_id, limit=settings.context_history_messages, user_id=user_id
            )
        except Exception as exc:
            logger.warning(f"Could not load the messages of thread {context_id}: {exc}")
        else:
            found.update({row.id: row for row in thread})

    try:
        window = await message_repo.find_recent(user_id, limit=settings.recent_history_messages)
    except Exception as exc:
        logger.warning(f"Could not load the recent messages: {exc}")
    else:
        found.update({row.id: row for row in window})

    # Two messages of the same exchange can share a timestamp at the database's
    # resolution, and an answer sorted before its question is worse than none — so the
    # role breaks the tie first, the user's side going in front. The id is only there to
    # make the rest deterministic: it is a truncated `uuid4().hex`, not a sortable ULID,
    # so it carries no order of its own.
    return sorted(found.values(), key=lambda row: (row.created_at, row.role != "user", row.id))


def _has_foreign(rows: list[Message], context_id: str | None, current_message_id: str | None) -> bool:
    return bool(context_id) and any(_is_foreign(row, context_id, current_message_id) for row in rows)


def _is_foreign(row: Message, context_id: str | None, current_message_id: str | None) -> bool:
    """Whether this message was said somewhere other than the thread being answered in.

    The message being answered never is, even when its tag could not be written: it is the
    reason this turn exists, and announcing it as a neighbour's would be a worse lie than
    leaving it unlabelled.
    """
    if not context_id or row.context_id == context_id:
        return False
    return current_message_id is None or str(row.id) != str(current_message_id)


async def _labels(user_id: str) -> dict[str, str]:
    """What each of the user's open threads is called, to name the messages that came from one."""
    return {str(ctx.id): ctx.label for ctx in await context_repo.find_active(user_id)}


def _turns(
    rows: list[Message],
    context_id: str | None,
    labels: dict[str, str],
    current_message_id: str | None,
    screen_context: str | None = None,
    tz: ZoneInfo = TZ_PARIS,
) -> list[dict]:
    """The rows as Anthropic turns: dated, labelled, replayed, merged, and starting on the user."""
    # A turn taken up again after a restart may have been overtaken: the user retyped the
    # request and it was answered. Its history stops at its own message, or the model would
    # be sent a conversation ending on its own answer (MAG-344).
    if current_message_id is not None:
        own = next((i for i, row in enumerate(rows) if str(row.id) == str(current_message_id)), None)
        if own is not None and any(row.role == "assistant" for row in rows[own + 1 :]):
            rows = rows[: own + 1]
    replayed = _replay_window(rows, context_id)
    turns: list[dict] = []
    today = _now(tz)
    announced: datetime | None = None
    for row in rows:
        if row.role not in ("user", "assistant") or not row.content:
            continue
        if _is_orphan(row, current_message_id):
            continue

        content = row.content
        # Only the user's side is labelled. Maggie's own answers, labelled, read to her as
        # the way she writes — and she wrote « [fil « … »] » at the head of hers (MAG-341).
        # The question just before an answer already says which thread the exchange was in.
        if row.role == "user" and _is_foreign(row, context_id, current_message_id):
            content = _prefix(labels.get(str(row.context_id))) + content
        # Only the turn being answered: the screen was there when that one was dictated,
        # and the follow-up question is about the answer, not about the page.
        if screen_context and current_message_id is not None and str(row.id) == str(current_message_id):
            content = attach(content, screen_context)
        # Only the user's side again: an answer opening on « — le mardi 6 octobre — » would
        # be copied the way the thread label was. The question that opens the day dates it.
        if row.role == "user":
            day = _local(row.created_at, tz)
            if (announced is None and day.date() != today.date()) or (
                announced is not None and day.date() != announced.date()
            ):
                content = _day_marker(day, today) + content
            announced = day

        # What this turn's tools said, before the text it led to (MAG-211). Whole or not at
        # all: `replay` returns nothing for blocks it cannot vouch for, and the message is
        # then the text it has always been.
        rounds = replay(row.blocks) if str(row.id) in replayed else []
        if rounds and _append_rounds(turns, rounds):
            turns.append({"role": row.role, "content": content})
            continue

        # The API refuses two turns of the same role in a row, and a thread does get them:
        # a proaction arrives unprompted between two of Maggie's answers, and a user sends
        # three messages before she replies to the first.
        if turns and turns[-1]["role"] == row.role and isinstance(turns[-1]["content"], str):
            turns[-1]["content"] += "\n" + content
        else:
            turns.append({"role": row.role, "content": content})

    # And it refuses a conversation that does not open on the user. A thread whose oldest
    # loaded message is one of Maggie's own — a proaction, or simply the window cutting
    # mid-exchange — would otherwise be a 400 on every message from then on.
    #
    # A batch of tool results is one of those: it is a user turn, but an orphan one as soon
    # as the `tool_use` it answers has been trimmed off in front of it — which the API
    # rejects just as flatly. Only a turn of plain text can open the conversation.
    while turns and (turns[0]["role"] != "user" or not isinstance(turns[0]["content"], str)):
        turns.pop(0)

    return turns


def _dated_today(rows: list[Message], message: str, tz: ZoneInfo) -> str:
    """`message`, opened with today's marker when the last user message above it is from another day."""
    last = next((row for row in reversed(rows) if row.role == "user" and row.content), None)
    today = _now(tz)
    if last is not None and _local(last.created_at, tz).date() != today.date():
        return _day_marker(today, today) + message
    return message


def _local(moment: datetime, tz: ZoneInfo) -> datetime:
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.astimezone(tz)


def _is_orphan(row: Message, current_message_id: str | None) -> bool:
    """Whether this user message is a request nobody is answering, which the model must not pick up (MAG-344).

    On 7 Oct. a message left without an answer was read back from the history as if it were
    pending, and executed hours later: a reminder nobody wanted. A message too old to be
    answered is never part of the conversation again; one whose turn was lost and not yet
    taken up is left to the turn that will answer it, or this one would answer it too. A turn
    that is running is the conversation, as before: « et du pain » sent while the first is
    still being answered.
    """
    if row.role != "user" or (current_message_id is not None and str(row.id) == str(current_message_id)):
        return False
    if row.turn_status == TURN_EXPIRED:
        return True
    if row.turn_status != TURN_RUNNING:
        return False
    lease = row.turn_lease_until
    if lease is None:
        return True
    if lease.tzinfo is None:
        lease = lease.replace(tzinfo=UTC)
    return lease < datetime.now(UTC)
def _replay_window(rows: list[Message], context_id: str | None) -> set[str]:
    """Which messages are sent their tool blocks back, and not merely their text.

    The thread's own, because the blocks are replayed as turns of *this* conversation: a
    neighbouring thread's call has no place in it, and its text already comes in labelled,
    which is all it is there for. And only the last `tool_replay_turns` of them, walked
    newest first — a tool result is bulky, so what the budget buys goes to the turns a
    follow-up question is actually about.
    """
    budget = settings.tool_replay_turns
    if not context_id or budget <= 0:
        return set()

    window: set[str] = set()
    for row in reversed(rows):
        if len(window) >= budget:
            break
        if row.role == "assistant" and str(row.context_id) == str(context_id) and row.blocks:
            window.add(str(row.id))
    return window


def _append_rounds(turns: list[dict], rounds: list[dict]) -> bool:
    """Put a turn's tool rounds on the conversation, in front of the text they led to.

    Returns whether they could go on at all. They open on one of Maggie's turns and the
    API refuses two of hers in a row, so an answer of hers sitting just before them — a
    proaction between two of her messages, the window cutting mid-exchange — is folded
    into the first one as a text block. A preceding turn that is not plain text cannot be
    folded, and the rounds are left out rather than sent in a shape that would be rejected.
    """
    if turns and turns[-1]["role"] == "assistant":
        if not isinstance(turns[-1]["content"], str):
            return False
        previous = turns.pop()
        rounds[0] = {
            "role": "assistant",
            "content": [{"type": "text", "text": previous["content"]}, *rounds[0]["content"]],
        }
    turns.extend(rounds)
    return True


def _append_user(turns: list[dict], message: str) -> None:
    # Merged into a trailing user turn, unless that turn is a batch of tool results — a
    # list of blocks, which a sentence cannot be glued onto.
    if turns and turns[-1]["role"] == "user" and isinstance(turns[-1]["content"], str):
        turns[-1]["content"] += "\n" + message
    else:
        turns.append({"role": "user", "content": message})

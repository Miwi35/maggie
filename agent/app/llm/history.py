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
    ago in a neighbouring thread is still there. A message that comes in this way and
    belongs elsewhere is prefixed with its thread's label: unmarked, it is the very
    confusion this ticket exists to remove.
  - **nothing of the other threads but their summary**, and that lives in the system prompt
    where MAG-11 put it (`active_contexts_section`). A summary is written *about* a
    conversation, not *in* it, so it is not a turn anyone took.

Nothing here raises. A database that will not answer costs the model the conversation; it
must not cost the user the answer.
"""

import logging

from app.config import settings
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import NOTHING_SAID, Message

logger = logging.getLogger(__name__)

# What a message of another thread is announced as. The label comes from the thread itself
# when it is still open; a closed or deleted one leaves the neutral form, which still says
# the one thing that matters — this was not said here.
UNKNOWN_THREAD = "[autre fil] "


# What Maggie reads after an answer the user cut short (MAG-223). The row holds only what was
# said or shown; without this she would take it for the whole of what she meant to say, or —
# worse, when the full text is gone — not know she was interrupted at all.
INTERRUPTION_AFTER_WORDS = (
    "\n[Interruption : l'utilisateur t'a coupé la parole ici. Tu n'as pas pu dire la suite : il ne l'a ni "
    "entendue ni lue. Ne reprends pas ce que tu disais ; tiens compte de ce qu'il dit maintenant.]"
)
INTERRUPTION_BEFORE_WORDS = (
    "[Interruption : l'utilisateur t'a coupé la parole avant que tu aies dit quoi que ce soit. Ne reprends pas "
    "ce que tu préparais ; tiens compte de ce qu'il dit maintenant.]"
)


def _spoken(row: Message) -> str:
    """The row's text, with the interruption it ended on spelled out for the model."""
    if not row.interrupted:
        return row.content
    if row.content == NOTHING_SAID:
        return INTERRUPTION_BEFORE_WORDS
    return row.content + INTERRUPTION_AFTER_WORDS


def _prefix(label: str | None) -> str:
    return f"[fil « {label} »] " if label else UNKNOWN_THREAD


async def build_history(
    user_id: str,
    *,
    context_id: str | None = None,
    pending_message: str | None = None,
    fallback_message: str | None = None,
    current_message_id: str | None = None,
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

    turns = _turns(rows, context_id, labels, current_message_id)
    if pending_message:
        _append_user(turns, pending_message)
    if not turns and fallback_message:
        _append_user(turns, fallback_message)

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
    rows: list[Message], context_id: str | None, labels: dict[str, str], current_message_id: str | None
) -> list[dict]:
    """The rows as Anthropic turns: labelled, merged, and starting on the user."""
    turns: list[dict] = []
    for row in rows:
        if row.role not in ("user", "assistant") or not row.content:
            continue

        content = _spoken(row)
        if _is_foreign(row, context_id, current_message_id):
            content = _prefix(labels.get(str(row.context_id))) + content

        # The API refuses two turns of the same role in a row, and a thread does get them:
        # a proaction arrives unprompted between two of Maggie's answers, and a user sends
        # three messages before she replies to the first.
        if turns and turns[-1]["role"] == row.role:
            turns[-1]["content"] += "\n" + content
        else:
            turns.append({"role": row.role, "content": content})

    # And it refuses a conversation that does not open on the user. A thread whose oldest
    # loaded message is one of Maggie's own — a proaction, or simply the window cutting
    # mid-exchange — would otherwise be a 400 on every message from then on.
    while turns and turns[0]["role"] != "user":
        turns.pop(0)

    return turns


def _append_user(turns: list[dict], message: str) -> None:
    if turns and turns[-1]["role"] == "user":
        turns[-1]["content"] += "\n" + message
    else:
        turns.append({"role": "user", "content": message})

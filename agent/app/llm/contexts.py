"""The threads a user has open, for every path that writes in one (MAG-11, MAG-13, MAG-14).

Three things live here, because the streamed chat, the plain chat and the proactions all
need them and a second copy of any of them would drift apart:

  - `active_contexts_section()` — the open threads as a system-prompt block: every
    label, and the summary of the few most recently spoken in. This is what a proaction
    used to run without, so a reminder landed in the conversation knowing nothing of it.
    The thread the current message was routed into is marked, and carries what has already
    been done in it (MAG-13).
  - `resolve_context()` — the fast-model router that places a piece of text in an open
    thread or opens a new one, with the ownership check a model's answer needs (MAG-203).
  - `route_message()` — the same routing, plus writing the resolved thread on the message
    it came from. Both chat paths route before building anything from the answer, so both
    need the tagging (MAG-13). A message sent shortly after the previous one stays in the
    thread in progress unless it asks to change subject or clearly has nothing to do with
    it (MAG-341): one discussion, one thread.

None of them raises. A thread list the database would not give up costs the model what the
conversation was about; it must not cost the user the answer, or the reminder.
"""

import json
import logging
import re
import time
import unicodedata
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from app.config import settings
from app.db.context_model import ContextStatus
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.metrics import record_llm_usage

logger = logging.getLogger(__name__)

# How many open threads carry their summary into the system prompt. Beyond this the
# label alone goes in: a summary is ~5 lines, and the owner can have a dozen threads open.
MAX_SUMMARIZED_CONTEXTS = 5

# How many of the current thread's tool calls are recalled. They are the one trace of what
# Maggie already did in the thread that survives a turn — the `tool_use` blocks themselves
# are not stored — and the newest are the ones that still matter.
MAX_RECALLED_TOOL_CALLS = 5

ROUTER_SYSTEM_PROMPT = (
    "Tu es un routeur de contexte. Analyse le message et les contextes existants.\n"
    "Réponds UNIQUEMENT avec un JSON valide, sans explication :\n"
    '- Si le message correspond à un contexte existant : {"context_id": "<id>"}\n'
    '- Si c\'est un nouveau sujet : {"context_id": null, "label": "<label court>"}\n'
    "Le label doit être court (3-5 mots max), en français.\n"
    "Préfère les contextes récents, mais un ancien contexte reste valide si le sujet correspond."
)

# How long after the previous message the conversation still counts as going on (MAG-341).
# Within it, a message stays in the thread in progress unless the user asks to change
# subject or the router finds it clearly unrelated; beyond it, the router places it freely.
# Fifteen minutes, the top of the range the ticket gives: a pause to answer the door or stir
# a pan does not end a discussion, and a few extra minutes in a thread cost nothing — a
# wrong split costs Maggie everything that was said a minute before.
CONTINUITY_WINDOW = timedelta(minutes=15)

# How much of the discussion in progress the router is shown, so a follow-up that names
# nothing — « et où l'as-tu noté du coup » — can be tied to what it follows (MAG-341).
CONTINUITY_EXCERPT_MESSAGES = 4
CONTINUITY_EXCERPT_CHARS = 200

# The user saying, in so many words, that the subject changes. Matched on the message
# lowercased and stripped of accents. « autre chose » only counts at the start: in the
# middle of a sentence it is « tu peux ajouter autre chose ? », not a change of subject.
_EXPLICIT_SWITCH = re.compile(
    r"\bchang(?:e|er|eons|ez)\b(?:\s+\w+)?\s+de\s+sujet\b"
    r"|\b(?:nouveau|autre)\s+sujet\b"
    r"|\bparlons\s+d'autre\s+chose\b"
    r"|\brien\s+a\s+voir\b"
    r"|^\W*(?:bon,?\s+|sinon,?\s+)?autre\s+chose\b"
)

# The same router while a discussion is going on (MAG-341). Same answer format — the
# parsing and the fake-llm scenarios are shared — but the question is turned around: the
# message continues the thread in progress unless it clearly does not.
CONTINUITY_SYSTEM_PROMPT = (
    "Tu es un routeur de contexte. Une discussion est en cours dans le fil marqué « ← fil en cours »,"
    " et tu vois ses derniers échanges.\n"
    "Réponds UNIQUEMENT avec un JSON valide, sans explication :\n"
    '- Par défaut, le message continue la discussion : {"context_id": "<id du fil en cours>"}\n'
    '- Seulement s\'il reprend clairement un autre contexte existant : {"context_id": "<id>"}\n'
    "- Seulement s'il n'a clairement aucun lien avec la discussion :"
    ' {"context_id": null, "label": "<label court>"}\n'
    "Une question de suivi, une précision, une objection, une réponse à ce que Maggie vient de dire"
    " continuent la discussion, même sans en répéter le sujet. Dans le doute, reste dans le fil en cours.\n"
    "Le label doit être court (3-5 mots max), en français."
)

# The router when the user has just asked to change subject (MAG-341). The thread in
# progress is not on offer: the user is leaving it.
SWITCH_SYSTEM_PROMPT = (
    "Tu es un routeur de contexte. L'utilisateur vient de demander à changer de sujet.\n"
    "Réponds UNIQUEMENT avec un JSON valide, sans explication :\n"
    '- Si le nouveau sujet correspond à un contexte existant : {"context_id": "<id>"}\n'
    '- Sinon : {"context_id": null, "label": "<label court>"}\n'
    "Le label doit être court (3-5 mots max), en français, et nomme le nouveau sujet."
)


async def active_contexts_section(user_id: str, current_context_id: str | None = None) -> str:
    """The user's open threads as a system-prompt section, or an empty string if there are none.

    What each thread is about comes from its summary (MAG-11), and this is all a thread
    other than the current one leaves behind: without it, a context the conversation
    has moved on from is a label and nothing else.

    `current_context_id` is the thread the message being answered was routed into, when the
    caller has one (MAG-13). It is marked, because the raw messages in the history are
    *its* messages and the model has to know which list it is reading; and it is the only
    one whose tool calls are recalled, since what Maggie already did in the thread is the
    part of a turn that does not survive in the stored text.
    """
    try:
        contexts = await context_repo.find_active(user_id)
    except Exception as e:
        logger.warning(f"Could not load the open threads: {e}")
        return ""

    if not contexts:
        return ""

    lines = ["\n\nContextes de conversation en cours :"]
    for rank, ctx in enumerate(contexts):
        status_icon = "●" if ctx.status == ContextStatus.ACTIVE else "◐"
        current = current_context_id is not None and str(ctx.id) == str(current_context_id)
        marker = " ← fil en cours" if current else ""
        lines.append(f"- {status_icon} {ctx.label} ({ctx.status.value}){marker}")
        # Every open thread keeps its label — the list is what tells Maggie a subject is
        # still open. Only the most recently spoken-in ones carry their summary with
        # them, because that is where the tokens are and `find_active` already orders by
        # `updated_at`. The current thread always does, whatever its rank: its summary is
        # what covers the part of it the history window no longer reaches.
        if ctx.summary and (current or rank < MAX_SUMMARIZED_CONTEXTS):
            lines.append(f"  Résumé : {ctx.summary}")
        if current:
            recalled = _recent_tool_calls(ctx.tool_calls_log)
            if recalled:
                lines.append(f"  Outils déjà appelés dans ce fil : {recalled}")
    return "\n".join(lines)


def _recent_tool_calls(log: list | None) -> str:
    """The last few tool calls of a thread, as `name (status)` — or an empty string.

    `context_repo.append_tool_call` has been writing this log since the contexts existed
    and nothing but the Mind panel ever read it. It is not a replay of the `tool_use`
    blocks — `agent_message` holds text and a role, so the blocks themselves are gone —
    but it is enough for Maggie to know she has already looked at the grocery list in this
    thread rather than looking again.
    """
    entries = [entry for entry in (log or []) if isinstance(entry, dict) and entry.get("name")]
    if not entries:
        return ""
    recent = entries[-MAX_RECALLED_TOOL_CALLS:]
    return ", ".join(f"{entry['name']} ({entry.get('status', 'inconnu')})" for entry in recent)


def _age(idle: timedelta) -> str:
    """How long a thread has been quiet, as the router reads it."""
    seconds = idle.total_seconds()
    if seconds < 60:
        return "à l'instant"
    if seconds < 3600:
        return f"il y a {int(seconds // 60)}min"
    if seconds < 86400:
        return f"il y a {int(seconds // 3600)}h"
    return f"il y a {idle.days}j"


def _now() -> datetime:
    """The clock the continuity window is read against — a function so the tests can set it."""
    return datetime.now(UTC)


def _aware(moment: datetime) -> datetime:
    """A stored timestamp as an aware one: Postgres gives them so, SQLite drops the zone."""
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def asks_to_change_subject(text: str) -> bool:
    """Whether the user says, in so many words, that the subject changes (MAG-341)."""
    folded = unicodedata.normalize("NFKD", text.lower().replace(chr(0x2019), "'"))
    folded = "".join(char for char in folded if not unicodedata.combining(char))
    return bool(_EXPLICIT_SWITCH.search(folded))


@dataclass
class _Discussion:
    """The discussion the message follows: its thread, and its last exchanges as the router reads them."""

    context: object
    excerpt: str


async def _discussion_in_progress(user_id: str, message_id: str, contexts: list) -> _Discussion | None:
    """The thread the conversation is in, when the previous message is recent enough to continue it.

    `None` when there is nothing to continue: no earlier message, one older than
    `CONTINUITY_WINDOW`, or one whose thread is no longer open. The router is then free,
    as it always was. A history that will not load is the same case — logged, not raised.
    """
    try:
        recent = await message_repo.find_recent(user_id, limit=CONTINUITY_EXCERPT_MESSAGES + 1)
    except Exception as e:
        logger.warning(f"Could not load the discussion in progress, routing freely: {e}")
        return None

    # `find_recent` gives them oldest first; newest first here, the previous message at the head.
    previous = [row for row in reversed(recent) if str(row.id) != str(message_id)]
    if not previous:
        return None

    now = _now()
    gap = now - _aware(previous[0].created_at)
    if gap > CONTINUITY_WINDOW:
        logger.info(f"Context routing: last message {int(gap.total_seconds() // 60)} min ago, routing freely")
        return None

    open_threads = {str(ctx.id): ctx for ctx in contexts}
    current = next(
        (
            open_threads[str(row.context_id)]
            for row in previous
            if row.context_id
            and str(row.context_id) in open_threads
            and now - _aware(row.created_at) <= CONTINUITY_WINDOW
        ),
        None,
    )
    if current is None:
        return None

    lines = []
    for row in reversed(previous[:CONTINUITY_EXCERPT_MESSAGES]):
        speaker = "Utilisateur" if row.role == "user" else "Maggie"
        lines.append(f"- {speaker} : {(row.content or '')[:CONTINUITY_EXCERPT_CHARS]}")
    return _Discussion(context=current, excerpt="\n".join(lines))


def _thread_lines(contexts: list, now: datetime, current_id: str | None = None) -> str:
    lines = []
    for ctx in contexts:
        marker = " ← fil en cours" if current_id is not None and str(ctx.id) == current_id else ""
        age = _age(now - _aware(ctx.updated_at))
        lines.append(f'- id="{ctx.id}" label="{ctx.label}" dernière activité={age}{marker}')
    return "\n".join(lines) if lines else "(aucun)"


async def _ask_router(client, system: str, prompt: str) -> dict:
    """One call to the fast model, its answer parsed. Raises on anything but a JSON object."""
    t0 = time.monotonic()
    response = await client.messages.create(
        model=settings.anthropic_fast_model,
        max_tokens=200,
        system=system,
        messages=[{"role": "user", "content": prompt}],
    )
    record_llm_usage(
        model=settings.anthropic_fast_model,
        call_type="context_resolve",
        input_tokens=response.usage.input_tokens,
        output_tokens=response.usage.output_tokens,
        duration_seconds=time.monotonic() - t0,
    )

    answer = response.content[0].text.strip()
    # Handle markdown-wrapped JSON
    if answer.startswith("```"):
        answer = answer.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
    result = json.loads(answer)
    if not isinstance(result, dict):
        raise ValueError(f"the router answered {answer!r}")
    return result


async def _matched(existing, reason: str) -> dict:
    """The message goes in a thread that is already open: wake it, and say so."""
    logger.info(f"Context routing: stayed in '{existing.label}' ({existing.id}) — {reason}")
    # What keeps a thread from going dormant, and what wakes one that has (MAG-12).
    # Best-effort like the rest: the thread is resolved either way.
    try:
        await context_repo.touch(str(existing.id))
    except Exception as e:
        logger.warning(f"Could not mark context {existing.id} as spoken in: {e}")
    # The summary travels with the event: the Mind panel replaces the whole context when
    # one arrives, so leaving it out would blank the line the panel is showing on the very
    # next message (MAG-11).
    return {
        "action": "matched",
        "id": str(existing.id),
        "label": existing.label,
        "status": "active",
        "summary": existing.summary,
    }


async def _created(user_id: str, label: str, reason: str) -> dict:
    ctx = await context_repo.create(user_id, label)
    logger.info(f"Context routing: opened '{label}' ({ctx.id}) — {reason}")
    return {"action": "created", "id": str(ctx.id), "label": label, "status": "active", "summary": None}


def _own(contexts: list, context_id) -> object | None:
    """The thread the model named, if it is one of this user's own (MAG-203)."""
    if not context_id:
        return None
    existing = next((c for c in contexts if str(c.id) == str(context_id)), None)
    if existing is None:
        logger.warning(f"Context router returned an id outside the user's contexts: {context_id!r}")
    return existing


async def resolve_context(client, text: str, user_id: str, *, message_id: str | None = None) -> dict | None:
    """Place `text` in one of the user's open threads, or open a new one for it.

    Returns what the Mind panel reads off a `context_update` event — `action`, `id`,
    `label`, `status` and the thread's `summary` — or `None` when there is no model to
    ask or the call failed, which leaves the caller without a thread rather than without
    an answer.

    `text` is whatever is about to be written in the thread: the user's message on the
    chat path, and on the proaction path the message Maggie is about to send unprompted,
    since there is no user message to route (MAG-14).

    `message_id` is the stored user message being routed, on the chat path. It is what
    lets the router see the discussion that message follows (MAG-341): within
    `CONTINUITY_WINDOW` of the previous message, the message stays in the thread in
    progress unless the user asks to change subject or the router finds it clearly
    unrelated — and any answer that settles nothing keeps it there. Before this, the
    router saw the message alone, and « et où l'as-tu noté du coup » named nothing it
    could tie to a thread: one discussion became five threads in nine minutes.
    """
    if client is None:
        return None

    try:
        contexts = await context_repo.find_active(user_id)
    except Exception as e:
        logger.warning(f"Could not load the open threads to route into: {e}")
        return None

    try:
        discussion = await _discussion_in_progress(user_id, message_id, contexts) if message_id else None
        if discussion is None:
            return await _route_freely(client, text, user_id, contexts)
        if asks_to_change_subject(text):
            return await _route_away(client, text, user_id, contexts, discussion)
        return await _route_within(client, text, user_id, contexts, discussion)
    except Exception as e:
        # A thread that could not be opened: the caller answers without one.
        logger.warning(f"Context resolution failed, continuing without context: {e}")
        return None


async def _route_freely(client, text: str, user_id: str, contexts: list) -> dict | None:
    """No discussion going on: the router places the message wherever it fits."""
    prompt = f"Contextes existants :\n{_thread_lines(contexts, _now())}\n\nMessage : {text}"
    try:
        result = await _ask_router(client, ROUTER_SYSTEM_PROMPT, prompt)
        # The id comes from the model, not from us: only one of this user's own contexts
        # may be written on the message. Anything else — a hallucinated id, one copied
        # from another user's thread — is a new topic (MAG-203).
        existing = _own(contexts, result.get("context_id"))
        if existing is not None:
            return await _matched(existing, "the router tied it to this thread")
        return await _created(user_id, result.get("label") or text[:60], "the router saw a new subject")
    except Exception as e:
        logger.warning(f"Context resolution failed, continuing without context: {e}")
        return None


async def _route_within(client, text: str, user_id: str, contexts: list, discussion: _Discussion) -> dict | None:
    """A discussion is going on: the message continues it unless the router clearly says otherwise."""
    current = discussion.context
    prompt = (
        f"Discussion en cours :\n{discussion.excerpt}\n\n"
        f"Contextes existants :\n{_thread_lines(contexts, _now(), str(current.id))}\n\n"
        f"Message : {text}"
    )
    try:
        result = await _ask_router(client, CONTINUITY_SYSTEM_PROMPT, prompt)
    except Exception as e:
        return await _matched(current, f"discussion in progress, the router failed ({e})")

    context_id = result.get("context_id")
    if context_id is not None and str(context_id) == str(current.id):
        return await _matched(current, "discussion in progress, the router tied it to it")
    existing = _own(contexts, context_id)
    if existing is not None:
        return await _matched(existing, "the router tied it to another open thread")
    if context_id is None and result.get("label"):
        return await _created(user_id, result["label"], "the router found no link with the discussion")
    # An unknown id, or a new subject with no name: nothing settled, so nothing moves.
    return await _matched(current, "discussion in progress, the router settled nothing")


async def _route_away(client, text: str, user_id: str, contexts: list, discussion: _Discussion) -> dict | None:
    """The user asked to change subject: anywhere but the thread they are leaving."""
    current_id = str(discussion.context.id)
    others = [ctx for ctx in contexts if str(ctx.id) != current_id]
    prompt = f"Contextes existants :\n{_thread_lines(others, _now())}\n\nMessage : {text}"
    try:
        result = await _ask_router(client, SWITCH_SYSTEM_PROMPT, prompt)
    except Exception as e:
        logger.warning(f"Context router failed on a change of subject: {e}")
        result = {}

    existing = _own(others, result.get("context_id"))
    if existing is not None:
        return await _matched(existing, "the user asked to change subject, back to an open thread")
    return await _created(user_id, result.get("label") or text[:60], "the user asked to change subject")


async def route_message(client, text: str, user_id: str, *, message_id: str | None = None) -> dict | None:
    """Route `text` into a thread, and write that thread on the message it came from.

    Both chat paths route before they build anything — the history is the thread's messages
    and the system prompt names it, so neither can be assembled first (MAG-13). The tagging
    is the part that belongs to a stored message: the user's message is already in the
    database when the turn starts, so the thread it lands in is written on it afterwards.

    A tag that could not be written costs the thread one message — the summary reads
    `context_id` — but the context itself is resolved, and the turn about to run will carry
    it. Not a reason to answer without one.
    """
    resolution = await resolve_context(client, text, user_id, message_id=message_id)
    if resolution is None:
        return None

    if message_id:
        try:
            await message_repo.update_context(message_id, resolution["id"])
        except Exception as e:
            logger.warning(f"Could not tag message {message_id} with its context: {e}")

    return resolution

"""The first layer of the memory: every conversation, whole, never rewritten.

A thread (`context_id`) is one file, `Conversations/AAAA/MM/AAAA-MM-JJ HHhMM — <sujet>.md`, dated at the
minute of its first message. Each message is added at the end with its own minute, an anchor
(`<!-- msg: <id> -->`) that tells a later pass it is already there, and its text as it was said. Nothing
above is ever touched: `MemoryService.write` refuses a conversation whose new text does not start with the
old one. This is written by the code after each answer, not by a tool the model would have to remember to
call — a phrase said in passing is in the file whatever the model did with it.

A question is stored before its thread is known (the router answers while the turn runs), so a pass is made
when the *answer* is stored: it adds every message of the thread the file does not hold yet. Messages with
no thread at all go to a file of their day.
"""

import asyncio
import logging
import re
from datetime import UTC, datetime
from typing import Any
from zoneinfo import ZoneInfo

import yaml

from app.memory import paths
from app.memory.service import MemoryService, memory_service
from app.memory.ulid import is_ulid

logger = logging.getLogger(__name__)

ROLE_NAMES = {"user": "Propriétaire", "assistant": "Maggie"}
NO_THREAD_SUBJECT = "Hors d'un fil"
_KEY = re.compile(r"^contexte:\s*(\S+)\s*$", re.MULTILINE)
_HEAD = 600

_locks: dict[tuple[str, str], asyncio.Lock] = {}
_files: dict[str, dict[str, str]] = {}


def anchor(message_id: str) -> str:
    return f"<!-- msg: {message_id} -->"


def _as_local(when: datetime | None, tz: ZoneInfo) -> datetime:
    when = when or datetime.now(UTC)
    if when.tzinfo is None:
        when = when.replace(tzinfo=UTC)
    return when.astimezone(tz)


def _tool_names(blocks: Any) -> list[str]:
    names: list[str] = []
    for turn in blocks if isinstance(blocks, list) else []:
        content = turn.get("content") if isinstance(turn, dict) else None
        for block in content if isinstance(content, list) else []:
            if isinstance(block, dict) and block.get("type") == "tool_use" and block.get("name"):
                names.append(str(block["name"]))
    return list(dict.fromkeys(names))


def render_message(msg: Any, tz: ZoneInfo) -> str:
    when = _as_local(msg.created_at, tz)
    role = ROLE_NAMES.get(msg.role, msg.role)
    lines = [f"## {when:%Y-%m-%d %Hh%M} — {role}", anchor(msg.id), "", msg.content.strip()]
    tools = _tool_names(getattr(msg, "blocks", None))
    if tools:
        lines += ["", f"_Outils appelés : {', '.join(tools)}_"]
    if getattr(msg, "interrupted", False):
        lines += ["", "_Réponse coupée par le propriétaire : seul ce qui précède a été dit._"]
    return "\n".join(lines) + "\n\n"


def _header(key: str, subject: str, first: datetime) -> str:
    head = yaml.safe_dump(
        {"contexte": key, "sujet": subject, "debut": first.isoformat(timespec="minutes")},
        allow_unicode=True,
        sort_keys=False,
        default_flow_style=False,
    ).rstrip("\n")
    return (
        f"---\n{head}\n---\n\n# {subject}\n\n"
        "> Conversation entière : on n'y ajoute qu'à la suite, rien de ce qui est écrit plus haut ne change.\n\n"
    )


def _lock(user_id: str, key: str) -> asyncio.Lock:
    return _locks.setdefault((user_id, key), asyncio.Lock())


def _scan(service: MemoryService, user_id: str) -> dict[str, str]:
    folder = service.folder(user_id)
    found: dict[str, str] = {}
    prefix = f"{paths.CONVERSATIONS_DIR}/"
    for rel in folder.files():
        if not rel.startswith(prefix):
            continue
        text = folder.read(rel)
        match = _KEY.search(text[:_HEAD]) if text else None
        if match:
            found.setdefault(match.group(1), rel)
    return found


def find_file(service: MemoryService, user_id: str, key: str) -> str | None:
    """The conversation file of a thread: remembered, else read from the heads of the files themselves."""
    known = _files.get(user_id)
    rel = known.get(key) if known is not None else None
    if rel is not None and service.folder(user_id).exists(rel):
        return rel
    known = _files[user_id] = _scan(service, user_id)
    return known.get(key)


async def append_messages(
    user_id: str,
    key: str,
    subject: str,
    messages: list[Any],
    *,
    tz: ZoneInfo,
    service: MemoryService | None = None,
) -> str | None:
    """Add to the thread's file the messages it does not hold yet. Returns the file, None when nothing was written."""
    service = service or memory_service
    ordered = sorted(messages, key=lambda m: _as_local(m.created_at, tz))
    if not ordered:
        return None
    async with _lock(user_id, key):
        rel = find_file(service, user_id, key)
        current = service.folder(user_id).read(rel) if rel else None
        fresh = [m for m in ordered if current is None or anchor(m.id) not in current]
        if not fresh:
            return rel
        blocks = "".join(render_message(m, tz) for m in fresh)
        if rel is None or current is None:
            first = _as_local(fresh[0].created_at, tz)
            rel = paths.conversation_relative(first, subject, service.folder(user_id).files())
            text = _header(key, subject, first) + blocks
        else:
            text = current + ("" if current.endswith("\n") else "\n") + blocks
        await service.write(user_id, rel, text, conversation=True, journal=False)
        _files.setdefault(user_id, {})[key] = rel
        return rel


async def record(msg: Any) -> None:
    """Called when a message is stored: an answer brings its thread's missing messages into the file."""
    if msg.role != "assistant":
        return
    await record_thread(msg.user_id, msg.context_id, last=msg)


async def record_thread(user_id: str, context_id: str | None, *, last: Any = None) -> None:
    """Bring into the file of a thread (or of the day, when there is none) the messages it does not hold yet.

    Called when an answer is stored, when the router tags a question with its thread and before a memory
    tool runs, so what was just said is on file before anything cites it. Never raises: the answer matters more.
    """
    service = memory_service
    if not service.enabled or not is_ulid(user_id):
        return
    try:
        await _record(service, user_id, context_id, last)
    except Exception:
        logger.exception("Could not write the conversation of %s (thread %s) into its file", user_id, context_id)


async def _record(service: MemoryService, user_id: str, context_id: str | None, last: Any) -> None:
    from app.db.context_repository import context_repo
    from app.db.message_repository import message_repo
    from app.user_timezone import resolve_user_timezone

    tz = await resolve_user_timezone(user_id)
    if context_id:
        context = await context_repo.get(context_id)
        subject = str(context.label) if context else NO_THREAD_SUBJECT
        thread = await message_repo.find_by_context(context_id, user_id=user_id, limit=500)
        key = context_id
    else:
        today = _as_local(last.created_at if last is not None else None, tz).date()
        recent = await message_repo.find_recent(user_id, 40)
        thread = [m for m in recent if m.context_id is None and _as_local(m.created_at, tz).date() == today]
        subject = NO_THREAD_SUBJECT
        key = f"jour-{today:%Y-%m-%d}"
    if last is not None and all(m.id != last.id for m in thread):
        thread.append(last)
    await append_messages(user_id, key, subject, thread, tz=tz, service=service)

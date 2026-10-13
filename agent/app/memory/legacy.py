"""Bring what Maggie kept before the two layers into the memory folder: `python -m app.memory.legacy [--user <ULID>]`.

The message history becomes conversation files (one per thread, one per day for what no thread holds), whole
and timestamped. The old `memory` rows become fiches in a folder of their own: nothing says where they came
from, so each claim is `déduit` and its source says « ancienne mémoire (sans source) » with the date it was
recorded — never a made-up conversation. A later pass of Maggie can re-source them when she finds the exchange.

Safe to run again: a thread already on file is left alone, a title already taken is skipped, and the journal
records when the import ended, so a fiche the owner deleted afterwards does not come back.
"""

import argparse
import asyncio
import logging
import re
import sys
from dataclasses import dataclass
from datetime import date

from app.db.context_repository import context_repo
from app.db.memory_repository import memory_repo
from app.db.message_repository import message_repo
from app.memory import conversations, fiches, paths
from app.memory.service import MemoryService, memory_service
from app.memory.ulid import is_ulid
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

LEGACY_FOLDER = "Ancienne mémoire"
DONE_MARK = "reprise de l'ancienne mémoire terminée"
MAX_THREAD_MESSAGES = 100000

_SENTENCES = re.compile(r"(?<=[.!?])\s+")
_TITLE_WORDS = 18

_done: set[str] = set()


@dataclass
class Report:
    conversations: int = 0
    fiches: int = 0
    skipped: int = 0


def _title_of(first_sentence: str) -> str:
    words = first_sentence.strip().rstrip(".!?…").split()
    return " ".join(words[:_TITLE_WORDS])


def _claims(content: str) -> list[str]:
    """The content as short claims: sentences, packed up to the length of one claim."""
    packed: list[str] = []
    for sentence in (s.strip() for s in _SENTENCES.split(" ".join(content.split()))):
        if not sentence:
            continue
        if packed and len(packed[-1]) + 1 + len(sentence) <= fiches.MAX_CLAIM:
            packed[-1] += " " + sentence
        else:
            packed.append(sentence[: fiches.MAX_CLAIM])
    return packed


async def _import_conversations(service: MemoryService, user_id: str, report: Report) -> None:
    tz = await resolve_user_timezone(user_id)
    for context in await context_repo.find_by_user(user_id):
        key = str(context.id)
        if conversations.find_file(service, user_id, key) is not None:
            continue
        thread = await message_repo.find_by_context(key, user_id=user_id, limit=MAX_THREAD_MESSAGES)
        if await conversations.append_messages(user_id, key, str(context.label), thread, tz=tz, service=service):
            report.conversations += 1
    by_day: dict[str, list] = {}
    for message in await message_repo.find_without_context(user_id):
        day = conversations._as_local(message.created_at, tz).date()
        by_day.setdefault(f"jour-{day:%Y-%m-%d}", []).append(message)
    for key, messages in by_day.items():
        if conversations.find_file(service, user_id, key) is not None:
            continue
        subject = conversations.NO_THREAD_SUBJECT
        if await conversations.append_messages(user_id, key, subject, messages, tz=tz, service=service):
            report.conversations += 1


async def _import_fiches(service: MemoryService, user_id: str, report: Report) -> None:
    today = date.today()
    for row in await memory_repo.find_by_user(user_id):
        claims = _claims(str(row.content or ""))
        if not claims:
            continue
        recorded = row.created_at.strftime("%d/%m/%Y") if row.created_at else "date inconnue"
        source = f"{fiches.LEGACY_SOURCE} (sans source), enregistrée le {recorded}"
        title = _title_of(_SENTENCES.split(claims[0])[0])
        taken = service.folder(user_id).files()
        try:
            title = paths.fiche_relative([LEGACY_FOLDER], title, taken).rsplit("/", 1)[-1].removesuffix(paths.EXTENSION)
        except paths.UnsafePath:
            report.skipped += 1
            continue
        try:
            await fiches.write_fiche(
                service,
                user_id,
                titre=title,
                resume=f"Repris de l'ancienne mémoire : {title}",
                dossiers=[LEGACY_FOLDER],
                affirmations=[{"texte": claim, "provenance": "déduit", "source": source} for claim in claims],
                today=today,
                allow_legacy=True,
                reason="reprise de l'ancienne mémoire",
            )
            report.fiches += 1
        except (fiches.FicheError, paths.UnsafePath):
            report.skipped += 1


def already_done(service: MemoryService, user_id: str) -> bool:
    journal = service.folder(user_id).read(paths.JOURNAL) or ""
    return DONE_MARK in journal


async def import_user(user_id: str, service: MemoryService | None = None) -> Report:
    service = service or memory_service
    report = Report()
    if already_done(service, user_id):
        return report
    await _import_conversations(service, user_id, report)
    await _import_fiches(service, user_id, report)
    await service.journal(
        user_id,
        f"{DONE_MARK} : {report.conversations} conversation(s), {report.fiches} fiche(s), {report.skipped} ignorée(s)",
    )
    return report


async def import_once(user_id: str) -> None:
    """Before the first turn of a user in this process: the import, if it has not been done. Never raises."""
    if user_id in _done or not memory_service.enabled or not is_ulid(user_id):
        return
    try:
        await import_user(user_id)
        _done.add(user_id)
    except Exception:
        logger.exception("Could not bring the old memory of %s into the folder", user_id)


async def run(user_id: str | None) -> int:
    if not memory_service.enabled:
        print("The memory folder is not configured (MEMORY_PROVIDER): nothing to import.", file=sys.stderr)
        return 1
    users = [user_id] if user_id else sorted(u for u in await memory_repo.user_ids() if is_ulid(u))
    for user in users:
        await memory_service.sync_user(user)
        report = await import_user(user)
        await memory_service.sync_user(user)
        print(f"{user}: {report.conversations} conversation(s), {report.fiches} fiche(s), {report.skipped} skipped.")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Bring the old memory and the message history into the memory folder.")
    parser.add_argument("--user", help="only this user (ULID); default: every user")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s: %(message)s")
    sys.exit(asyncio.run(run(args.user)))


if __name__ == "__main__":
    main()

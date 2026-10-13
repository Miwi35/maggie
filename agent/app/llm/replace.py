"""Replace the user's last message by a corrected one (MAG-320).

A message sent with a mistake — « à elle » for « Ael » — is taken back, corrected and sent
again; the turn it opened goes away, the question and its answer, from the thread and from
what the model reads. Two things survive it, because they happened:

  - **what the turn's tools did**: an event created is not undone by the replacement, so the
    corrected message carries a line saying so, and Maggie says it in one sentence instead of
    creating the event a second time;
  - nothing else. A card still waiting for approval is abandoned — denied, so it leaves the
    screen — because the request it asked about is no longer the one being made.
"""

import json
import logging
from typing import Any

from fastapi import HTTPException

from app.db.message_repository import message_repo
from app.db.pending_action_model import PendingActionStatus
from app.db.pending_action_repository import pending_action_repo
from app.llm.streaming import PENDING_APPROVAL_STATUS, answer_message_id, tool_result_status
from app.llm.turns import turn_runner

logger = logging.getLogger(__name__)

MAX_ACTIONS = 10
# Reading changes nothing: « j'avais déjà consulté… » is not an action to keep or delete.
READ_ONLY_PREFIXES = ("get_", "list_", "search_", "find_", "read_")
MAX_ACTION_CHARS = 200


def _calls(blocks: Any) -> list[tuple[str, dict, str]]:
    """`(tool, input, result)` of every call stored on a turn, in order."""
    if not isinstance(blocks, list):
        return []
    calls: list[tuple[str, dict, str]] = []
    for position in range(0, len(blocks) - 1, 2):
        uses, results = blocks[position], blocks[position + 1]
        if not isinstance(uses, dict) or not isinstance(results, dict):
            continue
        if not isinstance(uses.get("content"), list) or not isinstance(results.get("content"), list):
            continue
        answers = {r.get("tool_use_id"): r.get("content") for r in results["content"] if isinstance(r, dict)}
        for use in uses["content"]:
            if isinstance(use, dict):
                inputs = use.get("input") if isinstance(use.get("input"), dict) else {}
                calls.append((str(use.get("name") or ""), inputs, str(answers.get(use.get("id")) or "")))
    return calls


def done_actions(blocks: Any) -> list[str]:
    """The tool calls of a turn that went through, one short line each — what a replacement leaves behind."""
    lines: list[str] = []
    for name, tool_input, result in _calls(blocks):
        if tool_result_status(result) != "success" or name.startswith(READ_ONLY_PREFIXES):
            continue
        line = f"{name}({json.dumps(tool_input, ensure_ascii=False, separators=(',', ':'))})"
        lines.append(line if len(line) <= MAX_ACTION_CHARS else line[: MAX_ACTION_CHARS - 1] + "…")
    return lines[:MAX_ACTIONS]


def pending_approvals(blocks: Any) -> list[str]:
    """The ids of the approval cards a turn put in front of the user."""
    ids: list[str] = []
    for _, _, result in _calls(blocks):
        if tool_result_status(result) != PENDING_APPROVAL_STATUS:
            continue
        try:
            approval_id = json.loads(result).get("approval_id")
        except (json.JSONDecodeError, AttributeError):
            continue
        if isinstance(approval_id, str) and approval_id:
            ids.append(approval_id)
    return ids


async def abandon_approvals(blocks: Any) -> None:
    """Deny the cards a withdrawn or stopped turn left waiting, so they leave the screen."""
    for approval_id in pending_approvals(blocks):
        try:
            await pending_action_repo.decide(approval_id, PendingActionStatus.DENIED)
        except Exception as exc:
            logger.warning(f"Could not abandon the approval {approval_id}: {exc}")


def carried_over_note(actions: list[str]) -> str | None:
    """What the model is told on the corrected message, or nothing when the replaced turn did nothing."""
    if not actions:
        return None
    return (
        "[Message corrigé : il remplace le message précédent, retiré de la conversation. Pendant ce tour tu avais "
        f"déjà fait : {' ; '.join(actions)}. Ces actions n'ont pas été défaites. Si elles correspondent à cette "
        "demande, dis-le en une ligne (par exemple « j'avais déjà créé X, je le garde ou je le supprime ? ») "
        "au lieu de les refaire.]"
    )


async def withdraw_turn(user_id: str, message_id: str) -> str | None:
    """Take back the user's last message and its answer; returns the note the replacement carries.

    A message that is gone already (the replacement was sent twice) is nothing to take back.
    Only the last message can be replaced: a thread does not go back further than that.
    """
    question = await message_repo.get(message_id)
    if question is None:
        return None
    if question.user_id != user_id:
        raise HTTPException(status_code=404, detail="Message not found")
    if question.role != "user":
        raise HTTPException(status_code=400, detail="Only a message of the user can be replaced")
    last = await message_repo.find_last(user_id, role="user")
    if last is None or last.id != question.id:
        raise HTTPException(status_code=400, detail="Only the last message can be replaced")

    # Before reading the answer: a turn still running stores what it did as it winds down.
    await turn_runner.stop(question.id)
    answer = await message_repo.get(answer_message_id(question.id))
    blocks = answer.blocks if answer is not None else None

    await abandon_approvals(blocks)

    withdrawn = [question.id] + ([answer.id] if answer is not None else [])
    await message_repo.delete_many_for_user(withdrawn, user_id)
    return carried_over_note(done_actions(blocks))

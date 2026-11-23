"""What a turn's tool calls were, kept so the next turn can still read them (MAG-211).

MAG-13 built the conversation sent to the model out of the thread's messages, but a
message is a `role` and a TEXT `content`: the `tool_use` and `tool_result` blocks of a
turn died with it. At the next message Maggie saw « voici tes courses » and no sign of
the `get_grocery_list` that had taught her — so she called it again for something she
already knew, and could not refer to a line she had read.

This module is the two halves of that fix, and nothing else: `record()` turns one round
of tools into the pair of turns the API takes it in, which is what gets stored on the
message; `replay()` reads that back, and is deliberately strict.

Strict, because the API's own rule is unforgiving: a `tool_result` must follow
immediately the `tool_use` carrying its `tool_use_id`, or the whole call is rejected.
A single malformed round would therefore cost the user her answer, not just her context
— so anything this module cannot vouch for whole is dropped whole, and the message is
left with the text it has always had.
"""

import copy
import json
import logging
from typing import Any

from app.llm.envelope import wrap_data

logger = logging.getLogger(__name__)

# A tool result is unbounded — a grocery list is a kilobyte, a month of transactions is
# not — and it is replayed on every later message of the thread until it leaves the
# window. This is the ceiling per result, generous enough that the shape of a real answer
# survives it and low enough that a round cannot eat the budget on its own.
TOOL_RESULT_MAX_CHARS = 8000
TRUNCATION_MARK = "… [résultat tronqué]"

# What a call left without an answer by an interrupted turn is given (MAG-872). A text the
# method of using tools quotes: change it in both places.
INTERRUPTED_RESULT = json.dumps(
    {
        "error": "interrupted",
        "detail": "Appel interrompu avant sa réponse : issue inconnue. Vérifier son effet avant de le refaire.",
    },
    ensure_ascii=False,
)


def record(calls: list[dict]) -> list[dict]:
    """One round of tools, as the two turns the conversation carries it in.

    `calls` is what the loop just ran: `{"id", "name", "input", "result"}` per call, in
    the order the model asked for them — which is the order the results have to go back
    in. A call missing its id is skipped: without it the result could never be paired,
    and the API would refuse the round rather than ignore it.
    """
    uses: list[dict] = []
    results: list[dict] = []
    for call in calls:
        tool_use_id = call.get("id")
        if not isinstance(tool_use_id, str) or not tool_use_id:
            logger.warning(f"Tool call {call.get('name')!r} has no id: its blocks cannot be replayed")
            continue
        uses.append(
            {
                "type": "tool_use",
                "id": tool_use_id,
                "name": str(call.get("name") or ""),
                "input": call.get("input") if isinstance(call.get("input"), dict) else {},
            }
        )
        results.append(
            {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": _capped(call.get("result")),
            }
        )

    if not uses:
        return []

    return [
        {"role": "assistant", "content": uses},
        {"role": "user", "content": results},
    ]


def record_partial(uses: list[dict], done: list[dict]) -> list[dict]:
    """A round in progress: the assistant turn of every call asked for, then the results so far.

    `uses` are `{"id", "name", "input"}` as the model asked for them, `done` the calls that
    have run, in `record()`'s shape. With nothing done there is no user turn yet.
    """
    turns = record([{**use, "result": ""} for use in uses])
    if not turns:
        return []
    results = record(done)[1:]
    return [turns[0], *results]


def replay(blocks: Any) -> list[dict]:
    """The stored rounds as turns to send, a call without a result closed as interrupted.

    A column holding something that is not a round, a result truncated by a hand-written
    `UPDATE`, an id used twice: each of them is a rejected API call, which the user reads as
    « une erreur est survenue ». Those are dropped whole, and the message keeps the text it
    has always had. A call whose result never came is another matter: it is closed with
    `INTERRUPTED_RESULT`, so the model knows it may have acted (MAG-872).
    """
    return close_interrupted(blocks)


def close_interrupted(blocks: Any) -> list[dict]:
    """The stored turns, every call that has no result answered with `INTERRUPTED_RESULT`.

    The results of a round must be a prefix of its calls (same ids, same order: the loop runs
    them in order); a last assistant turn with no user turn after it is a round whose calls
    all went unanswered. Anything else that does not hold gives `[]`.
    """
    if not isinstance(blocks, list) or not blocks:
        return []

    turns: list[dict] = []
    seen: set[str] = set()
    position = 0
    while position < len(blocks):
        result_turn = blocks[position + 1] if position + 1 < len(blocks) else None
        round_turns = _round(blocks[position], result_turn, seen)
        if round_turns is None:
            logger.warning(f"Round {position // 2 + 1} of the replayed tool blocks is malformed: all dropped")
            return []
        turns.extend(round_turns)
        position += 2
    return turns


def _round(call_turn: Any, result_turn: Any, seen: set[str]) -> list[dict] | None:
    """One stored round, rebuilt and checked — `None` as soon as anything does not hold."""
    uses = _content(call_turn, role="assistant")
    results = _content(result_turn, role="user") if result_turn is not None else []
    if uses is None or results is None or len(results) > len(uses):
        return None

    rebuilt_uses: list[dict] = []
    rebuilt_results: list[dict] = []
    for index, use in enumerate(uses):
        tool_use_id = use.get("id")
        if not _is_filled(tool_use_id) or tool_use_id in seen:
            return None
        if use.get("type") != "tool_use" or not _is_filled(use.get("name")) or not isinstance(use.get("input"), dict):
            return None
        seen.add(tool_use_id)
        rebuilt_uses.append({"type": "tool_use", "id": tool_use_id, "name": use["name"], "input": use["input"]})

        if index >= len(results):
            rebuilt_results.append({"type": "tool_result", "tool_use_id": tool_use_id, "content": INTERRUPTED_RESULT})
            continue
        # Paired by position *and* by id: the API reads the id, and a round whose two
        # halves disagree on it is the failure this check exists for.
        result = results[index]
        if result.get("tool_use_id") != tool_use_id or result.get("type") != "tool_result":
            return None
        if not isinstance(result.get("content"), str):
            return None
        # Capped again on the way out, so a row written before the ceiling moved — or by
        # anything but `record()` — cannot blow the prompt budget of every later message.
        rebuilt_results.append(
            {"type": "tool_result", "tool_use_id": tool_use_id, "content": _capped(result["content"])}
        )

    return [
        {"role": "assistant", "content": rebuilt_uses},
        {"role": "user", "content": rebuilt_results},
    ]


def _content(turn: Any, *, role: str) -> list[dict] | None:
    """The blocks of a stored turn, if it is one of `role` and holds at least one dict."""
    if not isinstance(turn, dict) or turn.get("role") != role:
        return None
    content = turn.get("content")
    if not isinstance(content, list) or not content:
        return None
    if not all(isinstance(block, dict) for block in content):
        return None
    return content


def _is_filled(value: Any) -> bool:
    return isinstance(value, str) and bool(value)


def _capped(result: Any) -> str:
    """A tool's result as the text the API takes, cut to the ceiling with the cut announced.

    Announced on purpose: a silently shortened JSON reads to the model as a complete one,
    and she would answer from a list she thinks she saw the end of.
    """
    text = result if isinstance(result, str) else str(result)
    if len(text) <= TOOL_RESULT_MAX_CHARS:
        return text
    return text[:TOOL_RESULT_MAX_CHARS] + TRUNCATION_MARK


TOOL_RESULT_SOURCE = "outil"
TRUNCATED_NOTE = "[Résultat tronqué : seul le début est montré. Affinez la requête pour obtenir le reste.]"
OMITTED_RESULT = (
    "[Résultat retiré pour tenir dans la fenêtre : l'appel a bien eu lieu. "
    "Relis-le avec un outil de lecture si tu en as besoin ; ne refais pas une écriture.]"
)


def for_model(result: Any, limit: int) -> str:
    """A tool result as the model reads it: inside the data envelope, cut at `limit`, the cut announced."""
    text = result if isinstance(result, str) else str(result)
    if len(text) > limit:
        return wrap_data(text[:limit], TOOL_RESULT_SOURCE) + "\n" + TRUNCATED_NOTE
    return wrap_data(text, TOOL_RESULT_SOURCE)


def model_turns(turns: list[dict]) -> list[dict]:
    """A copy of recorded turns with every `tool_result` as the model reads it; the code keeps the raw ones."""
    out = copy.deepcopy(turns)
    for turn in out:
        if turn.get("role") != "user" or not isinstance(turn.get("content"), list):
            continue
        for block in turn["content"]:
            if not isinstance(block, dict) or block.get("type") != "tool_result":
                continue
            content = block.get("content")
            if content == INTERRUPTED_RESULT:
                continue
            if isinstance(content, str) and content.endswith(TRUNCATION_MARK):
                head = wrap_data(content[: -len(TRUNCATION_MARK)], TOOL_RESULT_SOURCE)
                block["content"] = head + "\n" + TRUNCATED_NOTE
            else:
                block["content"] = for_model(content, TOOL_RESULT_MAX_CHARS)
    return out


def without_old_results(messages: list) -> bool:
    """Replace, in place, the results of every round but the last by `OMITTED_RESULT`; whether anything changed."""
    rounds = [
        i
        for i, turn in enumerate(messages)
        if isinstance(turn, dict)
        and turn.get("role") == "user"
        and isinstance(turn.get("content"), list)
        and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in turn["content"])
    ]
    changed = False
    for i in rounds[:-1]:
        blocks = []
        for block in messages[i]["content"]:
            is_result = isinstance(block, dict) and block.get("type") == "tool_result"
            if is_result and block.get("content") != OMITTED_RESULT:
                block = {**block, "content": OMITTED_RESULT}
                changed = True
            blocks.append(block)
        messages[i] = {**messages[i], "content": blocks}
    return changed

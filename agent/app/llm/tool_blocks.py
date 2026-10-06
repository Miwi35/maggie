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

import logging
from typing import Any

logger = logging.getLogger(__name__)

# A tool result is unbounded — a grocery list is a kilobyte, a month of transactions is
# not — and it is replayed on every later message of the thread until it leaves the
# window. This is the ceiling per result, generous enough that the shape of a real answer
# survives it and low enough that a round cannot eat the budget on its own.
TOOL_RESULT_MAX_CHARS = 8000
TRUNCATION_MARK = "… [résultat tronqué]"


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


def replay(blocks: Any) -> list[dict]:
    """The stored rounds as turns to send — or nothing at all, if one of them is off.

    Nothing at all is the whole point. A `tool_use` whose result went missing, a column
    holding something that is not a round, a result truncated by a hand-written
    `UPDATE`: each of them is a rejected API call, which the user reads as « une erreur
    est survenue ». The message still has its text, and the text is what she said.
    """
    if not isinstance(blocks, list) or not blocks:
        return []

    # Rounds, always: the loop appends the call and its result together, so an odd number
    # of turns means something else wrote this column.
    if len(blocks) % 2 != 0:
        logger.warning(f"Replayed tool blocks are not whole rounds ({len(blocks)} turns): dropped")
        return []

    turns: list[dict] = []
    for position in range(0, len(blocks), 2):
        round_turns = _round(blocks[position], blocks[position + 1])
        if round_turns is None:
            logger.warning(f"Round {position // 2 + 1} of the replayed tool blocks is malformed: all dropped")
            return []
        turns.extend(round_turns)
    return turns


def _round(call_turn: Any, result_turn: Any) -> list[dict] | None:
    """One stored round, rebuilt and checked — `None` as soon as anything does not hold."""
    uses = _content(call_turn, role="assistant")
    results = _content(result_turn, role="user")
    if uses is None or results is None or len(uses) != len(results):
        return None

    rebuilt_uses: list[dict] = []
    rebuilt_results: list[dict] = []
    for use, result in zip(uses, results, strict=True):
        # Paired by position *and* by id: the API reads the id, and a round whose two
        # halves disagree on it is the failure this check exists for.
        tool_use_id = use.get("id")
        if not _is_filled(tool_use_id) or result.get("tool_use_id") != tool_use_id:
            return None
        if use.get("type") != "tool_use" or result.get("type") != "tool_result":
            return None
        if not _is_filled(use.get("name")) or not isinstance(use.get("input"), dict):
            return None
        if not isinstance(result.get("content"), str):
            return None

        rebuilt_uses.append({"type": "tool_use", "id": tool_use_id, "name": use["name"], "input": use["input"]})
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

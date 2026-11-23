from collections.abc import Callable

from app.llm.questions import asked_question, blocks_of
from app.runtime.loop import ITERATION_LIMIT_MESSAGE, LoopOptions, TurnEnded, run_loop

__all__ = ["DEFAULT_MAX_ITERATIONS", "ITERATION_LIMIT_MESSAGE", "run_tool_loop"]

DEFAULT_MAX_ITERATIONS = 5


async def run_tool_loop(
    system: str | list[dict],
    messages: list,
    tools: list[dict] | None,
    *,
    client,
    tool_router,
    user_id: str | None,
    model: str,
    max_iterations: int = DEFAULT_MAX_ITERATIONS,
    max_tokens: int | None = None,
    effort: str | None = None,
    call_type: str = "chat",
    source: str = "chat",
    context_id: str | None = None,
    review: Callable[[str, list[dict]], str | None] | None = None,
) -> dict:
    """Call Claude, execute the tools it asks for, and loop until it answers with text.

    `system` is a string or a list of system blocks (see `build_system`). `messages` is extended in place.
    `tools=None` (or empty) yields a plain answer without tools. The tool list gets a prompt-cache breakpoint.
    `effort` is the run's `output_config.effort` (`app.llm.models.effort_for`); `None` sends none.
    API errors are not caught: callers decide how to present them.

    `context_id` is the thread this turn belongs to; a call the policy holds back for the
    user's approval carries it, so the result is announced in the same conversation (MAG-4).

    An answer announcing a reminder or something learned that no tool backed this turn is sent
    back to the model once with the fact; then it goes out as written, and the gap is signalled
    (MAG-339, MAG-340, MAG-842, `app.llm.claim_guard`).

    `review(text, tool_calls)` reads an accepted answer back and may return a finding: it goes to the model once,
    as a turn of the same conversation, and the answer that follows goes out as written (MAG-843).

    A response may carry web searches Anthropic ran on its side (`app.llm.web_search`): the
    answer is the text after the last one, closed by the line of the sources it cites (MAG-384).

    The result carries `blocks`: the `tool_use` / `tool_result` rounds this run went
    through, in the shape the API takes them (MAG-211). The caller stores them on the
    message it writes, and the next turn of the thread is sent them back — so a result
    Maggie has already read is not fetched a second time.
    """
    options = LoopOptions.blocking(
        model=model,
        user_id=user_id,
        max_iterations=max_iterations,
        max_tokens=max_tokens,
        effort=effort,
        call_type=call_type,
        source=source,
        context_id=context_id,
        review=review,
        ends_turn=asked_question,
    )
    async for event in run_loop(system, messages, tools, client=client, tool_router=tool_router, options=options):
        if isinstance(event, TurnEnded):
            outcome = event.outcome
    return _outcome(outcome.response, outcome.tool_calls, outcome.blocks, outcome.usage)


def _outcome(response: str, tool_calls: list[dict], blocks: list[dict], usage: dict) -> dict:
    return {
        "response": response,
        "tool_calls": tool_calls,
        "blocks": blocks,
        "ui_blocks": blocks_of(tool_calls),
        "usage": usage,
    }

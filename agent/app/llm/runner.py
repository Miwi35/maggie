import logging
import time

import anthropic

from app.llm.prompt_cache import cache_tools
from app.llm.tool_blocks import record
from app.metrics import TOOL_CALLS, record_llm_usage, usage_kwargs

logger = logging.getLogger(__name__)

DEFAULT_MAX_ITERATIONS = 5
DEFAULT_MAX_TOKENS = 4096
ITERATION_LIMIT_MESSAGE = "I encountered an issue processing your request. Please try again."


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
    max_tokens: int = DEFAULT_MAX_TOKENS,
    call_type: str = "chat",
    source: str = "chat",
    context_id: str | None = None,
) -> dict:
    """Call Claude, execute the tools it asks for, and loop until it answers with text.

    `system` is a string or a list of system blocks (see `build_system`). `messages` is extended in place.
    `tools=None` (or empty) yields a plain answer without tools. The tool list gets a prompt-cache breakpoint.
    API errors are not caught: callers decide how to present them.

    `context_id` is the thread this turn belongs to; a call the policy holds back for the
    user's approval carries it, so the result is announced in the same conversation (MAG-4).

    The result carries `blocks`: the `tool_use` / `tool_result` rounds this run went
    through, in the shape the API takes them (MAG-211). The caller stores them on the
    message it writes, and the next turn of the thread is sent them back — so a result
    Maggie has already read is not fetched a second time.
    """
    tool_calls_made: list[dict] = []
    blocks: list[dict] = []
    cached_tools = cache_tools(tools)

    for _ in range(max_iterations):
        logger.info(f"Calling Claude with {len(tools or [])} tools, {len(messages)} messages")

        t0 = time.monotonic()
        try:
            response = await client.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=system,
                messages=messages,
                tools=cached_tools if cached_tools else anthropic.NOT_GIVEN,
            )
            record_llm_usage(
                model=model,
                call_type=call_type,
                duration_seconds=time.monotonic() - t0,
                **usage_kwargs(response.usage),
            )
        except Exception:
            record_llm_usage(
                model=model,
                call_type=call_type,
                input_tokens=0,
                output_tokens=0,
                duration_seconds=time.monotonic() - t0,
                status="error",
            )
            raise

        logger.info(f"Claude response: stop_reason={response.stop_reason}, blocks={len(response.content)}")

        if response.stop_reason != "tool_use":
            text_response = "".join(block.text for block in response.content if hasattr(block, "text"))
            return {"response": text_response, "tool_calls": tool_calls_made, "blocks": blocks}

        messages.append({"role": "assistant", "content": response.content})

        tool_results = []
        round_calls = []
        for block in response.content:
            if block.type == "tool_use":
                logger.info(f"Tool call: {block.name}({block.input})")
                TOOL_CALLS.labels(tool_name=block.name, source=source).inc()
                result = await tool_router.call_tool(
                    block.name, block.input, user_id=user_id, source=source, context_id=context_id
                )
                tool_calls_made.append({"name": block.name, "input": block.input, "result": result})
                tool_results.append({"type": "tool_result", "tool_use_id": block.id, "content": result})
                round_calls.append({"id": block.id, "name": block.name, "input": block.input, "result": result})

        messages.append({"role": "user", "content": tool_results})
        # The same round, as plain data this time: `response.content` holds the SDK's own
        # block objects, which no column can store.
        blocks.extend(record(round_calls))

    return {"response": ITERATION_LIMIT_MESSAGE, "tool_calls": tool_calls_made, "blocks": blocks}

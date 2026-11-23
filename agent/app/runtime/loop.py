"""The one tool loop: calls the model, runs the tools it asks for, emits the events of the turn (MAG-953).

`run_loop` is the only place that calls the model for a turn of tools. What differs between the blocking chat
(`app.llm.runner`) and the streamed one (`app.llm.streaming`) is chosen by `LoopOptions`, never by a copy of the loop:
the blocking callers aggregate the events into a dict, the streamed chat translates them into AG-UI.

Nothing here imports a domain module: what the turn does with a question (`ends_turn`) or a thread label
(`clean_text`) is given by the caller.
"""

import asyncio
import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

import anthropic

from app.config import settings
from app.llm.claim_guard import ClaimGuard, Verdict
from app.llm.prompt_cache import cache_tools
from app.llm.provider_status import (
    is_context_overflow,
    note_provider_failure,
    note_provider_success,
    retry_delay,
    retry_reason,
    retryable,
)
from app.llm.tool_blocks import INTERRUPTED_RESULT, for_model, model_turns, record, without_old_results
from app.llm.tool_failures import FailureStreak, tool_result_status
from app.llm.web_search import (
    PAUSE_TURN,
    SERVER_TOOL_USE,
    WEB_SEARCH_RESULT,
    answer_text,
    missing_sources,
    search_failed,
    with_sources,
)
from app.metrics import TOOL_CALLS, record_llm_usage, usage_kwargs

logger = logging.getLogger(__name__)

ITERATION_LIMIT_MESSAGE = "Je me suis perdue en route, je n'ai rien enregistré. Vous me redonnez le point de départ ?"
STREAM_SOURCE = "chat_stream"


def _identity(text: str) -> str:
    return text


@dataclass(frozen=True)
class LoopOptions:
    """How a turn runs. `blocking(...)` and `streaming(...)` hold the rules of the two chats as they were."""

    model: str
    user_id: str | None
    source: str
    call_type: str
    max_iterations: int
    max_tokens: int | None = None
    effort: str | None = None
    context_id: str | None = None
    streamed: bool = False
    # Blocking only: reads an accepted answer back once, and may return a finding (MAG-843).
    review: Callable[[str, list[dict]], str | None] | None = None
    # The question a round or a turn ends on, if any: `{"text": ...}`. Given by the caller (it is a domain rule).
    ends_turn: Callable[[list[dict]], dict | None] | None = None
    # Applied to the text of a streamed step (a thread label copied from the history is not an answer, MAG-341).
    clean_text: Callable[[str], str] = _identity
    # Streamed only: whether a cancellation is the owner's stop, which lets the running tool finish.
    stopped: Callable[[], bool] | None = None
    # The answer is read aloud: it carries no line of sources.
    spoken: bool = False

    @classmethod
    def blocking(
        cls,
        *,
        model: str,
        user_id: str | None,
        max_iterations: int,
        max_tokens: int | None = None,
        effort: str | None = None,
        call_type: str = "chat",
        source: str = "chat",
        context_id: str | None = None,
        review: Callable[[str, list[dict]], str | None] | None = None,
        ends_turn: Callable[[list[dict]], dict | None] | None = None,
    ) -> "LoopOptions":
        return cls(
            model=model,
            user_id=user_id,
            source=source,
            call_type=call_type,
            max_iterations=max_iterations,
            max_tokens=max_tokens,
            effort=effort,
            context_id=context_id,
            review=review,
            ends_turn=ends_turn,
        )

    @classmethod
    def streaming(
        cls,
        *,
        model: str,
        user_id: str,
        max_iterations: int,
        effort: str | None = None,
        context_id: str | None = None,
        ends_turn: Callable[[list[dict]], dict | None] | None = None,
        clean_text: Callable[[str], str] = _identity,
        stopped: Callable[[], bool] | None = None,
        spoken: bool = False,
    ) -> "LoopOptions":
        return cls(
            model=model,
            user_id=user_id,
            source=STREAM_SOURCE,
            call_type=STREAM_SOURCE,
            max_iterations=max_iterations,
            effort=effort,
            context_id=context_id,
            streamed=True,
            ends_turn=ends_turn,
            clean_text=clean_text,
            stopped=stopped,
            spoken=spoken,
        )


@dataclass
class TurnState:
    """What the turn has done so far, read by the caller when the turn is cancelled."""

    blocks: list[dict] = field(default_factory=list)
    round_calls: list[dict] = field(default_factory=list)
    answer: str = ""


@dataclass(frozen=True)
class LoopOutcome:
    response: str
    tool_calls: list[dict]
    blocks: list[dict]
    usage: dict
    failed: bool = False


@dataclass(frozen=True)
class StepStarted:
    iteration: int


@dataclass(frozen=True)
class TextDelta:
    text: str


@dataclass(frozen=True)
class ToolUseStarted:
    id: str
    name: str
    server: bool


@dataclass(frozen=True)
class WebSearchEnded:
    tool_use_id: str
    failed: bool


@dataclass(frozen=True)
class StepEnded:
    """The text of the step, cleaned and stripped; empty when the step said nothing."""

    text: str


@dataclass(frozen=True)
class ToolCallsPlanned:
    uses: list[dict]


@dataclass(frozen=True)
class ToolCallEnded:
    id: str
    name: str
    input: dict
    result: str
    status: str


@dataclass(frozen=True)
class RoundEnded:
    calls: list[dict]


@dataclass(frozen=True)
class QuestionAsked:
    text: str


@dataclass(frozen=True)
class SourcesAdded:
    line: str


@dataclass(frozen=True)
class ModelFailed:
    error: Exception


@dataclass(frozen=True)
class ModelRetrying:
    """The model failed in a way that clears by itself: the step goes again after `delay_s` (MAG-971)."""

    attempt: int
    attempts: int
    delay_s: float
    reason: str


@dataclass(frozen=True)
class TurnEnded:
    outcome: LoopOutcome


LoopEvent = (
    StepStarted
    | TextDelta
    | ToolUseStarted
    | WebSearchEnded
    | StepEnded
    | ToolCallsPlanned
    | ToolCallEnded
    | RoundEnded
    | QuestionAsked
    | SourcesAdded
    | ModelFailed
    | ModelRetrying
    | TurnEnded
)


def _back_the_guard(guard: ClaimGuard, resumed: list[dict]) -> None:
    """The calls a resumed turn had made count for the claim guard like any other of the turn."""
    asked = {use["id"]: use for turn in resumed[::2] for use in turn["content"]}
    for turn in resumed[1::2]:
        for result in turn["content"]:
            if result["content"] != INTERRUPTED_RESULT:
                use = asked[result["tool_use_id"]]
                guard.record(use["name"], result["content"], use["input"])


@dataclass(frozen=True)
class _StepResponse:
    """The last value of `_model_step`: the final message and the raw text streamed. Never emitted by the loop."""

    message: Any
    text: str


async def _model_step(
    client: Any, resolve_client: Callable[[], Awaitable[Any]] | None, request: dict, streamed: bool
) -> AsyncIterator[Any]:
    """The one model call of a step: its streamed events, then `_StepResponse`."""
    step_text = ""
    active = await resolve_client() if resolve_client else client
    if isinstance(active, anthropic.AsyncAnthropic):
        # The loop retries a step itself, visibly (MAG-971); the other calls keep the SDK's retries.
        active = active.with_options(max_retries=0)
    if streamed:
        async with active.messages.stream(**request) as stream:
            async for event in stream:
                if event.type == "content_block_start":
                    block = event.content_block
                    if hasattr(block, "name"):
                        server = block.type == SERVER_TOOL_USE
                        yield ToolUseStarted(block.id, block.name, server)
                        if server:
                            # What was said so far was the model announcing its search: the answer is
                            # the text written after the last one (MAG-384).
                            step_text = ""
                    elif block.type == WEB_SEARCH_RESULT:
                        yield WebSearchEnded(block.tool_use_id, search_failed(block))
                elif event.type == "content_block_delta" and hasattr(event.delta, "text"):
                    step_text += event.delta.text
                    yield TextDelta(event.delta.text)
            response = await stream.get_final_message()
    else:
        response = await active.messages.create(**request)
    yield _StepResponse(response, step_text)


async def run_loop(
    system: str | list[dict],
    messages: list,
    tools: list[dict] | None,
    *,
    client: Any,
    tool_router: Any,
    options: LoopOptions,
    state: TurnState | None = None,
    resumed: Sequence[dict] = (),
    resolve_client: Callable[[], Awaitable[Any]] | None = None,
) -> AsyncIterator[LoopEvent]:
    """Call the model, run the tools it asks for, and loop until it answers with text; emit the events of the turn.

    `messages` is extended in place. `tools=None` (or empty) yields a plain answer without tools; the tool list
    gets a prompt-cache breakpoint. API errors are raised when blocking (callers decide how to present them) and
    become `ModelFailed` when streamed. The last event is always `TurnEnded`.

    An answer announcing a reminder or something learned that no tool backed this turn is sent back to the model
    once with the fact (`app.llm.claim_guard`, MAG-339, MAG-340, MAG-842). A response may carry web searches
    Anthropic ran on its side: the answer is the text after the last one (`app.llm.web_search`, MAG-384).

    `resolve_client`, when given, picks the client at each model call, inside the error handling: a failing
    lookup is a failed call, not a crashed turn.

    `state` is filled as the turn goes: the `tool_use` / `tool_result` rounds in the shape the API takes them
    (MAG-211), the calls of the round in progress, the answer so far. `resumed` is the rounds a lost turn had
    started (MAG-872): they join the conversation and the blocks.
    """
    state = state if state is not None else TurnState()
    streamed = options.streamed
    source = options.source
    cached_tools = cache_tools(tools)
    guard = ClaimGuard(tools)
    streak = FailureStreak()
    reviewed = False
    stop_tools = False
    shrunk = False
    tool_calls: list[dict] = []
    usage = {"input_tokens": 0, "output_tokens": 0}

    if resumed:
        messages.extend(model_turns(list(resumed)))
        state.blocks.extend(resumed)
        _back_the_guard(guard, list(resumed))

    def ended(response: str, *, failed: bool = False) -> TurnEnded:
        return TurnEnded(LoopOutcome(response, tool_calls, state.blocks, usage, failed))

    for iteration in range(options.max_iterations):
        yield StepStarted(iteration)
        state.round_calls = []
        logger.info(f"Calling Claude with {len(tools or [])} tools, {len(messages)} messages")
        t0 = time.monotonic()
        calling = True
        try:
            request = {
                "model": options.model,
                "max_tokens": options.max_tokens or settings.llm_max_tokens,
                "system": system,
                "messages": messages,
                "tools": cached_tools if cached_tools else anthropic.NOT_GIVEN,
                "tool_choice": {"type": "none"} if stop_tools else anthropic.NOT_GIVEN,
                **({"output_config": {"effort": options.effort}} if options.effort else {}),
            }
            step_text = ""
            attempt = 0
            while True:
                emitted = False
                try:
                    async for item in _model_step(client, resolve_client, request, streamed):
                        if isinstance(item, _StepResponse):
                            response, step_text = item.message, item.text
                        else:
                            emitted = True
                            yield item
                    break
                except Exception as overflow:
                    if not emitted and attempt < settings.llm_retries and retryable(overflow):
                        attempt += 1
                        delay = retry_delay(overflow, attempt)
                        reason = retry_reason(overflow)
                        logger.warning(f"Model call failed ({reason}): retry {attempt}/{settings.llm_retries}")
                        record_llm_usage(
                            model=options.model,
                            call_type=options.call_type,
                            input_tokens=0,
                            output_tokens=0,
                            duration_seconds=time.monotonic() - t0,
                            status="error",
                        )
                        yield ModelRetrying(attempt, settings.llm_retries, delay, reason)
                        await asyncio.sleep(delay)
                        continue
                    # The window is full: one retry per turn, the older results replaced by a note. Only when
                    # the step has said nothing yet, so no event is ever emitted twice.
                    if emitted or shrunk or not is_context_overflow(overflow) or not without_old_results(messages):
                        raise
                    shrunk = True
                    logger.warning("Context window overflow: retrying the step without the older tool results")
                    record_llm_usage(
                        model=options.model,
                        call_type=options.call_type,
                        input_tokens=0,
                        output_tokens=0,
                        duration_seconds=time.monotonic() - t0,
                        status="error",
                    )
            await note_provider_success()
            counts = usage_kwargs(response.usage)
            record_llm_usage(
                model=options.model,
                call_type=options.call_type,
                duration_seconds=time.monotonic() - t0,
                **counts,
            )
            usage["input_tokens"] += sum(
                counts.get(key, 0) for key in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
            )
            usage["output_tokens"] += counts.get("output_tokens", 0)
            calling = False

            logger.info(f"Claude response: stop_reason={response.stop_reason}, blocks={len(response.content)}")

            if streamed:
                step_text = options.clean_text(step_text).strip()
                if step_text:
                    state.answer = step_text
                yield StepEnded(step_text)
            else:
                yield StepEnded(answer_text(response.content))

            if response.stop_reason == PAUSE_TURN:
                # A long web search stopped on the server's own limit: the response goes back as it
                # is, and the model carries on from where it paused (MAG-384).
                messages.append({"role": "assistant", "content": response.content})
                continue

            if response.stop_reason == "tool_use" and not stop_tools:
                messages.append({"role": "assistant", "content": response.content})
                uses = [
                    {"id": b.id, "name": b.name, "input": b.input} for b in response.content if b.type == "tool_use"
                ]
                yield ToolCallsPlanned(uses)

                tool_results = []
                for block in response.content:
                    if block.type != "tool_use":
                        continue
                    logger.info(f"Tool call: {block.name}({block.input})")
                    TOOL_CALLS.labels(tool_name=block.name, source=source).inc()
                    call_kwargs = {"user_id": options.user_id, "source": source, "context_id": options.context_id}
                    if streamed:
                        call = asyncio.ensure_future(tool_router.call_tool(block.name, block.input, **call_kwargs))
                        try:
                            result = await asyncio.shield(call)
                        except asyncio.CancelledError:
                            # A tool that may already be acting is let finish: what it did is what the next
                            # turn is told, and what is not known is not undone.
                            if options.stopped is not None and options.stopped():
                                result = await call
                                state.round_calls.append(
                                    {"id": block.id, "name": block.name, "input": block.input, "result": result}
                                )
                            else:
                                call.cancel()
                            raise
                    else:
                        result = await tool_router.call_tool(block.name, block.input, **call_kwargs)
                    state.round_calls.append(
                        {"id": block.id, "name": block.name, "input": block.input, "result": result}
                    )
                    tool_calls.append({"name": block.name, "input": block.input, "result": result})
                    guard.record(block.name, result, block.input)
                    streak.record(block.name, result)
                    tool_results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": for_model(result, settings.tool_result_max_chars),
                        }
                    )
                    yield ToolCallEnded(block.id, block.name, block.input, result, tool_result_status(result))

                if streak.gave_up():
                    tool_results.append(streak.note(options.user_id, source))
                    stop_tools = True
                messages.append({"role": "user", "content": tool_results})
                # The same round, as plain data this time: `response.content` holds the SDK's own
                # block objects, which no column can store.
                state.blocks.extend(record(state.round_calls))
                yield RoundEnded(state.round_calls)

                # Streamed, the question is the one of this round; blocking, any of the turn.
                question = (
                    options.ends_turn(state.round_calls if streamed else tool_calls) if options.ends_turn else None
                )
                if question:
                    # The turn ends on the question: the owner's answer is what Maggie waits for (MAG-717).
                    state.answer = question["text"]
                    yield QuestionAsked(question["text"])
                    yield ended(question["text"])
                    return
                continue

            if streamed:
                answer = state.answer
                if guard.review(answer, can_retry=iteration < options.max_iterations - 2) is Verdict.RETRY:
                    logger.warning("Action announced, nothing backs it: sending the model back")
                    guard.send_back(messages, response.content)
                    continue
                # The pages the answer cites, as one last line: text is what the clients show, store and
                # send back to the model at the next turn (MAG-384).
                sources = missing_sources(answer, response.content)
                if sources and step_text and not options.spoken:
                    state.answer = f"{answer}\n\n{sources}"
                    yield SourcesAdded(sources)
                yield ended(state.answer)
                return

            text_response = answer_text(response.content)
            if stop_tools and not text_response:
                yield ended(ITERATION_LIMIT_MESSAGE)
                return
            if guard.review(text_response, can_retry=iteration < options.max_iterations - 2) is Verdict.RETRY:
                logger.warning("Action announced, nothing backs it: sending the model back")
                guard.send_back(messages, response.content)
                continue
            if options.review is not None and not reviewed and iteration < options.max_iterations - 1:
                note = options.review(text_response, tool_calls)
                if note:
                    if text_response:
                        messages.append({"role": "assistant", "content": response.content})
                    messages.append({"role": "user", "content": [{"type": "text", "text": note}]})
                    reviewed = True
                    continue
            yield ended(with_sources(text_response, response.content))
            return

        except Exception as e:
            if not streamed and not calling:
                raise
            if not streamed:
                await note_provider_failure(e)
            record_llm_usage(
                model=options.model,
                call_type=options.call_type,
                input_tokens=0,
                output_tokens=0,
                duration_seconds=time.monotonic() - t0,
                status="error",
            )
            if not streamed:
                raise
            logger.error(f"Streaming error: {e}")
            await note_provider_failure(e)
            yield ModelFailed(e)
            yield ended(state.answer, failed=True)
            return

    if streamed:
        # The budget ran out on a tool call, so the answer is an earlier step's text that nobody reviewed:
        # an action it announces is signalled here, with no relaunch left.
        guard.review(state.answer, can_retry=False)
        yield ended(state.answer)
    else:
        yield ended(ITERATION_LIMIT_MESSAGE)

"""AG-UI streaming gateway for token-by-token chat responses."""

import asyncio
import hashlib
import logging
import time
import uuid
from collections.abc import AsyncGenerator, Callable, Coroutine
from datetime import UTC, datetime

import anthropic
from sqlalchemy.exc import IntegrityError

from app.config import settings
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import NOTHING_SAID, Message
from app.error_tracking import capture_error
from app.llm.claim_guard import ClaimGuard, Verdict
from app.llm.client import client_for
from app.llm.context_summary import context_summarizer
from app.llm.contexts import open_threads, route_message, with_routed
from app.llm.gateway import API_ERROR_MESSAGE
from app.llm.history import build_history, label_settled, strip_thread_label
from app.llm.image import ChatImage
from app.llm.models import CHAT, effort_kwargs, model_for
from app.llm.prompt_cache import cache_tools
from app.llm.provider_status import alert_owner, note_provider_failure, note_provider_success, provider_reason
from app.llm.questions import asked_question, blocks_of
from app.llm.tool_blocks import INTERRUPTED_RESULT, close_interrupted, record, record_partial
from app.llm.tool_failures import FailureStreak, tool_result_status
from app.llm.tools import ToolRouter
from app.llm.web_search import (
    PAUSE_TURN,
    SERVER_TOOL_USE,
    WEB_SEARCH_RESULT,
    WEB_SEARCH_TOOL,
    missing_sources,
    search_failed,
    with_web_search,
)
from app.metrics import TOOL_CALLS, record_llm_usage, usage_kwargs
from app.runtime.system_prompt import SystemPrompt
from app.skills.index import MOMENT_CHAT
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)


def answer_message_id(user_msg_id: str) -> str:
    """The id of the answer to a user's message, fixed by the message alone (MAG-344).

    A turn that is taken up again after a restart then stores its answer under the id the
    first one would have used: the second insert is refused, and the answer exists once.
    """
    return hashlib.sha1(f"answer:{user_msg_id}".encode()).hexdigest()[:26]


class StreamingGateway:
    """Claude API gateway with AG-UI streaming event emission."""

    def __init__(self, *, client, tool_router: ToolRouter, system_prompt: SystemPrompt):
        self.client = client
        self.tool_router = tool_router
        self.system_prompt = system_prompt
        # Background work the answer does not wait for. Held in a set because the event
        # loop keeps only a weak reference to a running task: one nobody holds can be
        # collected mid-await, and the summary would go missing on a busy process.
        self._background: set[asyncio.Task] = set()

    def _in_background(self, work: Coroutine) -> None:
        task = asyncio.create_task(work)
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    async def _route_turn(self, message: str, user_id: str, user_msg_id: str) -> tuple[dict | None, list | None]:
        """The thread of this turn, and the open threads as they are once it is routed (read once)."""
        threads = await open_threads(user_id)
        resolution = await self._resolve_context(message, user_id, user_msg_id, contexts=threads)
        return resolution, (with_routed(threads, resolution) if threads is not None else None)

    async def chat_stream(
        self,
        message: str,
        user_id: str,
        user_msg_id: str,
        *,
        screen_context: str | None = None,
        image: ChatImage | None = None,
        carried_over: str | None = None,
        stopped: Callable[[], bool] | None = None,
        spoken: bool = False,
        in_car: bool = False,
        checkpoint: list | None = None,
    ) -> AsyncGenerator[dict, None]:
        """Stream AG-UI events for a chat message.

        `checkpoint` is the calls a lost turn had started (MAG-872): the turn carries on from them, a call
        with no result closed as interrupted and never run again by the code.

        `carried_over` is what the turn this message replaces had already done (MAG-320), told to
        the model on this turn. `stopped` says whether a cancellation is the user's stop: only
        then is what the turn did kept, a shutdown hands the turn over untouched.

        `spoken`: the answer will be read aloud (the voice overlay): written for the ear (ADR-014).
        `in_car`: the owner is driving: the answer is short and made for the ear (MAG-684).

        `screen_context` is what the screen behind the assistant overlay was showing
        (MAG-30). It joins the conversation in `build_history`, on the turn being answered,
        and stays out of the stored message — which is what every client displays. So does
        `image`, the screenshot (MAG-214).

        Yields dicts representing AG-UI protocol events:
        - RUN_STARTED / RUN_FINISHED
        - TEXT_MESSAGE_START / TEXT_MESSAGE_CONTENT / TEXT_MESSAGE_END
        - TOOL_CALL_START / TOOL_CALL_END
        - CUSTOM (context updates, tool results)
        """
        run_id = uuid.uuid4().hex[:16]
        yield {"type": "RUN_STARTED", "runId": run_id}

        if self.client is None:
            msg_id = uuid.uuid4().hex[:16]
            yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
            error_text = "Le service IA n'est pas configuré."
            yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": error_text}
            yield {"type": "TEXT_MESSAGE_END", "messageId": msg_id}
            yield {"type": "RUN_FINISHED", "runId": run_id}
            return

        # Start the memory's bucket catch-up now so it overlaps the thread router;
        # `agent_memory.blocks` waits for it later, bounded.
        self.system_prompt.warm_up(user_id)

        # The thread decides the history, the memory recall and the open-threads section (MAG-13), and
        # nothing else: the router runs in a task while the rest loads beside it. No TaskGroup — it
        # would turn an error into an ExceptionGroup.
        routing = asyncio.create_task(self._route_turn(message, user_id, user_msg_id))
        try:
            tz, tools = await asyncio.gather(resolve_user_timezone(user_id), self.tool_router.get_tool_definitions())
            # Anthropic runs the web search itself: it is offered here, never routed (MAG-384).
            tools = with_web_search(tools, tz)
            parts = await self.system_prompt.thread_free(
                user_id, tools, moment=MOMENT_CHAT, exclude_message_id=user_msg_id, tz=tz
            )
            ctx_resolution, threads = await routing
        finally:
            routing.cancel()
        current_context_id = ctx_resolution.get("id") if ctx_resolution else None
        if ctx_resolution:
            yield {"type": "CUSTOM", "name": "context_update", "value": ctx_resolution}

        # The thread's messages plus a short global window. The user's message is already
        # persisted and, by now, tagged with the thread, so the history holds it — and
        # `fallback_message` is the floor under a history that would not load at all, since
        # the API refuses a conversation with no message in it.
        messages, system_prompt = await asyncio.gather(
            build_history(
                user_id,
                context_id=current_context_id,
                fallback_message=message,
                current_message_id=user_msg_id,
                screen_context=screen_context,
                tz=tz,
                image=image,
                carried_over=carried_over,
                open_threads=threads,
            ),
            self.system_prompt.build(
                user_id,
                tools=tools,
                exclude_message_id=user_msg_id,
                current_context_id=current_context_id,
                moment=MOMENT_CHAT,
                about=message,
                spoken=spoken,
                in_car=in_car,
                parts=parts,
                open_threads=threads,
                tz=tz,
            ),
        )
        cached_tools = cache_tools(tools)

        # A reminder announced has to be a reminder scheduled (MAG-339), and « c'est noté »
        # something stored (MAG-340). The answer streams as it is written, so a false
        # announcement may already be on screen when the guard reads it: it is corrected
        # rather than held back. A relaunch replaces it with the next step's text, like any
        # intermediate step (MAG-229), and a second failure goes out as the model wrote it,
        # signalled (MAG-842).
        guard = ClaimGuard(tools)

        # What is stored, shown and read aloud is the last step's text alone: the steps
        # before a tool call are the model thinking out loud (announcements, errors, retries),
        # and glued together they read as one broken sentence (MAG-229). Only a step with no
        # text of its own leaves the previous one standing, so the answer is never empty.
        answer = ""
        max_iterations = 5
        streak = FailureStreak()
        stop_tools = False

        # The `tool_use` / `tool_result` rounds of this run, stored on the answer so the
        # next message of the thread is sent them back (MAG-211). Not the same thing as
        # the context's `tool_calls_log` below, which is a list of names for the Mind
        # panel: this is what the calls said.
        resumed = close_interrupted(checkpoint)
        messages.extend(resumed)
        blocks: list[dict] = list(resumed)
        ui_blocks: list[dict] = []
        self._back_the_guard(guard, resumed)

        # Single message ID across all iterations so the frontend sees one message bubble
        msg_id = answer_message_id(user_msg_id)
        text_started = False

        for iteration in range(max_iterations):
            logger.info(f"Stream iteration {iteration + 1}, {len(tools)} tools, {len(messages)} messages")
            round_calls: list[dict] = []

            t0 = time.monotonic()
            try:
                # Use streaming API
                stream = (await client_for(user_id, self.client)).messages.stream(
                    model=model_for(CHAT),
                    max_tokens=settings.llm_max_tokens,
                    system=system_prompt,
                    messages=messages,
                    tools=cached_tools if cached_tools else anthropic.NOT_GIVEN,
                    tool_choice={"type": "none"} if stop_tools else anthropic.NOT_GIVEN,
                    **effort_kwargs(CHAT),
                )

                tool_use_blocks = []
                response_content = []
                stop_reason = None
                step_text = ""
                step_shown = False

                async with stream as s:
                    async for event in s:
                        if event.type == "content_block_start":
                            if hasattr(event.content_block, "name"):
                                # Tool use block starting
                                tool_name = event.content_block.name
                                tool_id = event.content_block.id
                                yield {
                                    "type": "TOOL_CALL_START",
                                    "toolCallId": tool_id,
                                    "toolName": tool_name,
                                }
                                if event.content_block.type == SERVER_TOOL_USE:
                                    # What was said so far was the model announcing its search:
                                    # the answer is the text written after the last one (MAG-384).
                                    step_text = ""
                                    step_shown = False
                            elif event.content_block.type == WEB_SEARCH_RESULT:
                                # The search ran on Anthropic's side: its result block is the only
                                # sign it ended, and the Mind panel gets it like any tool's.
                                yield {
                                    "type": "TOOL_CALL_END",
                                    "toolCallId": event.content_block.tool_use_id,
                                    "toolName": WEB_SEARCH_TOOL,
                                }
                                yield {
                                    "type": "CUSTOM",
                                    "name": "tool_result",
                                    "value": {
                                        "toolCallId": event.content_block.tool_use_id,
                                        "toolName": WEB_SEARCH_TOOL,
                                        "status": "error" if search_failed(event.content_block) else "success",
                                    },
                                }

                        elif event.type == "content_block_delta":
                            if hasattr(event.delta, "text"):
                                delta = event.delta.text
                                step_text += delta
                                # Held back until its head is known not to be a thread label:
                                # once shown, a delta cannot be taken out of the bubble (MAG-341).
                                if not step_shown and label_settled(step_text):
                                    # A new TEXT_MESSAGE_START on the same id empties the bubble on
                                    # both clients: whatever an earlier step showed gives way to this
                                    # one, so the intermediate text is only ever a passing state.
                                    yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                                    text_started = True
                                    step_shown = True
                                    delta = strip_thread_label(step_text).lstrip()
                                if step_shown:
                                    yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": delta}

                        elif event.type == "content_block_stop":
                            pass  # Handled after stream ends

                    # Get the final message
                    response = await s.get_final_message()
                    stop_reason = response.stop_reason
                    response_content = response.content

                    duration = time.monotonic() - t0
                    await note_provider_success()
                    record_llm_usage(
                        model=model_for(CHAT),
                        call_type="chat_stream",
                        duration_seconds=duration,
                        **usage_kwargs(response.usage),
                    )

                step_text = strip_thread_label(step_text)
                if step_text.strip():
                    answer = step_text.strip()
                    if not step_shown:
                        # A step too short to settle its head (a label and a word) is shown whole now.
                        yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                        yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": answer}
                        text_started = True

                if stop_reason == PAUSE_TURN:
                    # A long search stopped on the server's own limit: the response goes back
                    # as it is and the model carries on (MAG-384).
                    messages.append({"role": "assistant", "content": response_content})
                    continue

                # Process tool calls if stop_reason is tool_use
                if stop_reason == "tool_use" and not stop_tools:
                    messages.append({"role": "assistant", "content": response_content})
                    uses = [
                        {"id": b.id, "name": b.name, "input": b.input} for b in response_content if b.type == "tool_use"
                    ]
                    await self._checkpoint(user_msg_id, blocks, uses, round_calls)

                    tool_results = []
                    for block in response_content:
                        if block.type == "tool_use":
                            tool_name = block.name
                            tool_input = block.input

                            # Regular tool call
                            logger.info(f"Tool call: {tool_name}({tool_input})")
                            TOOL_CALLS.labels(tool_name=tool_name, source="chat_stream").inc()

                            call = asyncio.ensure_future(
                                self.tool_router.call_tool(
                                    tool_name,
                                    tool_input,
                                    user_id=user_id,
                                    source="chat_stream",
                                    context_id=current_context_id,
                                )
                            )
                            try:
                                result = await asyncio.shield(call)
                            except asyncio.CancelledError:
                                # A tool that may already be acting is let finish: what it did is
                                # what the next turn is told, and what is not known is not undone.
                                if stopped is not None and stopped():
                                    result = await call
                                    round_calls.append(
                                        {"id": block.id, "name": tool_name, "input": tool_input, "result": result}
                                    )
                                else:
                                    call.cancel()
                                raise
                            round_calls.append(
                                {"id": block.id, "name": tool_name, "input": tool_input, "result": result}
                            )
                            await self._checkpoint(user_msg_id, blocks, uses, round_calls)
                            tool_use_blocks.append(
                                {
                                    "name": tool_name,
                                    "input": tool_input,
                                    "result": result,
                                }
                            )
                            guard.record(tool_name, result, tool_input)
                            streak.record(tool_name, result)

                            # Log to context if we have one
                            if current_context_id:
                                try:
                                    status = tool_result_status(result)
                                    await context_repo.append_tool_call(
                                        current_context_id,
                                        {
                                            "name": tool_name,
                                            "status": status,
                                            "timestamp": datetime.now(UTC).isoformat(),
                                        },
                                    )
                                except Exception as e:
                                    logger.warning(f"Failed to log tool call to context: {e}")

                            yield {
                                "type": "TOOL_CALL_END",
                                "toolCallId": block.id,
                                "toolName": tool_name,
                            }

                            # Emit tool result for Mind Panel
                            status = tool_result_status(result)

                            yield {
                                "type": "CUSTOM",
                                "name": "tool_result",
                                "value": {
                                    "toolCallId": block.id,
                                    "toolName": tool_name,
                                    "status": status,
                                },
                            }

                            tool_results.append(
                                {
                                    "type": "tool_result",
                                    "tool_use_id": block.id,
                                    "content": result,
                                }
                            )

                    if streak.gave_up():
                        tool_results.append(streak.note(user_id, "chat_stream"))
                        stop_tools = True
                    messages.append({"role": "user", "content": tool_results})
                    # The same round as plain data, which is what a column can hold:
                    # `response_content` is the SDK's own block objects.
                    blocks.extend(record(round_calls))
                    ui_blocks.extend(blocks_of(tool_use_blocks))
                    if question := asked_question(tool_use_blocks):
                        # The turn ends on the question; what was streamed before gives way to it (MAG-717).
                        answer = question["text"]
                        yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                        text_started = True
                        yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": answer}
                        break
                    # Continue loop for more iterations
                    continue

                verdict = guard.review(answer, can_retry=iteration < max_iterations - 2)
                if verdict is Verdict.RETRY:
                    logger.warning("Action announced, nothing backs it: sending the model back")
                    guard.send_back(messages, response_content)
                    continue
                # The pages the answer cites, as one last line: text is what the clients
                # show, store and send back to the model at the next turn (MAG-384).
                sources = missing_sources(answer, response_content)
                if sources and step_text.strip() and not (spoken or in_car):
                    answer = f"{answer}\n\n{sources}"
                    yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": f"\n\n{sources}"}
                break

            except asyncio.CancelledError:
                if stopped is not None and stopped():
                    blocks.extend(record(round_calls))
                    await self._keep_what_was_done(user_id, msg_id, answer, current_context_id, blocks)
                raise
            except Exception as e:
                duration = time.monotonic() - t0
                record_llm_usage(
                    model=model_for(CHAT),
                    call_type="chat_stream",
                    input_tokens=0,
                    output_tokens=0,
                    duration_seconds=duration,
                    status="error",
                )
                logger.error(f"Streaming error: {e}")
                await note_provider_failure(e)
                # The phone's chat goes this way: the owner hears of an empty credit from here too (MAG-448).
                if (reason := provider_reason(e)) is not None:
                    await alert_owner(user_id, reason)
                # Restarted, not appended: the bubble must hold what is stored, and
                # an earlier step's text is not part of that.
                yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                text_started = True
                answer = API_ERROR_MESSAGE
                yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": answer}
                break
        else:
            # The budget ran out on a tool call, so the answer is an earlier step's text that
            # nobody reviewed: an action it announces is signalled here, with no relaunch left.
            guard.review(answer, can_retry=False)

        # End the single text message of the run
        if text_started:
            yield {"type": "TEXT_MESSAGE_END", "messageId": msg_id}

        # Persisted under the id the stream announced, and published like any message: the
        # device that streamed it recognises the echo by that id, the others learn of it.
        if answer:
            try:
                msg = await message_repo.create(
                    user_id=user_id,
                    role="assistant",
                    content=answer,
                    context_id=current_context_id,
                    message_id=msg_id,
                    # What this turn's tools said, carried on the message so the next one can
                    # read it instead of calling them again (MAG-211). The error path falls
                    # here too, with the rounds that did run before it: they happened.
                    blocks=blocks,
                    ui_blocks=ui_blocks,
                    announce=False,
                )
            except IntegrityError:
                # A turn taken up again after the first one had already stored its answer.
                logger.info(f"Answer to {user_msg_id} was already stored: not stored twice")
                yield {"type": "RUN_FINISHED", "runId": run_id}
                return

            # Both sides of the exchange are now in the database, so this is the one
            # moment the thread's message count is right. Not awaited: nobody is waiting
            # for a summary, and the run has to finish at the speed of the answer
            # (MAG-11). The Mind panel gets it over Mercure when it lands.
            if current_context_id:
                self._in_background(context_summarizer.maybe_summarize(current_context_id))

        yield {"type": "RUN_FINISHED", "runId": run_id}

        # After RUN_FINISHED: the phone asks for the voice on it, and neither Mercure nor the
        # memory write may delay that. Still in the turn's task, which drains this generator.
        if answer:
            await self._announce_answer(msg, user_id, msg_id)

    @staticmethod
    def _back_the_guard(guard: ClaimGuard, resumed: list[dict]) -> None:
        """The calls a resumed turn had made count for the claim guard like any other of the turn."""
        asked = {use["id"]: use for turn in resumed[::2] for use in turn["content"]}
        for turn in resumed[1::2]:
            for result in turn["content"]:
                if result["content"] != INTERRUPTED_RESULT:
                    use = asked[result["tool_use_id"]]
                    guard.record(use["name"], result["content"], use["input"])

    @staticmethod
    async def _checkpoint(user_msg_id: str, blocks: list[dict], uses: list[dict], done: list[dict]) -> None:
        """Keep the calls of the round in progress on the user's message (MAG-872); never breaks the turn."""
        try:
            await message_repo.checkpoint_turn(user_msg_id, [*blocks, *record_partial(uses, done)])
        except Exception as exc:
            logger.warning(f"Could not keep the calls of the running turn: {exc}")

    @staticmethod
    async def _announce_answer(msg: Message, user_id: str, msg_id: str) -> None:
        try:
            await message_repo.announce(msg)
        except Exception as exc:
            logger.exception("Could not announce answer %s", msg_id)
            capture_error(exc, user_id=user_id, message_id=msg_id)

    @staticmethod
    async def _keep_what_was_done(
        user_id: str, msg_id: str, shown: str, context_id: str | None, blocks: list[dict]
    ) -> None:
        """A stopped turn that had already acted leaves its calls on an interrupted answer (MAG-320).

        Without them the next turn would not know that an event was created, and would create
        it again. A turn that had done nothing leaves no row: `POST /chat/interrupt` writes it.
        """
        if not blocks:
            return
        try:
            await message_repo.create(
                user_id=user_id,
                role="assistant",
                content=shown or NOTHING_SAID,
                context_id=context_id,
                message_id=msg_id,
                blocks=blocks,
                interrupted=True,
            )
        except IntegrityError:
            logger.info(f"Answer {msg_id} was already stored: the stopped turn's calls are not kept twice")
        except Exception as exc:
            logger.warning(f"Could not keep what the stopped turn did: {exc}")

    async def _resolve_context(
        self, message: str, user_id: str, user_msg_id: str, *, contexts: list | None = None
    ) -> dict | None:
        """Route the user's message to an existing or new context, and tag the message with it.

        Both the routing and the tagging are `app.llm.contexts.route_message`, shared with
        the plain chat path (MAG-13) and with the proactions (MAG-14).
        """
        return await route_message(self.client, message, user_id, message_id=user_msg_id, contexts=contexts)

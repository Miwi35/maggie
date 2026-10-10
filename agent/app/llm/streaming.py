"""AG-UI streaming gateway for token-by-token chat responses."""

import asyncio
import hashlib
import json
import logging
import time
import uuid
from collections.abc import AsyncGenerator, Coroutine
from datetime import UTC, datetime

import anthropic
from sqlalchemy.exc import IntegrityError

from app.config import settings
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.llm.capabilities import generate_capability_summary
from app.llm.claim_guard import ClaimGuard, Verdict, question_of
from app.llm.client import create_llm_client, llm_configured
from app.llm.context_summary import context_summarizer
from app.llm.contexts import active_contexts_section, route_message
from app.llm.directives import behavior_directives_section
from app.llm.history import build_history, label_settled, strip_thread_label
from app.llm.last_exchange import last_exchange_section
from app.llm.prompt_cache import build_system, cache_tools
from app.llm.tool_blocks import record
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.metrics import TOOL_CALLS, record_llm_usage, usage_kwargs
from app.personality.engine import PersonalityEngine, current_datetime_line
from app.skills.index import MOMENT_CHAT, skill_index
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

PENDING_APPROVAL_STATUS = "pending_approval"


def tool_result_status(result: str) -> str:
    """How a tool call ended, as the Mind panel and the thread's activity log read it.

    `pending_approval` is a third outcome, not a failure: the policy held the call back
    and the user has the card in front of them (MAG-4). Drawing it as an error would say
    Maggie tried and could not, which is the opposite of « Propose, never impose ».
    """
    try:
        data = json.loads(result)
    except (json.JSONDecodeError, TypeError):
        return "error" if '"error"' in result else "success"

    if not isinstance(data, dict):
        return "success"
    if data.get("status") == PENDING_APPROVAL_STATUS:
        return PENDING_APPROVAL_STATUS
    return "error" if "error" in data else "success"


def answer_message_id(user_msg_id: str) -> str:
    """The id of the answer to a user's message, fixed by the message alone (MAG-344).

    A turn that is taken up again after a restart then stores its answer under the id the
    first one would have used: the second insert is refused, and the answer exists once.
    """
    return hashlib.sha1(f"answer:{user_msg_id}".encode()).hexdigest()[:26]


class StreamingGateway:
    """Claude API gateway with AG-UI streaming event emission."""

    def __init__(self):
        self.client = create_llm_client() if llm_configured() else None
        self.personality = PersonalityEngine()
        self.tool_router = ToolRouter()
        self.agent_memory = AgentMemory()
        # Background work the answer does not wait for. Held in a set because the event
        # loop keeps only a weak reference to a running task: one nobody holds can be
        # collected mid-await, and the summary would go missing on a busy process.
        self._background: set[asyncio.Task] = set()

    def _in_background(self, work: Coroutine) -> None:
        task = asyncio.create_task(work)
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    async def _build_system_prompt(
        self,
        user_id: str,
        tools: list[dict] | None = None,
        *,
        exclude_message_id: str | None = None,
        current_context_id: str | None = None,
    ) -> list[dict]:
        """Build the system blocks: cached prefix (personality + skills), then memory, directives, contexts, date."""
        capabilities = generate_capability_summary(tools) if tools else ""
        base = await self.personality.get_system_prompt(user_id, capabilities=capabilities)
        await skill_index.refresh()
        skill_context = skill_index.get_skills_index()
        memory_context = await self.agent_memory.get_memory_context(user_id)

        # How the user asked to be spoken to. In the volatile block rather than the
        # cached prefix: a preference stated in this very conversation has to hold on
        # the next message, and the prefix would serve the copy cached before it (MAG-22).
        directives = await behavior_directives_section(user_id)

        # Inject active contexts so Claude knows ongoing topics — with what each one is
        # about, for the threads whose summary has been written (MAG-11), and with the
        # current thread marked, since the history is made of its messages (MAG-13).
        context_section = await active_contexts_section(user_id, current_context_id)

        # The silence before this message, so she can greet by the gap (MAG-10).
        last_exchange = await last_exchange_section(user_id, exclude_message_id=exclude_message_id)

        date_line = current_datetime_line(tz=await resolve_user_timezone(user_id))
        volatile = f"{memory_context}{directives}{context_section}\n\n{date_line}{last_exchange}"
        # The skills learnt for the chat, in full (MAG-345).
        return build_system(base + skill_context, volatile, skill_index.skills_for_moment(MOMENT_CHAT))

    async def chat_stream(
        self, message: str, user_id: str, user_msg_id: str, *, screen_context: str | None = None
    ) -> AsyncGenerator[dict, None]:
        """Stream AG-UI events for a chat message.

        `screen_context` is what the screen behind the assistant overlay was showing
        (MAG-30). It joins the conversation in `build_history`, on the turn being answered,
        and stays out of the stored message — which is what every client displays.

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

        # The thread first. Everything below is built around it — the history is its
        # messages, and the system prompt names it — so routing afterwards, as this used to,
        # meant the routing decided nothing at all (MAG-13).
        ctx_resolution = await self._resolve_context(message, user_id, user_msg_id)
        current_context_id = ctx_resolution.get("id") if ctx_resolution else None
        if ctx_resolution:
            yield {"type": "CUSTOM", "name": "context_update", "value": ctx_resolution}

        # The thread's messages plus a short global window. The user's message is already
        # persisted and, by now, tagged with the thread, so the history holds it — and
        # `fallback_message` is the floor under a history that would not load at all, since
        # the API refuses a conversation with no message in it.
        messages = await build_history(
            user_id,
            context_id=current_context_id,
            fallback_message=message,
            current_message_id=user_msg_id,
            screen_context=screen_context,
            tz=await resolve_user_timezone(user_id),
        )

        # Get tools (contexts are managed by the gateway, not by Claude)
        tools = await self.tool_router.get_tool_definitions(include_native=True)

        system_prompt = await self._build_system_prompt(
            user_id, tools=tools, exclude_message_id=user_msg_id, current_context_id=current_context_id
        )
        cached_tools = cache_tools(tools)

        # A reminder announced has to be a reminder scheduled (MAG-339), and « c'est noté »
        # something stored (MAG-340). The answer streams as it is written, so a false
        # announcement may already be on screen when the guard reads it: it is corrected
        # rather than held back. A relaunch replaces it with the next step's text, like any
        # intermediate step (MAG-229), and a second failure restarts the bubble on the
        # guard's `honest_answer()` — the only text then stored.
        guard = ClaimGuard(tools, question_of(messages))

        # What is stored, shown and read aloud is the last step's text alone: the steps
        # before a tool call are the model thinking out loud (announcements, errors, retries),
        # and glued together they read as one broken sentence (MAG-229). Only a step with no
        # text of its own leaves the previous one standing, so the answer is never empty.
        answer = ""
        max_iterations = 5

        # The `tool_use` / `tool_result` rounds of this run, stored on the answer so the
        # next message of the thread is sent them back (MAG-211). Not the same thing as
        # the context's `tool_calls_log` below, which is a list of names for the Mind
        # panel: this is what the calls said.
        blocks: list[dict] = []

        # Single message ID across all iterations so the frontend sees one message bubble
        msg_id = answer_message_id(user_msg_id)
        text_started = False

        for iteration in range(max_iterations):
            logger.info(f"Stream iteration {iteration + 1}, {len(tools)} tools, {len(messages)} messages")

            t0 = time.monotonic()
            try:
                # Use streaming API
                stream = self.client.messages.stream(
                    model=settings.anthropic_model,
                    max_tokens=4096,
                    system=system_prompt,
                    messages=messages,
                    tools=cached_tools if cached_tools else anthropic.NOT_GIVEN,
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
                    record_llm_usage(
                        model=settings.anthropic_model,
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

                # Process tool calls if stop_reason is tool_use
                if stop_reason == "tool_use":
                    messages.append({"role": "assistant", "content": response_content})

                    tool_results = []
                    round_calls = []
                    for block in response_content:
                        if block.type == "tool_use":
                            tool_name = block.name
                            tool_input = block.input

                            # Regular tool call
                            logger.info(f"Tool call: {tool_name}({tool_input})")
                            TOOL_CALLS.labels(tool_name=tool_name, source="chat_stream").inc()

                            result = await self.tool_router.call_tool(
                                tool_name,
                                tool_input,
                                user_id=user_id,
                                source="chat_stream",
                                context_id=current_context_id,
                            )
                            tool_use_blocks.append(
                                {
                                    "name": tool_name,
                                    "input": tool_input,
                                    "result": result,
                                }
                            )
                            guard.record(tool_name, result, tool_input)

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
                            round_calls.append(
                                {"id": block.id, "name": tool_name, "input": tool_input, "result": result}
                            )

                    messages.append({"role": "user", "content": tool_results})
                    # The same round as plain data, which is what a column can hold:
                    # `response_content` is the SDK's own block objects.
                    blocks.extend(record(round_calls))
                    # Continue loop for more iterations
                    continue

                verdict = guard.review(answer, can_retry=iteration < max_iterations - 2)
                if verdict is Verdict.RETRY:
                    logger.warning("Action announced, nothing backs it: sending the model back")
                    guard.send_back(messages, response_content)
                    continue
                if verdict is Verdict.GIVE_UP:
                    logger.warning("Action announced twice, never done: answering it is not")
                    answer = guard.honest_answer()
                    yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                    text_started = True
                    yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": answer}
                break

            except Exception as e:
                duration = time.monotonic() - t0
                record_llm_usage(
                    model=settings.anthropic_model,
                    call_type="chat_stream",
                    input_tokens=0,
                    output_tokens=0,
                    duration_seconds=duration,
                    status="error",
                )
                logger.error(f"Streaming error: {e}")
                # Restarted, not appended: the bubble must hold what is stored, and
                # an earlier step's text is not part of that.
                yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                text_started = True
                answer = "Désolé, une erreur est survenue. Réessaie."
                yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": answer}
                break
        else:
            # The budget ran out on a tool call, so the answer is an earlier step's text that
            # nobody reviewed: an action it announces is checked here, with no relaunch left.
            if guard.review(answer, can_retry=False) is Verdict.GIVE_UP:
                logger.warning("Action announced, never done, out of iterations: answering it is not")
                answer = guard.honest_answer()
                yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                text_started = True
                yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": answer}

        # End the single text message of the run
        if text_started:
            yield {"type": "TEXT_MESSAGE_END", "messageId": msg_id}

        # Persisted under the id the stream announced, and published like any message: the
        # device that streamed it recognises the echo by that id, the others learn of it.
        if answer:
            try:
                await message_repo.create(
                    user_id=user_id,
                    role="assistant",
                    content=answer,
                    context_id=current_context_id,
                    message_id=msg_id,
                    # What this turn's tools said, carried on the message so the next one can
                    # read it instead of calling them again (MAG-211). The error path falls
                    # here too, with the rounds that did run before it: they happened.
                    blocks=blocks,
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

    async def _resolve_context(self, message: str, user_id: str, user_msg_id: str) -> dict | None:
        """Route the user's message to an existing or new context, and tag the message with it.

        Both the routing and the tagging are `app.llm.contexts.route_message`, shared with
        the plain chat path (MAG-13) and with the proactions (MAG-14).
        """
        return await route_message(self.client, message, user_id, message_id=user_msg_id)

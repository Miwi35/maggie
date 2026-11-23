"""AG-UI streaming gateway for token-by-token chat responses."""

import asyncio
import hashlib
import logging
import uuid
from collections.abc import AsyncGenerator, Callable, Coroutine
from contextlib import aclosing
from datetime import UTC, datetime

from sqlalchemy.exc import IntegrityError

from app.config import settings
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import NOTHING_SAID, Message
from app.error_tracking import capture_error
from app.llm.client import client_for
from app.llm.context_summary import context_summarizer
from app.llm.contexts import open_threads, route_message, with_routed
from app.llm.gateway import API_ERROR_MESSAGE
from app.llm.history import build_history, label_settled, strip_thread_label
from app.llm.image import ChatImage
from app.llm.models import CHAT, effort_for, model_for
from app.llm.provider_status import alert_owner, provider_reason
from app.llm.questions import asked_question, blocks_of
from app.llm.tool_blocks import close_interrupted, record, record_partial
from app.llm.tools import ToolRouter
from app.llm.web_search import WEB_SEARCH_TOOL, with_web_search
from app.runtime.loop import (
    LoopOptions,
    ModelFailed,
    QuestionAsked,
    RoundEnded,
    SourcesAdded,
    StepEnded,
    StepStarted,
    TextDelta,
    ToolCallEnded,
    ToolCallsPlanned,
    ToolUseStarted,
    TurnEnded,
    TurnState,
    WebSearchEnded,
    run_loop,
)
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
        # What is stored, shown and read aloud is the last step's text alone: the steps before a tool call
        # are the model thinking out loud (announcements, errors, retries), and glued together they read as
        # one broken sentence (MAG-229). The loop (`app.runtime.loop`) decides it; this translates its events.
        state = TurnState()
        # The `tool_use` / `tool_result` rounds of this run, stored on the answer so the next message of the
        # thread is sent them back (MAG-211): `state.blocks`, not the context's `tool_calls_log` below, which is
        # a list of names for the Mind panel.
        resumed = close_interrupted(checkpoint)
        ui_blocks: list[dict] = []
        answer = ""
        options = LoopOptions.streaming(
            model=model_for(CHAT),
            effort=effort_for(CHAT),
            max_iterations=settings.chat_max_iterations,
            context_id=current_context_id,
            ends_turn=asked_question,
            clean_text=strip_thread_label,
            stopped=stopped,
            spoken=spoken or in_car,
            user_id=user_id,
        )

        # Single message ID across all iterations so the frontend sees one message bubble
        msg_id = answer_message_id(user_msg_id)
        text_started = False
        step_text = ""
        step_shown = False
        planned: list[dict] = []

        try:
            loop = run_loop(
                system_prompt,
                messages,
                tools,
                client=await client_for(user_id, self.client),
                tool_router=self.tool_router,
                options=options,
                state=state,
                resumed=resumed,
            )
            async with aclosing(loop) as events:
                async for event in events:
                    if isinstance(event, StepStarted):
                        step_text = ""
                        step_shown = False
                    elif isinstance(event, ToolUseStarted):
                        yield {"type": "TOOL_CALL_START", "toolCallId": event.id, "toolName": event.name}
                        if event.server:
                            step_text = ""
                            step_shown = False
                    elif isinstance(event, WebSearchEnded):
                        # The search ran on Anthropic's side: its result block is the only sign it ended,
                        # and the Mind panel gets it like any tool's.
                        yield {"type": "TOOL_CALL_END", "toolCallId": event.tool_use_id, "toolName": WEB_SEARCH_TOOL}
                        yield {
                            "type": "CUSTOM",
                            "name": "tool_result",
                            "value": {
                                "toolCallId": event.tool_use_id,
                                "toolName": WEB_SEARCH_TOOL,
                                "status": "error" if event.failed else "success",
                            },
                        }
                    elif isinstance(event, TextDelta):
                        delta = event.text
                        step_text += delta
                        # Held back until its head is known not to be a thread label:
                        # once shown, a delta cannot be taken out of the bubble (MAG-341).
                        if not step_shown and label_settled(step_text):
                            # A new TEXT_MESSAGE_START on the same id empties the bubble on both clients:
                            # whatever an earlier step showed gives way to this one, so the intermediate
                            # text is only ever a passing state.
                            yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                            text_started = True
                            step_shown = True
                            delta = strip_thread_label(step_text).lstrip()
                        if step_shown:
                            yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": delta}
                    elif isinstance(event, StepEnded):
                        if event.text and not step_shown:
                            # A step too short to settle its head (a label and a word) is shown whole now.
                            yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                            yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": event.text}
                            text_started = True
                    elif isinstance(event, ToolCallsPlanned):
                        planned = event.uses
                        await self._checkpoint(user_msg_id, state.blocks, planned, state.round_calls)
                    elif isinstance(event, ToolCallEnded):
                        await self._checkpoint(user_msg_id, state.blocks, planned, state.round_calls)
                        if current_context_id:
                            try:
                                await context_repo.append_tool_call(
                                    current_context_id,
                                    {
                                        "name": event.name,
                                        "status": event.status,
                                        "timestamp": datetime.now(UTC).isoformat(),
                                    },
                                )
                            except Exception as e:
                                logger.warning(f"Failed to log tool call to context: {e}")
                        yield {"type": "TOOL_CALL_END", "toolCallId": event.id, "toolName": event.name}
                        # Tool result for the Mind panel
                        yield {
                            "type": "CUSTOM",
                            "name": "tool_result",
                            "value": {"toolCallId": event.id, "toolName": event.name, "status": event.status},
                        }
                    elif isinstance(event, RoundEnded):
                        ui_blocks.extend(blocks_of(event.calls))
                    elif isinstance(event, QuestionAsked):
                        # The turn ends on the question; what was streamed before gives way to it (MAG-717).
                        yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                        text_started = True
                        yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": event.text}
                    elif isinstance(event, SourcesAdded):
                        yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": f"\n\n{event.line}"}
                    elif isinstance(event, ModelFailed):
                        # The phone's chat goes this way: the owner hears of an empty credit from here too (MAG-448).
                        if (reason := provider_reason(event.error)) is not None:
                            await alert_owner(user_id, reason)
                        # Restarted, not appended: the bubble must hold what is stored, and
                        # an earlier step's text is not part of that.
                        yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                        text_started = True
                        yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": API_ERROR_MESSAGE}
                    elif isinstance(event, TurnEnded):
                        answer = API_ERROR_MESSAGE if event.outcome.failed else event.outcome.response
        except asyncio.CancelledError:
            if stopped is not None and stopped():
                state.blocks.extend(record(state.round_calls))
                await self._keep_what_was_done(user_id, msg_id, state.answer, current_context_id, state.blocks)
            raise
        blocks = state.blocks

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

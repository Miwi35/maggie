import asyncio
import json
import logging

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from sqlalchemy.exc import IntegrityError

from app.api.schemas import ChatRequest, ChatResponse
from app.auth import get_current_user_id, get_smoke_account_id
from app.container import container
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import NOTHING_SAID
from app.domain.conversation.service import message_service
from app.llm.context_summary import context_summarizer
from app.llm.duplicates import find_duplicated_turn, find_settled_answer, normalize, user_lock
from app.llm.held_secret import hold_secrets
from app.llm.image import ChatImage
from app.llm.message_reply import grant_approval_answer, with_reply
from app.llm.replace import abandon_approvals, withdraw_turn
from app.llm.screen_context import split as split_screen_context
from app.llm.streaming import answer_message_id
from app.llm.turns import lease_deadline, turn_runner

logger = logging.getLogger(__name__)

router = APIRouter()


MAX_MESSAGE_ID_CHARS = 26


MAX_INTERRUPT_CHARS = 20000


class InterruptRequest(BaseModel):
    # The id the answer was streamed under, when the client had been told it: a cut during the
    # preparation precedes the first word, so there is none and the server mints one.
    messageId: str | None = None
    # What was said aloud or shown before the cut. Empty: not a word, which is a cut like any other.
    spokenText: str = ""


# The `/chat` calls being answered, by user and normalized text: a repeat of one shares its
# result instead of opening a second turn (MAG-386).
_chats_in_flight: dict[tuple[str, str], asyncio.Task] = {}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Send a message to the AI agent and get a response.

    The same text sent again while it is being answered, or less than a minute after, is the
    same message: it gets that answer, and no second turn runs (MAG-386).
    """
    said, screen = split_screen_context(request.message, request.screen_context)
    said, screen = hold_secrets(said, screen)
    screen = with_reply(screen, request.in_reply_to)
    await grant_approval_answer(user_id, request.in_reply_to, said, container.llm_gateway.client)
    logger.info(f"Chat request from user {user_id}: {said[:100]}{_image_note(request.image)}")

    # A screenshot, or the message it answers, makes it another message, whatever the words say:
    # « c'est fait » said to two reminders is two answers.
    if request.image is not None or request.in_reply_to is not None:
        return await _answer_chat(request, user_id, said, screen)

    flight = (user_id, normalize(said))
    running = _chats_in_flight.get(flight)
    if running is not None:
        logger.info(f"Chat request from user {user_id} repeats the one being answered: not answered twice")
        return await asyncio.shield(running)

    async with user_lock(user_id):
        running = _chats_in_flight.get(flight)
        if running is None:
            settled = await find_settled_answer(user_id, said)
            if settled is not None:
                question, answer = settled
                logger.info(f"Chat request from user {user_id} repeats {question.id}, answered: not answered twice")
                return ChatResponse(response=answer.content, messages=[question.to_dict(), answer.to_dict()])
            running = asyncio.create_task(_answer_chat(request, user_id, said, screen))
            _chats_in_flight[flight] = running
            running.add_done_callback(lambda task: _forget_chat(flight, task))
    return await asyncio.shield(running)


def _forget_chat(flight: tuple[str, str], task: asyncio.Task) -> None:
    _chats_in_flight.pop(flight, None)
    # Read here too: a call nobody awaits any more must not leave an « exception never retrieved ».
    if not task.cancelled():
        task.exception()


async def _store_reply(user_id: str, result: dict):
    # Both halves in the thread the question was routed into (MAG-13), and the thread
    # re-summarized — the same two sinks as `POST /agent/proaction` below. Before this,
    # nothing on this path was ever tagged: the exchange existed outside every thread, so
    # the Mind panel never saw it and no summary could be written from it.
    context_id = result.get("context_id")
    assistant_msg = await message_service.create(
        user_id=user_id,
        role="assistant",
        content=result["response"],
        context_id=context_id,
        # And what its tools said, so the next message of the thread sees the calls rather
        # than making them again (MAG-211).
        blocks=result.get("blocks"),
        ui_blocks=result.get("ui_blocks"),
    )
    # Not on a turn the model never answered: the summary would be a second call, as
    # doomed as the first, with the user still waiting on this request — and it would read
    # an apology as if it were the conversation.
    if context_id and not result.get("error"):
        await context_summarizer.maybe_summarize(context_id)
    return assistant_msg


async def _answer_chat(request: ChatRequest, user_id: str, said: str, screen: str | None) -> ChatResponse:
    user_msg = await message_service.create(
        user_id=user_id, role="user", content=said, has_image=request.image is not None
    )

    result = await container.llm_gateway.chat(
        said,
        user_id,
        exclude_message_id=user_msg.id,
        screen_context=screen,
        image=request.image,
        spoken=request.spoken,
        in_car=request.in_car,
    )

    assistant_msg = await _store_reply(user_id, result)

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[user_msg.to_dict(), assistant_msg.to_dict()],
        error=result.get("error"),
        provider_reason=result.get("provider_reason"),
    )


@router.post("/chat/stream")
async def chat_stream(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Stream a chat response using AG-UI protocol (Server-Sent Events).

    The turn runs in the background and this response only follows it: a client that
    leaves does not cancel the answer, which is stored and published all the same (MAG-344).
    """
    # The screen the assistant was summoned from reaches the model, not the message that
    # is stored and published — this is the route the overlay streams on (MAG-30).
    said, screen = split_screen_context(request.message, request.screen_context)
    said, screen = hold_secrets(said, screen)
    screen = with_reply(screen, request.in_reply_to)
    await grant_approval_answer(user_id, request.in_reply_to, said, container.llm_gateway.client)
    logger.info(f"Stream chat request from user {user_id}: {said[:100]}{_image_note(request.image)}")

    key = request.idempotency_key
    # Held until the message is stored: two requests at once cannot both pass the look-up.
    async with user_lock(user_id):
        received = await message_repo.find_by_client_key(user_id, key) if key else None
        carried_over = None
        if received is not None:
            logger.info(f"Message {received.id} already received under key {key}: not answered twice")
        else:
            if request.replaces:
                # Before the look-up below: the corrected words may be the old ones, and must not
                # be taken for a repeat of the turn that is being taken back.
                carried_over = await withdraw_turn(user_id, request.replaces)
            if request.image is None and request.in_reply_to is None:
                received = await find_duplicated_turn(user_id, said)
            if received is not None:
                # No key in common, but the same words while that turn runs or just ended (MAG-386):
                # not a second message, not a second turn — this response follows the first.
                logger.info(f"Message {received.id} repeated without its key: not answered twice")
        if received is None:
            try:
                user_msg = await message_service.create(
                    user_id=user_id,
                    role="user",
                    content=said,
                    client_key=key,
                    turn_lease_until=lease_deadline(),
                    turn_screen_context=screen,
                    has_image=request.image is not None,
                )
            except IntegrityError:
                # The same key, twice at once: the other request won, and owns the turn.
                received = await message_repo.find_by_client_key(user_id, key) if key else None
                if received is None:
                    raise
                logger.info(f"Message {received.id} already received under key {key}: not answered twice")
            else:
                events = turn_runner.start(
                    container.streaming_gateway,
                    user_id=user_id,
                    message_id=user_msg.id,
                    message=said,
                    screen_context=screen,
                    image=request.image,
                    carried_over=carried_over,
                    spoken=request.spoken,
                    in_car=request.in_car,
                )
    if received is not None:
        events = turn_runner.replay(user_id, received)

    async def generate():
        async for event in events:
            yield ": keep-alive\n\n" if event is None else f"data: {json.dumps(event)}\n\n"

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _image_note(image: ChatImage | None) -> str:
    return f" [image: {image.describe()}]" if image else ""


@router.delete("/smoke/history")
async def reset_smoke_history(smoke_user_id: str = Depends(get_smoke_account_id)):
    """Wipe the conversation of the technical smoke account, so each run starts from a blank one (MAG-253)."""
    deleted_messages = await message_repo.delete_by_user(smoke_user_id)
    deleted_contexts = await context_repo.delete_by_user(smoke_user_id)
    logger.info(f"Smoke history reset: {deleted_messages} messages, {deleted_contexts} contexts")
    return {"deletedMessages": deleted_messages, "deletedContexts": deleted_contexts}


@router.post("/chat/interrupt")
async def chat_interrupt(request: InterruptRequest, user_id: str = Depends(get_current_user_id)):
    """The user cut Maggie off: keep of her answer what was said or shown, and mark it interrupted (MAG-223).

    Also the « Arrêter » of the written chat (MAG-320): the turn still running is cancelled, and
    the approval cards it left waiting are abandoned.

    Closing the stream stops what the phone shows; this is what the next turn is told. It is
    idempotent, and works on both orders of events — the answer already stored (a voice reply
    cut while being read) or never stored (the stream was closed first) — so the client does
    not have to know which one it lost the race to.
    """
    message_id = request.messageId
    if message_id is not None and not 0 < len(message_id) <= MAX_MESSAGE_ID_CHARS:
        raise HTTPException(status_code=400, detail="Invalid messageId")
    if len(request.spokenText) > MAX_INTERRUPT_CHARS:
        raise HTTPException(status_code=400, detail="spokenText is too long")
    content = request.spokenText.strip() or NOTHING_SAID

    # The question being answered carries the thread the answer belongs to, and fixes the id of
    # its answer: a turn still running (it outlives the stream) then finds the row taken and
    # stores nothing, instead of leaving the full answer beside the interrupted one.
    question = await message_repo.find_last(user_id, role="user")
    if message_id is None and question is not None:
        message_id = answer_message_id(question.id)

    # A turn still running is stopped, not only left to be ignored (MAG-320): no tool starts after
    # this, and the ones that had acted are kept on the answer being cut, below.
    if question is not None and await turn_runner.stop(question.id):
        stopped = await message_repo.get(answer_message_id(question.id))
        await abandon_approvals(stopped.blocks if stopped is not None else None)

    existing = await message_repo.get(message_id) if message_id else None
    if existing is None:
        try:
            msg = await message_service.create(
                user_id=user_id,
                role="assistant",
                content=content,
                context_id=question.context_id if question else None,
                message_id=message_id,
                interrupted=True,
            )
            return msg.to_dict()
        except IntegrityError:
            # The turn stored its answer between the lookup and here: cut that one.
            existing = await message_repo.get(message_id) if message_id else None
            if existing is None:
                raise
    if existing.user_id != user_id:
        raise HTTPException(status_code=404, detail="Message not found")
    if existing.role != "assistant":
        raise HTTPException(status_code=400, detail="Only an answer of Maggie can be interrupted")
    msg = await message_service.mark_interrupted(existing.id, content)

    return msg.to_dict() if msg else {}


@router.get("/contexts")
async def get_contexts(
    user_id: str = Depends(get_current_user_id),
    include_closed: bool = Query(
        default=False, alias="includeClosed", description="Also the closed threads, which a clean-up needs to reach"
    ),
):
    """Get the threads, each with how many messages it holds (what deleting it takes away)."""
    contexts = await (context_repo.find_by_user(user_id) if include_closed else context_repo.find_active(user_id))
    counts = await message_repo.count_by_user_contexts(user_id)
    return [{**c.to_dict(), "messageCount": counts.get(c.id, 0)} for c in contexts]


@router.delete("/contexts/{context_id}")
async def delete_context(context_id: str, user_id: str = Depends(get_current_user_id)):
    """Delete one of the caller's threads and its messages (MAG-342). 404 for another user's, like an unknown one."""
    deleted_messages = await context_repo.delete_with_messages(context_id, user_id)
    if deleted_messages is None:
        raise HTTPException(status_code=404, detail="Context not found")
    return {"deletedMessages": deleted_messages}

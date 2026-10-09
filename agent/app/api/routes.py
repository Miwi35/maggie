import json
import logging
from datetime import UTC, datetime
from typing import Literal

import anthropic
import openai
from fastapi import APIRouter, BackgroundTasks, Depends, Form, HTTPException, Query, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.exc import IntegrityError

from app.auth import (
    get_current_user_id,
    get_smoke_account_id,
    require_proaction_trigger,
    require_service_token,
)
from app.config import settings
from app.db.context_repository import context_repo
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.message_repository import message_repo
from app.db.pending_action_model import PendingAction, PendingActionStatus
from app.db.pending_action_repository import pending_action_repo
from app.db.models import NOTHING_SAID
from app.db.proaction_repository import proaction_repo
from app.db.user_data import purge_user_data
from app.db.user_setting_repository import user_setting_repo
from app.llm.context_summary import context_summarizer
from app.llm.gateway import LLMGateway
from app.llm.runner import run_tool_loop
from app.llm.screen_context import split as split_screen_context
from app.llm.streaming import StreamingGateway
from app.llm.transcription import CLEANUP_MODES, transcribe_audio
from app.llm.turns import lease_deadline, turn_runner
from app.queue.proaction_consumer import execute_proaction
from app.queue.scheduler import generate_proactions
from app.skills.index import render_markdown, skill_index
from app.tts.synthesis import DEFAULT_VOICE, VOICE_IDS, get_voices, synthesize_speech

logger = logging.getLogger(__name__)

router = APIRouter()

llm_gateway = LLMGateway()
streaming_gateway = StreamingGateway()


class ChatRequest(BaseModel):
    # Non-empty: an empty message has nothing to route, nothing to answer, and the model
    # refuses a conversation whose only turn is an empty string — a 422 naming the field
    # beats « Désolé, une erreur est survenue » (MAG-13).
    message: str = Field(min_length=1)

    # What the screen behind the assistant overlay was showing (MAG-30). Its own field,
    # not a block glued to `message`: what is stored is what is displayed, by every
    # client, so a block inside the message is a bubble full of the page it was about.
    screen_context: str | None = None

    # One per message the client composes, kept for every retry of it (MAG-344): a request
    # that failed on the way back may have been received, and sending it again must not
    # make a second message — or a second answer.
    idempotency_key: str | None = Field(default=None, min_length=1, max_length=64)


MAX_MESSAGE_ID_CHARS = 26
MAX_INTERRUPT_CHARS = 20000


class InterruptRequest(BaseModel):
    # The id the answer was streamed under, when the client had been told it: a cut during the
    # preparation precedes the first word, so there is none and the server mints one.
    messageId: str | None = None
    # What was said aloud or shown before the cut. Empty: not a word, which is a cut like any other.
    spokenText: str = ""


class ChatResponse(BaseModel):
    response: str
    tool_calls: list[dict] = []
    messages: list[dict] = []


class PersonalityUpdate(BaseModel):
    name: str | None = None
    language: str | None = None
    backstory: str | None = None


class InstructionCreate(BaseModel):
    content: str
    # Defaulted rather than required: every directive stored before MAG-22 was a
    # planning rule, and so is everything the admin's form sent until now.
    kind: InstructionKind = InstructionKind.PLANNING


class SkillCreate(BaseModel):
    name: str = Field(max_length=200)
    description: str
    tags: list[str]
    content: str


class SkillUpdate(BaseModel):
    description: str | None = None
    tags: list[str] | None = None
    content: str | None = None


@router.get("/health")
async def health():
    return {"status": "ok", "service": "maggie-agent-hub"}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Send a message to the AI agent and get a response."""
    said, screen = split_screen_context(request.message, request.screen_context)
    logger.info(f"Chat request from user {user_id}: {said[:100]}")

    user_msg = await message_repo.create(user_id=user_id, role="user", content=said)

    result = await llm_gateway.chat(said, user_id, exclude_message_id=user_msg.id, screen_context=screen)

    # Both halves in the thread the question was routed into (MAG-13), and the thread
    # re-summarized — the same two sinks as `POST /agent/proaction` below. Before this,
    # nothing on this path was ever tagged: the exchange existed outside every thread, so
    # the Mind panel never saw it and no summary could be written from it.
    context_id = result.get("context_id")
    assistant_msg = await message_repo.create(
        user_id=user_id,
        role="assistant",
        content=result["response"],
        context_id=context_id,
        # And what its tools said, so the next message of the thread sees the calls rather
        # than making them again (MAG-211).
        blocks=result.get("blocks"),
    )
    # Not on a turn the model never answered: the summary would be a second call, as
    # doomed as the first, with the user still waiting on this request — and it would read
    # an apology as if it were the conversation.
    if context_id and not result.get("error"):
        await context_summarizer.maybe_summarize(context_id)

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[user_msg.to_dict(), assistant_msg.to_dict()],
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
    logger.info(f"Stream chat request from user {user_id}: {said[:100]}")

    key = request.idempotency_key
    received = await message_repo.find_by_client_key(user_id, key) if key else None
    if received is None:
        try:
            user_msg = await message_repo.create(
                user_id=user_id,
                role="user",
                content=said,
                client_key=key,
                turn_lease_until=lease_deadline(),
                turn_screen_context=screen,
            )
        except IntegrityError:
            # The same key, twice at once: the other request won, and owns the turn.
            received = await message_repo.find_by_client_key(user_id, key) if key else None
            if received is None:
                raise
        else:
            events = turn_runner.start(
                streaming_gateway, user_id=user_id, message_id=user_msg.id, message=said, screen_context=screen
            )
    if received is not None:
        logger.info(f"Message {received.id} already received under key {key}: not answered twice")
        events = turn_runner.replay(user_id, received)

    async def generate():
        async for event in events:
            yield ": keep-alive\n\n" if event is None else f"data: {json.dumps(event)}\n\n"

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


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

    Closing the stream is what stops the model; this is what the next turn is told. It is
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

    existing = await message_repo.get(message_id) if message_id else None
    if existing is not None:
        if existing.user_id != user_id:
            raise HTTPException(status_code=404, detail="Message not found")
        if existing.role != "assistant":
            raise HTTPException(status_code=400, detail="Only an answer of Maggie can be interrupted")
        msg = await message_repo.mark_interrupted(existing.id, content)
    else:
        # The question being answered carries the thread the answer belongs to.
        question = await message_repo.find_last(user_id, role="user")
        msg = await message_repo.create(
            user_id=user_id,
            role="assistant",
            content=content,
            context_id=question.context_id if question else None,
            message_id=message_id,
            interrupted=True,
        )

    return msg.to_dict() if msg else {}


@router.get("/contexts")
async def get_contexts(user_id: str = Depends(get_current_user_id)):
    """Get active and dormant contexts for the Mind Panel."""
    contexts = await context_repo.find_active(user_id)
    return [c.to_dict() for c in contexts]


@router.post("/proaction", response_model=ChatResponse)
async def proaction(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Execute a proaction prompt autonomously, reading the open threads but no message history."""
    logger.info(f"Proaction request for user {user_id}: {request.message[:100]}")

    result = await llm_gateway.proaction(request.message, user_id)

    # Stored in the thread the gateway routed it to, so a reply to it stays in the same
    # one — and that thread is re-summarized, since the reply will be routed against its
    # summary. Both are what `proaction_consumer` does with a scheduled proaction
    # (MAG-14): a proaction has two sinks, and they must leave a thread in the same
    # state. Where they still differ is the empty answer, which the consumer drops and
    # this route stores, because `ChatResponse` owes its caller a message either way.
    # Awaited, not spawned: nothing is streaming, and `maybe_summarize` never raises.
    context_id = result.get("context_id")
    assistant_msg = await message_repo.create(
        user_id=user_id,
        role="assistant",
        content=result["response"],
        context_id=context_id,
        blocks=result.get("blocks"),
    )
    if context_id:
        await context_summarizer.maybe_summarize(context_id)

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[assistant_msg.to_dict()],
    )


# --- Approvals (MAG-5) ---


def _is_overdue(action: PendingAction) -> bool:
    expires_at = action.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=UTC)
    return expires_at <= datetime.now(UTC)


def _is_error_result(result: str) -> bool:
    try:
        parsed = json.loads(result)
    except (TypeError, ValueError):
        return False
    return isinstance(parsed, dict) and "error" in parsed


async def _pending_action_or_error(user_id: str, action_id: str) -> PendingAction:
    """The user's action if it can still be answered — 404, 409 or 410 otherwise.

    The owner is part of the query: someone else's action is as unknown as a made-up id.
    """
    action = await pending_action_repo.get_for_user(user_id, action_id)
    if action is None:
        raise HTTPException(status_code=404, detail="Approval not found")
    if action.status == PendingActionStatus.EXPIRED:
        raise HTTPException(status_code=410, detail="Approval has expired")
    if action.status != PendingActionStatus.PENDING:
        raise HTTPException(status_code=409, detail="Approval is not pending")
    if _is_overdue(action):
        raise HTTPException(status_code=410, detail="Approval has expired")
    return action


async def announce_approved_action(action: PendingAction, result: str) -> None:
    """Have Maggie say what the approved action did, in the thread it was asked in.

    Runs after the response has gone out, so it never raises: the action is already done and
    recorded, and a failed announcement must not look like a failed approval. Same ending as
    `execute_proaction` — the message is stored (and published on the chat topic by
    `message_repo`) in the thread, which is then re-summarized.
    """
    if llm_gateway.client is None:
        return

    prompt = (
        f"L'utilisateur a validé {action.tool_name}({json.dumps(action.arguments, ensure_ascii=False)}). "
        f"Résultat : {result}. Annonce-le brièvement."
    )
    try:
        system = await llm_gateway._build_system_prompt(action.user_id, current_context_id=action.context_id)
        answer = await run_tool_loop(
            system,
            [{"role": "user", "content": prompt}],
            None,
            client=llm_gateway.client,
            tool_router=llm_gateway.tool_router,
            user_id=action.user_id,
            model=settings.anthropic_model,
            call_type="approval",
            source="approval",
            context_id=action.context_id,
        )
        if not answer["response"]:
            return
        await message_repo.create(
            user_id=action.user_id, role="assistant", content=answer["response"], context_id=action.context_id
        )
        if action.context_id:
            await context_summarizer.maybe_summarize(action.context_id)
    except Exception as e:
        logger.error(f"Announcement of approved action {action.id} failed: {e}")


@router.get("/approvals")
async def get_approvals(
    status: Literal["pending"] = Query(default="pending"),
    user_id: str = Depends(get_current_user_id),
):
    """The authenticated user's actions waiting for an answer, oldest first."""
    actions = await pending_action_repo.find_pending(user_id)
    return [a.to_dict() for a in actions]


@router.post("/approvals/{approval_id}/approve")
async def approve_action(
    approval_id: str,
    background_tasks: BackgroundTasks,
    user_id: str = Depends(get_current_user_id),
):
    """Run the held call with its frozen arguments, record the outcome, and announce it in the background.

    The row is claimed as approved *before* the call runs: two tabs clicking at once, or the
    scheduler expiring the action meanwhile, get a 409 instead of a second execution.
    """
    action = await _pending_action_or_error(user_id, approval_id)

    claimed = await pending_action_repo.decide(approval_id, PendingActionStatus.APPROVED)
    if claimed is None:
        raise HTTPException(status_code=409, detail="Approval is not pending")

    try:
        result = await llm_gateway.tool_router.call_tool(
            action.tool_name, action.arguments or {}, user_id, source="approval"
        )
    except Exception as e:
        logger.error(f"Approved action {approval_id} failed to run: {e}")
        result = json.dumps({"error": f"Tool call failed: {e}"})

    status = PendingActionStatus.FAILED if _is_error_result(result) else PendingActionStatus.APPROVED
    settled = await pending_action_repo.settle(approval_id, status, result)
    final = settled or claimed

    background_tasks.add_task(announce_approved_action, final, result)
    return final.to_dict()


@router.post("/approvals/{approval_id}/deny")
async def deny_action(approval_id: str, user_id: str = Depends(get_current_user_id)):
    """Refuse the held call: nothing runs and the model is not called."""
    await _pending_action_or_error(user_id, approval_id)

    denied = await pending_action_repo.decide(approval_id, PendingActionStatus.DENIED)
    if denied is None:
        raise HTTPException(status_code=409, detail="Approval is not pending")
    return denied.to_dict()


@router.get("/messages")
async def get_messages(
    user_id: str = Depends(get_current_user_id),
    after: str | None = Query(default=None, description="ISO timestamp for incremental sync"),
    before: str | None = Query(default=None, description="Message ID for cursor pagination (older messages)"),
    limit: int = Query(default=20, ge=1, le=50, description="Number of messages to return"),
):
    """Get conversation messages for a user with cursor-based pagination."""
    if before:
        messages = await message_repo.find_before(user_id, before_id=before, limit=limit)
    elif after:
        messages = await message_repo.find_after(user_id, after=after)
    else:
        messages = await message_repo.find_recent(user_id, limit=limit)

    return [msg.to_dict() for msg in messages]


@router.get("/messages/search")
async def search_messages(
    user_id: str = Depends(get_current_user_id),
    q: str = Query(..., min_length=1, description="Search query"),
    limit: int = Query(default=20, ge=1, le=50),
):
    """Search messages by content."""
    messages = await message_repo.search(user_id, q, limit=limit)
    return [msg.to_dict() for msg in messages]


@router.get("/messages/context")
async def get_message_context(
    user_id: str = Depends(get_current_user_id),
    around: str = Query(..., description="Message ID to load context around"),
):
    """Load messages around a specific message (for search result navigation)."""
    result = await message_repo.find_around(user_id, around)
    return {
        "messages": [msg.to_dict() for msg in result["messages"]],
        "targetIndex": result["targetIndex"],
    }


@router.get("/proactions")
async def get_proactions(user_id: str = Depends(get_current_user_id)):
    """Get all proactions for the authenticated user."""
    proactions = await proaction_repo.find_by_user(user_id)
    return [p.to_dict() for p in proactions]


@router.post("/proactions/generate")
async def generate_user_proactions(
    dry_run: bool = Query(default=False),
    user_id: str = Depends(require_proaction_trigger),
):
    """Run the daily proaction generator for the caller now and say what it planned (MAG-249).

    The same call the scheduler makes every morning. With `dry_run=true` nothing is recorded: the tools
    that write are simulated and the plan is read from what the model tried to schedule.
    """
    known = {p.id for p in await proaction_repo.find_by_user(user_id)}
    try:
        result = await generate_proactions(llm_gateway, user_id, dry_run=dry_run)
    except Exception as e:
        logger.error(f"Proaction generation failed for user {user_id}: {e}")
        raise HTTPException(status_code=502, detail=f"Proaction generation failed: {e}") from e

    simulated = result.get("simulated_tools", [])
    if dry_run:
        planned = [
            {"prompt": t["input"].get("prompt"), "scheduledAt": t["input"].get("scheduled_at"), "simulated": True}
            for t in simulated
            if t["name"] == "schedule_proaction"
        ]
    else:
        planned = [p.to_dict() for p in await proaction_repo.find_by_user(user_id) if p.id not in known]

    return {
        "dryRun": dry_run,
        "planned": planned,
        "response": result["response"],
        "toolCalls": result.get("tool_calls", []),
        "simulatedTools": simulated,
    }


@router.post("/proactions/{proaction_id}/execute")
async def execute_user_proaction(
    proaction_id: str,
    dry_run: bool = Query(default=False),
    user_id: str = Depends(require_proaction_trigger),
):
    """Run one of the caller's scheduled proactions now, through the consumer's path, and return its message (MAG-249).

    A real run takes the proaction (pending only: 409 once it was taken, so it cannot run twice); a dry run
    leaves it pending, stores nothing and simulates the tools that write.
    """
    proaction = await proaction_repo.get(proaction_id)
    if proaction is None or proaction.user_id != user_id:
        raise HTTPException(status_code=404, detail="Proaction not found")

    if not dry_run and not await proaction_repo.claim(proaction_id):
        raise HTTPException(status_code=409, detail="Proaction is not pending")

    try:
        result = await execute_proaction(llm_gateway, proaction, dry_run=dry_run)
    except Exception as e:
        logger.error(f"Execution of proaction {proaction_id} failed: {e}")
        raise HTTPException(status_code=502, detail=f"Proaction execution failed: {e}") from e

    return {"id": proaction_id, "dryRun": dry_run, **result}


class RecetteResetRequest(BaseModel):
    userId: str = Field(min_length=1)
    dryRun: bool = False


@router.post("/internal/recette/reset")
async def reset_recette_data(request: RecetteResetRequest, _: None = Depends(require_service_token)):
    """Wipe the agent's rows of one user — called by `app:recette:reset` with the service token (MAG-249).

    The user is the API command's constant, never a client input: no user token reaches this route.
    """
    return {"deleted": await purge_user_data(request.userId, dry_run=request.dryRun)}


@router.get("/personality")
async def get_personality(user_id: str = Depends(get_current_user_id)):
    """Return current personality configuration."""
    return await llm_gateway.personality.get_config(user_id)


@router.put("/personality")
async def update_personality(data: PersonalityUpdate, user_id: str = Depends(get_current_user_id)):
    """Update personality configuration and reload."""
    updates = data.model_dump(exclude_none=True)
    return await llm_gateway.personality.update_config(user_id, updates)


MAX_AUDIO_SIZE = 25 * 1024 * 1024  # 25 MB (Whisper limit)


@router.post("/transcribe")
async def transcribe(
    audio: UploadFile,
    cleanup: str = Form(default="auto"),
    _user_id: str = Depends(get_current_user_id),
):
    """Transcribe audio via Whisper, and tidy it only if asked and only if needed.

    `cleanup=none` never reaches the model — what a conversation with Maggie sends,
    since she reads through a hesitation herself (MAG-222). `cleanup=auto`, the
    default, calls the fast model for a text destined to be written as is, and only
    when `needs_cleanup` says it would gain from it.
    """
    if cleanup not in CLEANUP_MODES:
        raise HTTPException(status_code=400, detail=f"cleanup must be one of {', '.join(CLEANUP_MODES)}")

    contents = await audio.read()
    if not contents:
        raise HTTPException(status_code=400, detail="Empty audio file")
    if len(contents) > MAX_AUDIO_SIZE:
        raise HTTPException(status_code=400, detail="Audio file exceeds 25 MB limit")

    try:
        result = await transcribe_audio(contents, audio.filename or "audio.webm", cleanup=cleanup)
    except openai.RateLimitError as exc:
        logger.warning("Whisper quota exhausted: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Quota OpenAI dépassé — recharge le compte pour réactiver la transcription.",
        ) from exc
    except anthropic.RateLimitError as exc:
        logger.warning("Anthropic rate-limited during transcription cleanup: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Anthropic est temporairement rate-limité, réessaie dans quelques instants.",
        ) from exc
    except openai.AuthenticationError as exc:
        logger.error("OpenAI authentication failed: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Clé API OpenAI invalide — vérifie la configuration côté agent.",
        ) from exc
    except (openai.APIError, anthropic.APIError) as exc:
        logger.exception("LLM provider error during transcription")
        raise HTTPException(
            status_code=502,
            detail=f"Le service de transcription a échoué : {exc.__class__.__name__}",
        ) from exc

    return result


# --- TTS ---

MAX_TTS_CHARS = 5000


class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TTS_CHARS)
    voice: str = DEFAULT_VOICE


@router.get("/tts/voices")
async def tts_voices(_user_id: str = Depends(get_current_user_id)):
    """Return curated list of available TTS voices."""
    return get_voices()


@router.post("/tts/synthesize")
async def tts_synthesize(request: TtsRequest, _user_id: str = Depends(get_current_user_id)):
    """Synthesize text to MP3 audio via Edge TTS."""
    if request.voice not in VOICE_IDS:
        raise HTTPException(status_code=400, detail=f"Unknown voice: {request.voice}")

    return StreamingResponse(
        synthesize_speech(request.text, request.voice),
        media_type="audio/mpeg",
    )


@router.get("/tts/voice")
async def get_tts_voice(user_id: str = Depends(get_current_user_id)):
    """Get the user's preferred TTS voice."""
    setting = await user_setting_repo.get(user_id)
    return {"voice": setting.tts_voice if setting and setting.tts_voice else DEFAULT_VOICE}


@router.put("/tts/voice")
async def set_tts_voice(request: dict, user_id: str = Depends(get_current_user_id)):
    """Set the user's preferred TTS voice."""
    voice = request.get("voice", "")
    if voice not in VOICE_IDS:
        raise HTTPException(status_code=400, detail=f"Unknown voice: {voice}")
    await user_setting_repo.set_tts_voice(user_id, voice)
    return {"voice": voice}


# --- Instructions ---


@router.get("/instructions")
async def get_instructions(
    kind: InstructionKind | None = None,
    user_id: str = Depends(get_current_user_id),
):
    """List the authenticated user's instructions, of one kind or of all kinds."""
    instructions = await instruction_repo.find_by_user(user_id, kind=kind)
    return [i.to_dict() for i in instructions]


@router.post("/instructions", status_code=201)
async def create_instruction(data: InstructionCreate, user_id: str = Depends(get_current_user_id)):
    """Create a new instruction."""
    instruction = await instruction_repo.store(user_id, data.content, kind=data.kind)
    return instruction.to_dict()


@router.delete("/instructions/{instruction_id}")
async def delete_instruction(instruction_id: str, user_id: str = Depends(get_current_user_id)):
    """Delete an instruction of the authenticated user."""
    deleted = await instruction_repo.delete(user_id, instruction_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Instruction not found")
    return {"deleted": True}


# --- Skills ---


@router.get("/skills")
async def get_skills(_user_id: str = Depends(get_current_user_id)):
    """List all skills (name, description, tags)."""
    entries = skill_index.list_all()
    return [{"name": e.name, "description": e.description, "tags": e.tags} for e in entries]


@router.get("/skills/{name}")
async def get_skill_detail(name: str, _user_id: str = Depends(get_current_user_id)):
    """Get full skill content by name."""
    skill = await skill_index.get_skill(name)
    if skill is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {
        "name": skill.name,
        "description": skill.description,
        "tags": list(skill.tags or []),
        "content": render_markdown(skill.name, skill.description, list(skill.tags or []), skill.content),
    }


@router.post("/skills", status_code=201)
async def create_skill_endpoint(data: SkillCreate, user_id: str = Depends(get_current_user_id)):
    """Create a new skill."""
    entry = await skill_index.create(data.name, data.description, data.tags, data.content, user_id)
    return {"name": entry.name, "description": entry.description, "tags": entry.tags}


@router.put("/skills/{name}")
async def update_skill_endpoint(name: str, data: SkillUpdate, user_id: str = Depends(get_current_user_id)):
    """Update an existing skill."""
    entry = await skill_index.update(
        name,
        description=data.description,
        tags=data.tags,
        content=data.content,
        user_id=user_id,
    )
    if entry is None:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {"name": entry.name, "description": entry.description, "tags": entry.tags}


@router.delete("/skills/{name}")
async def delete_skill_endpoint(name: str, user_id: str = Depends(get_current_user_id)):
    """Delete a skill."""
    deleted = await skill_index.delete(name, user_id=user_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Skill not found")
    return {"deleted": True}

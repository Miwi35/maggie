import logging
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query

from app.api.schemas import ChatRequest, ChatResponse
from app.auth import get_current_user_id, require_proaction_trigger
from app.container import container
from app.db.journal_model import PLANNING, PROACTION
from app.db.journal_repository import journal_repo
from app.db.message_repository import message_repo
from app.llm.context_summary import context_summarizer
from app.proactions import api as proactions_api
from app.queue.proaction_consumer import execute_proaction
from app.queue.scheduler import generate_proactions

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/proaction", response_model=ChatResponse)
async def proaction(request: ChatRequest, user_id: str = Depends(get_current_user_id)):
    """Execute a proaction prompt autonomously, reading the open threads but no message history."""
    logger.info(f"Proaction request for user {user_id}: {request.message[:100]}")

    result = await container.llm_gateway.proaction(request.message, user_id, spoken=request.spoken)

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
        ui_blocks=result.get("ui_blocks"),
    )
    if context_id:
        await context_summarizer.maybe_summarize(context_id)

    return ChatResponse(
        response=result["response"],
        tool_calls=result.get("tool_calls", []),
        messages=[assistant_msg.to_dict()],
    )


async def _user_proactions(user_id: str) -> list[proactions_api.Proaction]:
    """The user's proactions from the API (MAG-327); 502 when it cannot be read."""
    try:
        return await proactions_api.list_for_user(user_id)
    except proactions_api.ProactionApiError as e:
        logger.error(f"Could not read the proactions of user {user_id}: {e}")
        raise HTTPException(status_code=502, detail="Proactions unavailable") from e


@router.get("/proactions")
async def get_proactions(user_id: str = Depends(get_current_user_id)):
    """Get all proactions for the authenticated user, latest first — read from the API (MAG-327)."""
    return [p.to_dict() for p in reversed(await _user_proactions(user_id))]


@router.post("/proactions/generate")
async def generate_user_proactions(
    dry_run: bool = Query(default=False),
    user_id: str = Depends(require_proaction_trigger),
):
    """Run the daily proaction generator for the caller now and say what it planned (MAG-249).

    The same call the scheduler makes every morning. With `dry_run=true` nothing is recorded: the tools
    that write are simulated and the plan is read from what the model tried to schedule.
    """
    known = set() if dry_run else {p.id for p in await _user_proactions(user_id)}
    try:
        result = await generate_proactions(container.llm_gateway, user_id, dry_run=dry_run)
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
        planned = [p.to_dict() for p in await _user_proactions(user_id) if p.id not in known]

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

    A real run claims the proaction on the API (pending only: 409 once it was taken, so it cannot run twice);
    a dry run leaves it pending, stores nothing and simulates the tools that write.
    """
    proaction = next((p for p in await _user_proactions(user_id) if p.id == proaction_id), None)
    if proaction is None:
        raise HTTPException(status_code=404, detail="Proaction not found")

    if not dry_run:
        try:
            claimed = await proactions_api.claim_due(proaction_id, user_id=user_id)
        except proactions_api.ProactionApiError as e:
            logger.error(f"Could not claim proaction {proaction_id}: {e}")
            raise HTTPException(status_code=502, detail="Proactions unavailable") from e
        if not claimed:
            raise HTTPException(status_code=409, detail="Proaction is not pending")
        proaction = claimed[0]

    try:
        result = await execute_proaction(container.llm_gateway, proaction, dry_run=dry_run)
    except Exception as e:
        logger.error(f"Execution of proaction {proaction_id} failed: {e}")
        raise HTTPException(status_code=502, detail=f"Proaction execution failed: {e}") from e

    return {"id": proaction_id, "dryRun": dry_run, **result}


def _with_origin(entries: list[dict]) -> list[dict]:
    """Each proaction run gets the planning that scheduled it, when the journal still has it: its origin.

    The instructions that planning read are the closest the journal comes to « the instruction it came
    from »: the model chose among them, and nothing records which one.
    """
    planned_by = {
        scheduled["id"]: entry
        for entry in reversed(entries)
        if entry["kind"] == PLANNING
        for scheduled in entry["scheduled"]
        if scheduled.get("id")
    }
    for entry in entries:
        if entry["kind"] == PROACTION:
            planning = planned_by.get(entry["proactionId"])
            entry["origin"] = (
                {"planningId": planning["id"], "day": planning["day"], "instructions": planning["instructions"]}
                if planning
                else None
            )
    return entries


@router.get("/journal")
async def get_journal(
    days: int = Query(default=7, ge=1, le=366),
    user_id: str = Depends(get_current_user_id),
):
    """The caller's plannings and proaction runs of the last `days` days, newest first (MAG-688)."""
    since = datetime.now(UTC) - timedelta(days=days)
    return _with_origin([run.to_dict() for run in await journal_repo.find_by_user(user_id, since)])


@router.post("/journal/planning")
async def run_journal_planning(user_id: str = Depends(get_current_user_id)):
    """« Relancer la planification maintenant » (MAG-688): the caller's daily planning, now, and its journal entry.

    The same run as the morning's, for the caller alone — so a new instruction is tried without waiting
    for tomorrow. Today's proactions already scheduled are left be: the planning reads them first.
    """
    try:
        result = await generate_proactions(container.llm_gateway, user_id)
    except Exception as e:
        logger.error(f"Planning on demand failed for user {user_id}: {e}")
        raise HTTPException(status_code=502, detail="La planification a échoué : voir le journal.") from e
    if result.get("journal") is None:
        raise HTTPException(status_code=500, detail="La planification a tourné mais le journal ne l'a pas gardée.")
    return result["journal"]

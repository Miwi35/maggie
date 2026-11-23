from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.auth import get_current_user_id
from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo
from app.db.style_proposal_model import StyleProposalStatus
from app.db.style_proposal_repository import style_proposal_repo
from app.llm.style_synthesis import style_synthesizer
from app.personality.engine import PersonalityEngine

router = APIRouter()
personality = PersonalityEngine()


class PersonalityUpdate(BaseModel):
    name: str | None = None
    language: str | None = None
    backstory: str | None = None


class InstructionCreate(BaseModel):
    content: str
    # Defaulted rather than required: every directive stored before MAG-22 was a
    # planning rule, and so is everything the admin's form sent until now.
    kind: InstructionKind = InstructionKind.PLANNING


class StyleProposalAccept(BaseModel):
    # The owner may reword the proposal before accepting it; absent, it is taken as proposed.
    content: str | None = Field(default=None, min_length=1)


@router.get("/personality")
async def get_personality(user_id: str = Depends(get_current_user_id)):
    """Return current personality configuration."""
    return await personality.get_config(user_id)


@router.put("/personality")
async def update_personality(data: PersonalityUpdate, user_id: str = Depends(get_current_user_id)):
    """Update personality configuration and reload."""
    updates = data.model_dump(exclude_none=True)
    return await personality.update_config(user_id, updates)


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


@router.get("/style-proposals")
async def get_style_proposals(
    status: StyleProposalStatus | None = StyleProposalStatus.PENDING,
    user_id: str = Depends(get_current_user_id),
):
    """The directives drawn from the owner's thumbs — the pending ones by default."""
    return [p.to_dict() for p in await style_proposal_repo.find_by_user(user_id, status=status)]


@router.post("/style-proposals/generate")
async def generate_style_proposals(user_id: str = Depends(get_current_user_id)):
    """Read the thumbs given since the last pass now, whatever their number, instead of waiting for the daily one."""
    proposals = await style_synthesizer.synthesize(user_id, min_feedback=1)
    return [p.to_dict() for p in proposals]


@router.post("/style-proposals/{proposal_id}/accept")
async def accept_style_proposal(
    proposal_id: str, data: StyleProposalAccept | None = None, user_id: str = Depends(get_current_user_id)
):
    """The owner accepts a proposal: it becomes a behaviour directive, read on every turn from then on."""
    if await style_proposal_repo.get(user_id, proposal_id) is None:
        raise HTTPException(status_code=404, detail="Proposal not found")
    content = data.content.strip() if data and data.content else None
    accepted = await style_proposal_repo.decide(user_id, proposal_id, StyleProposalStatus.ACCEPTED, content)
    if accepted is None:
        raise HTTPException(status_code=409, detail="Proposal is not pending")
    try:
        instruction = await instruction_repo.store(user_id, accepted.content, kind=InstructionKind.BEHAVIOR)
    except Exception:
        # Accepted without a directive would be a proposal consumed for nothing: offer it again.
        await style_proposal_repo.reopen(user_id, proposal_id)
        raise
    return {"proposal": accepted.to_dict(), "instruction": instruction.to_dict()}


@router.post("/style-proposals/{proposal_id}/reject")
async def reject_style_proposal(proposal_id: str, user_id: str = Depends(get_current_user_id)):
    """The owner refuses a proposal: nothing changes, and the synthesis will not offer it again."""
    if await style_proposal_repo.get(user_id, proposal_id) is None:
        raise HTTPException(status_code=404, detail="Proposal not found")
    rejected = await style_proposal_repo.decide(user_id, proposal_id, StyleProposalStatus.REJECTED)
    if rejected is None:
        raise HTTPException(status_code=409, detail="Proposal is not pending")
    return rejected.to_dict()

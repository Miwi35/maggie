import json

from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo

# Declared on the schemas so the model picks from the enum instead of inventing a kind.
INSTRUCTION_KINDS = [kind.value for kind in InstructionKind]

# Instruction tools (always available — chat + proaction)
INSTRUCTION_TOOLS = [
    {
        "name": "add_instruction",
        "description": (
            "Store a standing directive: a rule from the owner that lasts beyond this reply. "
            "Two kinds, and the kind decides who reads it: 'planning' (a moment that comes back, hours not to "
            "disturb him) is read by her daily planning, which schedules the runs; 'behavior' (tone, register, "
            "length) is injected into every reply. "
            "'planning' is for when MAGGIE acts on her own; something the owner does that comes back is a "
            "task (create_task with rrule), not a directive. "
            "A way to carry out a kind of request is a skill (create_skill), a fact about him or the people "
            "around him a fiche (memory_write_fiche), one run at a set time schedule_proaction. "
            "A request for this reply only needs no directive. "
            "Before: list_instructions; a directive that says the same is not added twice."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "content": {
                    "type": "string",
                    "description": "The directive, in the user's own words",
                },
                "kind": {
                    "type": "string",
                    "enum": INSTRUCTION_KINDS,
                    "description": (
                        "'planning' for a scheduling/notification rule, "
                        "'behavior' for a preference about tone, style or length"
                    ),
                },
            },
            "required": ["content", "kind"],
        },
    },
    {
        "name": "list_instructions",
        "description": (
            "List the owner's standing directives (planning, behavior) with their ids. "
            "Read it before add_instruction, and to find the id delete_instruction needs."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "kind": {
                    "type": "string",
                    "enum": INSTRUCTION_KINDS,
                    "description": "Only directives of this kind; omit for all of them",
                },
            },
        },
    },
    {
        "name": "delete_instruction",
        "description": (
            "Delete a standing directive by its id (from list_instructions). "
            "It cancels no run already scheduled (cancel_proaction) and no skill (delete_skill)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "instruction_id": {
                    "type": "string",
                    "description": "The ID of the instruction to delete",
                },
            },
            "required": ["instruction_id"],
        },
    },
]


def _parse_instruction_kind(raw: object) -> InstructionKind | None:
    """The `kind` argument as an enum, or None when it is absent or unreadable.

    Case and surrounding space are forgiven — a model answering `"Behavior"` meant the
    right thing, and refusing it would only buy a wasted tool round trip. A kind that is
    not one of the two is still an error: see `handle_add_instruction`.
    """
    try:
        return InstructionKind(str(raw).strip().lower())
    except ValueError:
        return None


async def handle_add_instruction(arguments: dict, user_id: str) -> str:
    content = arguments.get("content", "")
    if not content:
        return json.dumps({"error": "'content' is required"})
    # The schema requires a kind, but nothing forces the model to honour it. Defaulting
    # a behaviour preference to 'planning' would store it where nobody reads it, so an
    # unreadable kind is an error the model can see and correct (MAG-22).
    kind = _parse_instruction_kind(arguments.get("kind"))
    if kind is None:
        return json.dumps({"error": f"'kind' must be one of {INSTRUCTION_KINDS}"})
    instruction = await instruction_repo.store(user_id, content, kind=kind)
    return json.dumps(instruction.to_dict())


async def handle_list_instructions(arguments: dict, user_id: str) -> str:
    raw_kind = arguments.get("kind")
    kind = _parse_instruction_kind(raw_kind) if raw_kind else None
    if raw_kind and kind is None:
        return json.dumps({"error": f"'kind' must be one of {INSTRUCTION_KINDS}"})
    instructions = await instruction_repo.find_by_user(user_id, kind=kind)
    return json.dumps([i.to_dict() for i in instructions])


async def handle_delete_instruction(arguments: dict, user_id: str) -> str:
    instruction_id = arguments.get("instruction_id", "")
    if not instruction_id:
        return json.dumps({"error": "'instruction_id' is required"})
    deleted = await instruction_repo.delete(user_id, instruction_id)
    if not deleted:
        return json.dumps({"error": f"Instruction '{instruction_id}' not found"})
    return json.dumps({"deleted": True, "id": instruction_id})

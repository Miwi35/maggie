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
            "Store a standing directive from the user. Two kinds, and the kind decides who reads it: "
            "'planning' tells Maggie WHEN to act autonomously (e.g. 'send me a day summary every morning "
            "at 9', 'don't bother me 9pm-9am') and is read during daily proaction planning; "
            "'behavior' tells her HOW to answer (e.g. 'tutoie-moi', 'fewer emojis', 'be brief') and is "
            "injected into the system prompt of every chat reply and proaction. "
            "Use this for a lasting preference or a rule that recurs — "
            "a one-off request for this reply needs no directive. "
            "'planning' is for when MAGGIE acts on her own; something the owner does that comes back is a "
            "task (create_task with rrule), not a directive."
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
        "description": "List the current user's standing directives, of one kind or of both.",
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
        "description": "Delete a standing directive by its ID.",
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

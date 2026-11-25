import json

from app.skills.index import LOCKED_SKILL_DETAIL, SkillLocked, skill_index

# Skill tools (always available — chat + proaction)
SKILL_TOOLS = [
    {
        "name": "create_skill",
        "description": (
            "Create a skill: a procedure Maggie follows for a kind of request (when asked X, do Y), in markdown, "
            "listed by name and description in the prompt and loaded with get_skill. "
            "A general rule about how she should act is a skill, even about reminders or notifications. "
            "Tone and wording are a 'behavior' directive (add_instruction), when she acts on her own a 'planning' "
            "one, one run at a set time schedule_proaction, knowledge about the owner or the people around him "
            "a fiche (memory_write_fiche). "
            "Before: list_skills; a skill that already covers it is changed with update_skill, never doubled. "
            "The owner sees and edits skills in the admin, Réglages \u203a Agent \u203a Compétences. "
            "Tag the moment it applies, from what he said: 'moment:chat' (when he asks something), "
            "'moment:proaction' (when a reminder or a proaction comes due), 'moment:planification' (the daily "
            "planning). A skill tagged with a moment is put in full in the prompt at that moment; one without "
            "stays in the index only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Unique skill name (kebab-case)",
                },
                "description": {
                    "type": "string",
                    "description": "Short description of what the skill does",
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": (
                        "Keywords describing the skill, plus the moment it applies "
                        "(e.g. ['rappel', 'notification', 'moment:proaction'])"
                    ),
                },
                "content": {
                    "type": "string",
                    "description": "The full skill procedure in markdown",
                },
            },
            "required": ["name", "description", "tags", "content"],
        },
    },
    {
        "name": "list_skills",
        "description": (
            "List the skills (name, description, tags). Read it before create_skill: "
            "a skill that already covers the request is updated, not created again."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "get_skill",
        "description": (
            "Charge une compétence de l'index « Compétences disponibles ». Quand sa description couvre la "
            "demande, charge-la au premier round, en même temps que tes premières lectures, "
            "et suis-la avant d'écrire."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The skill name to load",
                },
            },
            "required": ["name"],
        },
    },
    {
        "name": "update_skill",
        "description": (
            "Change a skill that exists (name from list_skills); read it with get_skill first, since content "
            "replaces the whole text. Tags replace the old ones: keep them, "
            "and add or fix the moment the skill applies ('moment:chat', 'moment:proaction', "
            "'moment:planification') when the user says when it should be applied."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The skill name to update",
                },
                "content": {
                    "type": "string",
                    "description": "New skill content (optional)",
                },
                "description": {
                    "type": "string",
                    "description": "New description (optional)",
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "New tags, moment tags included (optional; they replace the old ones)",
                },
            },
            "required": ["name"],
        },
    },
    {
        "name": "delete_skill",
        "description": (
            "Delete a skill by name (from list_skills). "
            "A directive goes with delete_instruction, a scheduled run with cancel_proaction."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The skill name to delete",
                },
            },
            "required": ["name"],
        },
    },
]


def _locked_skill_result(e: SkillLocked) -> str:
    return json.dumps({"error": "locked", "skill": e.name, "detail": LOCKED_SKILL_DETAIL}, ensure_ascii=False)


async def handle_create_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    description = arguments.get("description", "")
    tags = arguments.get("tags", [])
    content = arguments.get("content", "")
    if not name or not content:
        return json.dumps({"error": "'name' and 'content' are required"})
    if len(name) > 200:
        return json.dumps({"error": "'name' must be 200 characters or fewer"})
    if not isinstance(tags, list):
        return json.dumps({"error": "'tags' must be a list of strings"})
    try:
        entry = await skill_index.create(name, description, tags, content, user_id)
    except SkillLocked as e:
        return _locked_skill_result(e)
    return json.dumps({"name": entry.name, "description": entry.description, "tags": entry.tags})


async def handle_list_skills(arguments: dict, user_id: str) -> str:
    await skill_index.refresh()
    entries = skill_index.list_all()
    return json.dumps([{"name": e.name, "description": e.description, "tags": e.tags} for e in entries])


async def handle_get_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    if not name:
        return json.dumps({"error": "'name' is required"})
    content = await skill_index.get(name)
    if content is None:
        return json.dumps({"error": f"Skill '{name}' not found"})
    return json.dumps({"name": name, "content": content})


async def handle_update_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    if not name:
        return json.dumps({"error": "'name' is required"})
    if arguments.get("tags") is not None and not isinstance(arguments["tags"], list):
        return json.dumps({"error": "'tags' must be a list of strings"})
    try:
        entry = await skill_index.update(
            name,
            description=arguments.get("description"),
            tags=arguments.get("tags"),
            content=arguments.get("content"),
            user_id=user_id,
        )
    except SkillLocked as e:
        return _locked_skill_result(e)
    if entry is None:
        return json.dumps({"error": f"Skill '{name}' not found"})
    return json.dumps({"name": entry.name, "description": entry.description, "tags": entry.tags})


async def handle_delete_skill(arguments: dict, user_id: str) -> str:
    name = arguments.get("name", "")
    if not name:
        return json.dumps({"error": "'name' is required"})
    deleted = await skill_index.delete(name, user_id=user_id)
    if not deleted:
        return json.dumps({"error": f"Skill '{name}' not found"})
    return json.dumps({"deleted": True, "name": name})

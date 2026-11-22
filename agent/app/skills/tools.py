import json

from app.skills.index import LOCKED_SKILL_DETAIL, SkillLocked, skill_index

# Skill tools (always available — chat + proaction)
SKILL_TOOLS = [
    {
        "name": "create_skill",
        "description": (
            "Create a new skill file. Skills teach Maggie HOW to perform specific tasks "
            "(e.g. 'when adding a concert, search for the event webpage'). "
            "The owner's rule: 'when I ask you X, do Y' — a general request about how Maggie should act is a skill, "
            "even when it is about reminders or notifications (e.g. 'quand je demande un rappel, je veux une "
            "notification'). Tone and wording go to add_instruction kind='behavior', a fixed schedule to "
            "kind='planning', a one-off action later to schedule_proaction, knowledge about the user or the "
            "people around them to memory_write_fiche. "
            "Use update_skill when a skill already covers it. "
            "Skills are listed by name and description in the system prompt and loaded on demand with get_skill; "
            "the user sees and edits them in the admin, Réglages \u203a Agent \u203a Compétences. "
            "Tag the moment the skill applies, deduced from what the user said: 'moment:chat' (when they ask "
            "you something), 'moment:proaction' (when a reminder or a proaction comes due), "
            "'moment:planification' (the daily planning). A skill tagged with a moment is put in full in the "
            "prompt at that moment; one without stays in the index only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Unique skill name (kebab-case, e.g. 'concert-event-link')",
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
        "description": "List all available skills (name, description, tags).",
        "input_schema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "get_skill",
        "description": (
            "Charge une compétence AVANT d'exécuter une tâche qu'elle couvre "
            "(voir l'index des compétences du system prompt)."
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
            "Update an existing skill's content, description, or tags. Tags replace the old ones: keep them, "
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
        "description": "Delete a skill by name.",
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

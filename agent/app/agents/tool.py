from app.agents.delegate import handle_delegate
from app.agents.registry import subagent_registry
from app.runtime.tools.registry import ToolCallContext


def delegate_tool_definition() -> dict:
    """The `delegate` tool, built from the sub-agents loaded at startup: they are its enum and its description."""
    agents = subagent_registry.list_all()
    listing = "\n".join(f"- {a.name} : {a.description}" for a in agents)
    return {
        "name": "delegate",
        "description": (
            "Confie une tâche à un sous-agent spécialisé, qui l'exécute avec ses propres outils et te rend une "
            "synthèse. À utiliser quand la tâche demande beaucoup de lectures ou de recherches ; pas pour une "
            "action simple. Décris la tâche en entier : le sous-agent ne voit pas la conversation.\n"
            f"Agents disponibles :\n{listing}"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "agent": {
                    "type": "string",
                    "enum": [a.name for a in agents],
                    "description": "Le sous-agent à qui confier la tâche",
                },
                "task": {
                    "type": "string",
                    "description": "La tâche à exécuter, complète et autonome (période, sujets, forme de la synthèse)",
                },
            },
            "required": ["agent", "task"],
        },
    }


def delegate_offered() -> bool:
    return bool(subagent_registry.agents)


async def handle_delegate_tool(arguments: dict, ctx: ToolCallContext) -> str:
    return await handle_delegate(arguments, ctx.user_id)

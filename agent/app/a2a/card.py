from a2a.types import AgentCapabilities, AgentCard, AgentSkill, HTTPAuthSecurityScheme, SecurityScheme

from app.config import settings


def build_agent_card() -> AgentCard:
    """Build the public A2A agent card describing Maggie's capabilities."""
    skills = [
        AgentSkill(
            id="chat",
            name="Chat",
            description="Conversational AI assistant — answers questions, manages calendar, stores memories.",
            tags=["chat", "conversation", "calendar", "memory"],
            examples=["What's on my calendar today?", "Remember that I prefer tea over coffee."],
        ),
        AgentSkill(
            id="calendar",
            name="Calendar Management",
            description="Create, update, delete and query calendar events via MCP tools.",
            tags=["calendar", "events", "scheduling"],
            examples=["Create a meeting tomorrow at 3pm", "What events do I have this week?"],
        ),
        AgentSkill(
            id="proaction",
            name="Proactive Tasks",
            description="Autonomous scheduled tasks — Maggie plans and executes actions on your behalf.",
            tags=["proaction", "autonomous", "scheduled"],
            examples=["Check my calendar and remind me of upcoming events"],
        ),
    ]

    return AgentCard(
        name=settings.agent_name,
        description="Maggie — personal AI assistant with calendar, memory, and proactive capabilities.",
        url=settings.agent_base_url + "/a2a",
        version="0.1.0",
        default_input_modes=["text"],
        default_output_modes=["text"],
        capabilities=AgentCapabilities(streaming=False),
        security_schemes={"bearer": SecurityScheme(root=HTTPAuthSecurityScheme(scheme="bearer"))},
        security=[{"bearer": []}],
        skills=skills,
    )

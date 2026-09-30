import logging

import anthropic

from app.config import settings
from app.db.message_repository import message_repo
from app.llm.capabilities import generate_capability_summary
from app.llm.prompt_cache import build_system
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.personality.engine import PersonalityEngine, current_datetime_line
from app.skills.index import skill_index

logger = logging.getLogger(__name__)


class LLMGateway:
    """Claude API gateway with tool use support."""

    def __init__(self):
        self.client = (
            anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key) if settings.anthropic_api_key else None
        )
        self.personality = PersonalityEngine()
        self.tool_router = ToolRouter()
        self.agent_memory = AgentMemory()

    async def _build_system_prompt(
        self, user_id: str, tools: list[dict] | None = None, preamble: str = ""
    ) -> list[dict]:
        """Build the system blocks: cached prefix (personality + skill index), then memory, date and preamble."""
        capabilities = generate_capability_summary(tools) if tools else ""
        base = await self.personality.get_system_prompt(user_id, capabilities=capabilities)
        skill_context = skill_index.get_skills_index()
        memory_context = await self.agent_memory.get_memory_context(user_id)
        volatile = f"{memory_context}\n\n{current_datetime_line()}{preamble}"
        return build_system(base + skill_context, volatile)

    async def _load_conversation_history(self, user_id: str) -> list[dict]:
        """Load conversation history from the database."""
        try:
            messages = await message_repo.find_recent(user_id, limit=settings.max_conversation_history)

            # Convert to Anthropic message format, keeping only user/assistant roles
            anthropic_messages = []
            for msg in messages:
                if msg.role in ("user", "assistant") and msg.content:
                    anthropic_messages.append({"role": msg.role, "content": msg.content})

            # Merge consecutive messages with the same role (Anthropic requires alternating)
            merged = []
            for msg in anthropic_messages:
                if merged and merged[-1]["role"] == msg["role"]:
                    merged[-1]["content"] += "\n" + msg["content"]
                else:
                    merged.append(msg)

            logger.info(f"Loaded {len(merged)} messages from conversation history")
            return merged
        except Exception as e:
            logger.warning(f"Failed to load conversation history: {e}")
            return []

    async def proaction(self, prompt: str, user_id: str, *, silent: bool = False) -> dict:
        """Execute a proaction prompt without conversation memory.

        Native tools (schedule_proaction, list_proactions) are available here.

        Args:
            silent: If True, planning mode — output is an internal log, not sent to user.
                    If False, execution mode — output is a chat message for the user.
        """
        if self.client is None:
            return {
                "response": "AI service is not configured.",
                "tool_calls": [],
            }

        tools = await self.tool_router.get_tool_definitions(include_native=True)

        if silent:
            preamble = (
                "\n\nTu es en mode planification autonome. "
                "Planifie les proactions de la journée. "
                "Ton output est un log interne — il ne sera pas envoyé à l'utilisateur."
            )
        else:
            preamble = (
                "\n\nTu es en mode proaction. "
                "Exécute la tâche demandée et rédige un message clair pour l'utilisateur. "
                "Ton message sera envoyé directement dans le chat. "
                "Ne demande pas de confirmation avant d'agir — agis directement."
            )

        system_prompt = await self._build_system_prompt(user_id, tools=tools, preamble=preamble)

        messages = [{"role": "user", "content": prompt}]

        try:
            return await run_tool_loop(
                system_prompt,
                messages,
                tools,
                client=self.client,
                tool_router=self.tool_router,
                user_id=user_id,
                model=settings.anthropic_model,
                call_type="proaction",
                source="proaction",
            )
        except anthropic.APIStatusError as e:
            logger.error(f"Proaction API error: {e.message}")
            return {"response": f"AI service error: {e.message}", "tool_calls": []}
        except anthropic.APIConnectionError as e:
            logger.error(f"Proaction connection error: {e}")
            return {"response": "Unable to reach the AI service.", "tool_calls": []}

    async def chat(self, message: str, user_id: str, *, source: str = "chat") -> dict:
        """Process a chat message through Claude with MCP tool support."""
        if self.client is None:
            return {
                "response": (
                    "I'm sorry, the AI service is not configured."
                    " Please set the ANTHROPIC_API_KEY environment variable."
                ),
                "tool_calls": [],
            }

        # Load conversation history from database
        messages = await self._load_conversation_history(user_id)

        # Append current user message
        if messages and messages[-1]["role"] == "user":
            messages[-1]["content"] += "\n" + message
        else:
            messages.append({"role": "user", "content": message})

        # Get all tools including proaction tools (so user can schedule reminders from chat)
        tools = await self.tool_router.get_tool_definitions(include_native=True)

        try:
            system_prompt = await self._build_system_prompt(user_id, tools=tools)
            return await run_tool_loop(
                system_prompt,
                messages,
                tools,
                client=self.client,
                tool_router=self.tool_router,
                user_id=user_id,
                model=settings.anthropic_model,
                call_type="chat",
                source=source,
            )
        except anthropic.APIStatusError as e:
            logger.error(f"Anthropic API error: {e.message}")
            return {
                "response": f"AI service error: {e.message}",
                "tool_calls": [],
            }
        except anthropic.APIConnectionError as e:
            logger.error(f"Anthropic connection error: {e}")
            return {
                "response": "Unable to reach the AI service. Please try again later.",
                "tool_calls": [],
            }

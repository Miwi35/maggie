import logging

import anthropic

from app.config import settings
from app.db.message_repository import message_repo
from app.llm.capabilities import generate_capability_summary
from app.llm.client import create_llm_client, llm_configured
from app.llm.contexts import active_contexts_section, resolve_context
from app.llm.directives import behavior_directives_section
from app.llm.last_exchange import last_exchange_section
from app.llm.prompt_cache import build_system
from app.llm.runner import ITERATION_LIMIT_MESSAGE, run_tool_loop
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.personality.engine import PersonalityEngine, current_datetime_line
from app.skills.index import skill_index

logger = logging.getLogger(__name__)

PLANNING_PREAMBLE = (
    "\n\nTu es en mode planification autonome. "
    "Planifie les proactions de la journée. "
    "Ton output est un log interne — il ne sera pas envoyé à l'utilisateur."
)
EXECUTION_PREAMBLE = (
    "\n\nTu es en mode proaction. "
    "Exécute la tâche demandée et rédige un message clair pour l'utilisateur. "
    "Ton message sera envoyé directement dans le chat. "
    "Ne demande pas de confirmation avant d'agir — agis directement."
)


class LLMGateway:
    """Claude API gateway with tool use support."""

    def __init__(self):
        self.client = create_llm_client() if llm_configured() else None
        self.personality = PersonalityEngine()
        self.tool_router = ToolRouter()
        self.agent_memory = AgentMemory()

    async def _build_system_prompt(
        self,
        user_id: str,
        tools: list[dict] | None = None,
        preamble: str = "",
        *,
        exclude_message_id: str | None = None,
    ) -> list[dict]:
        """Build the system blocks: cached prefix (personality + skills), then memory, directives, date, preamble."""
        capabilities = generate_capability_summary(tools) if tools else ""
        base = await self.personality.get_system_prompt(user_id, capabilities=capabilities)
        skill_context = skill_index.get_skills_index()
        memory_context = await self.agent_memory.get_memory_context(user_id)
        # A proaction is a message the user reads in the chat, so it owes the same
        # preferences as a reply does — and in the volatile block, not the cached
        # prefix, so « tutoie-moi » applies to the very next message (MAG-22).
        directives = await behavior_directives_section(user_id)
        # And what the open threads are about. A proaction used to run with none of it:
        # the reminder arrived in a conversation it knew nothing of, so Maggie could
        # neither refer to what was already decided nor speak in the thread's terms
        # (MAG-14).
        context_section = await active_contexts_section(user_id)
        # How long since the chat last moved, so a proaction or an answer can greet by the gap (MAG-10).
        last_exchange = await last_exchange_section(user_id, exclude_message_id=exclude_message_id)
        now = current_datetime_line()
        volatile = f"{memory_context}{directives}{context_section}\n\n{now}{last_exchange}{preamble}"
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
        """Execute a proaction prompt, knowing what the open threads are about.

        Native tools (schedule_proaction, list_proactions) are available here.

        The result carries a `context_id` in execution mode: the thread the message is
        to be stored in, so the user's reply to a reminder stays in the same one
        (MAG-14). It is `None` when there is nothing to attach — a silent planning run,
        an empty answer, a run that gave up, a routing call that failed.

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

        preamble = PLANNING_PREAMBLE if silent else EXECUTION_PREAMBLE

        system_prompt = await self._build_system_prompt(user_id, tools=tools, preamble=preamble)

        messages = [{"role": "user", "content": prompt}]

        try:
            result = await run_tool_loop(
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

        if not silent:
            result["context_id"] = await self._resolve_proaction_context(result.get("response", ""), user_id)
        return result

    async def _resolve_proaction_context(self, response: str, user_id: str) -> str | None:
        """The thread the proaction's message belongs to — one already open, or a new one.

        A proaction is Maggie speaking first, so there is no user message to route: what
        the router reads is the message she is about to send. A planning run never gets
        here — its output is an internal log, and routing it would open a thread the user
        never sees.

        The two answers that are not a message get no thread either: nothing was said,
        and `run_tool_loop`'s giving-up sentence is an English apology that would open a
        thread labelled from it.
        """
        if not response.strip() or response == ITERATION_LIMIT_MESSAGE:
            return None

        resolution = await resolve_context(self.client, response, user_id)
        return resolution["id"] if resolution else None

    async def chat(
        self, message: str, user_id: str, *, source: str = "chat", exclude_message_id: str | None = None
    ) -> dict:
        """Process a chat message through Claude with MCP tool support.

        `exclude_message_id` is the user's message when the caller has already stored it.
        """
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

        # Get all tools including proaction tools (so user can schedule reminders from chat);
        # an A2A call only gets the read-only ones
        tools = await self.tool_router.get_tool_definitions(include_native=True, source=source)

        try:
            system_prompt = await self._build_system_prompt(user_id, tools=tools, exclude_message_id=exclude_message_id)
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

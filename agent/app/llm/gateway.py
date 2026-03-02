import logging
import time

import anthropic

from app.config import settings
from app.db.message_repository import message_repo
from app.llm.capabilities import generate_capability_summary
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.metrics import TOOL_CALLS, record_llm_usage
from app.personality.engine import PersonalityEngine
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

    async def _build_system_prompt(self, user_id: str, message: str = "", tools: list[dict] | None = None) -> str:
        """Build the full system prompt: personality + persistent memory context + skill context."""
        capabilities = generate_capability_summary(tools) if tools else ""
        base = await self.personality.get_system_prompt(user_id, capabilities=capabilities)
        memory_context = await self.agent_memory.get_memory_context(user_id)
        skill_context = skill_index.get_relevant_skills_context(message)
        return base + memory_context + skill_context

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

        system_prompt = await self._build_system_prompt(user_id, tools=tools) + preamble

        messages = [{"role": "user", "content": prompt}]
        tool_calls_made = []

        try:
            return await self._run_tool_loop(
                system_prompt, messages, tools, tool_calls_made, user_id=user_id, call_type="proaction"
            )
        except anthropic.APIStatusError as e:
            logger.error(f"Proaction API error: {e.message}")
            return {"response": f"AI service error: {e.message}", "tool_calls": []}
        except anthropic.APIConnectionError as e:
            logger.error(f"Proaction connection error: {e}")
            return {"response": "Unable to reach the AI service.", "tool_calls": []}

    async def chat(self, message: str, user_id: str) -> dict:
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

        # Call Claude
        tool_calls_made = []

        try:
            system_prompt = await self._build_system_prompt(user_id, message=message, tools=tools)
            result = await self._run_tool_loop(
                system_prompt, messages, tools, tool_calls_made, user_id=user_id, call_type="chat"
            )
            return result
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

    async def _run_tool_loop(
        self,
        system_prompt: str,
        messages: list,
        tools: list,
        tool_calls_made: list,
        max_iterations: int = 5,
        user_id: str | None = None,
        call_type: str = "chat",
    ) -> dict:
        model = settings.anthropic_model
        for _ in range(max_iterations):
            logger.info(f"Calling Claude with {len(tools)} tools, {len(messages)} messages")

            t0 = time.monotonic()
            try:
                response = await self.client.messages.create(
                    model=model,
                    max_tokens=4096,
                    system=system_prompt,
                    messages=messages,
                    tools=tools if tools else anthropic.NOT_GIVEN,
                )
                duration = time.monotonic() - t0
                record_llm_usage(
                    model=model,
                    call_type=call_type,
                    input_tokens=response.usage.input_tokens,
                    output_tokens=response.usage.output_tokens,
                    duration_seconds=duration,
                )
            except Exception:
                duration = time.monotonic() - t0
                record_llm_usage(
                    model=model,
                    call_type=call_type,
                    input_tokens=0,
                    output_tokens=0,
                    duration_seconds=duration,
                    status="error",
                )
                raise

            logger.info(f"Claude response: stop_reason={response.stop_reason}, blocks={len(response.content)}")

            if response.stop_reason == "tool_use":
                assistant_content = response.content
                messages.append({"role": "assistant", "content": assistant_content})

                tool_results = []
                for block in assistant_content:
                    if block.type == "tool_use":
                        logger.info(f"Tool call: {block.name}({block.input})")
                        TOOL_CALLS.labels(tool_name=block.name, source=call_type).inc()
                        result = await self.tool_router.call_tool(block.name, block.input, user_id=user_id)
                        tool_calls_made.append({"name": block.name, "input": block.input, "result": result})
                        tool_results.append(
                            {
                                "type": "tool_result",
                                "tool_use_id": block.id,
                                "content": result,
                            }
                        )

                messages.append({"role": "user", "content": tool_results})
            else:
                text_response = ""
                for block in response.content:
                    if hasattr(block, "text"):
                        text_response += block.text

                return {
                    "response": text_response,
                    "tool_calls": tool_calls_made,
                }

        return {
            "response": "I encountered an issue processing your request. Please try again.",
            "tool_calls": tool_calls_made,
        }

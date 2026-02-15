import logging

import anthropic

from app.config import settings
from app.llm.tools import ToolRouter
from app.memory.conversation import ConversationMemory
from app.personality.engine import PersonalityEngine

logger = logging.getLogger(__name__)


class LLMGateway:
    """Claude API gateway with tool use support."""

    def __init__(self):
        self.client = (
            anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key) if settings.anthropic_api_key else None
        )
        self.personality = PersonalityEngine()
        self.memory = ConversationMemory(max_messages=settings.max_conversation_history)
        self.tool_router = ToolRouter()

    async def proaction(self, prompt: str, user_id: str) -> dict:
        """Execute a proaction prompt without conversation memory."""
        if self.client is None:
            return {
                "response": "AI service is not configured.",
                "tool_calls": [],
            }

        system_prompt = self.personality.get_system_prompt() + (
            "\n\nTu es en mode autonome (proaction). "
            "Exécute la tâche demandée sans attendre de confirmation de l'utilisateur. "
            "Utilise les outils disponibles si nécessaire."
        )

        messages = [{"role": "user", "content": prompt}]
        tools = await self.tool_router.get_tool_definitions()
        tool_calls_made = []

        try:
            return await self._run_tool_loop(system_prompt, messages, tools, tool_calls_made)
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

        # Build conversation
        self.memory.add_message(user_id, "user", message)
        messages = self.memory.get_history(user_id)

        # Get available tools from MCP
        tools = await self.tool_router.get_tool_definitions()

        # Call Claude
        tool_calls_made = []

        try:
            result = await self._run_tool_loop(
                self.personality.get_system_prompt(), messages, tools, tool_calls_made
            )
            self.memory.add_message(user_id, "assistant", result["response"])
            return result
        except anthropic.APIStatusError as e:
            logger.error(f"Anthropic API error: {e.message}")
            self.memory.add_message(user_id, "assistant", "")
            return {
                "response": f"AI service error: {e.message}",
                "tool_calls": [],
            }
        except anthropic.APIConnectionError as e:
            logger.error(f"Anthropic connection error: {e}")
            self.memory.add_message(user_id, "assistant", "")
            return {
                "response": "Unable to reach the AI service. Please try again later.",
                "tool_calls": [],
            }

    async def _run_tool_loop(
        self, system_prompt: str, messages: list, tools: list, tool_calls_made: list, max_iterations: int = 5
    ) -> dict:
        for _ in range(max_iterations):
            logger.info(f"Calling Claude with {len(tools)} tools, {len(messages)} messages")
            response = await self.client.messages.create(
                model=settings.anthropic_model,
                max_tokens=4096,
                system=system_prompt,
                messages=messages,
                tools=tools if tools else anthropic.NOT_GIVEN,
            )
            logger.info(f"Claude response: stop_reason={response.stop_reason}, blocks={len(response.content)}")

            if response.stop_reason == "tool_use":
                assistant_content = response.content
                messages.append({"role": "assistant", "content": assistant_content})

                tool_results = []
                for block in assistant_content:
                    if block.type == "tool_use":
                        logger.info(f"Tool call: {block.name}({block.input})")
                        result = await self.tool_router.call_tool(block.name, block.input)
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

import logging

from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.utils import new_agent_text_message

from app.llm.gateway import LLMGateway

logger = logging.getLogger(__name__)

# Default user for A2A calls (agent-to-agent, no JWT context)
A2A_USER_ID = "a2a"


class MaggieAgentExecutor(AgentExecutor):
    """Bridges A2A protocol to LLMGateway.chat().

    A2A is strictly for agent-to-agent communication.
    The executor does NOT provide native proaction tools —
    it only gives Claude access to MCP tools.
    """

    def __init__(self):
        self.gateway = LLMGateway()

    async def execute(
        self,
        context: RequestContext,
        event_queue: EventQueue,
    ) -> None:
        # Extract user message from the A2A request
        message = self._extract_message(context)
        if not message:
            await event_queue.enqueue_event(
                new_agent_text_message("No message provided.")
            )
            return

        logger.info(f"A2A request: {message[:100]}")

        # Use chat() which only provides MCP tools (no native proaction tools)
        result = await self.gateway.chat(message, A2A_USER_ID)

        await event_queue.enqueue_event(
            new_agent_text_message(result["response"])
        )

    async def cancel(
        self,
        context: RequestContext,
        event_queue: EventQueue,
    ) -> None:
        raise Exception("cancel not supported")

    @staticmethod
    def _extract_message(context: RequestContext) -> str | None:
        """Extract the text message from the A2A request context."""
        if context.message and context.message.parts:
            for part in context.message.parts:
                if hasattr(part, "root") and hasattr(part.root, "text"):
                    return part.root.text
                if hasattr(part, "text"):
                    return part.text
        return None

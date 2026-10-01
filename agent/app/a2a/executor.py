import logging

from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.utils import new_agent_text_message

from app.llm.gateway import LLMGateway

logger = logging.getLogger(__name__)

# Fixed user for A2A calls: a peer carries the A2A bearer token, not a user JWT
A2A_USER_ID = "a2a"


class MaggieAgentExecutor(AgentExecutor):
    """Bridges A2A protocol to LLMGateway.chat().

    The route is behind the A2A bearer token (see app.a2a.auth). Through source="a2a",
    Claude only gets the read-only MCP tools of app.llm.tools.A2A_ALLOWED_TOOLS:
    no native tool (memory, skills, instructions, proactions) and no write tool.
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
            await event_queue.enqueue_event(new_agent_text_message("No message provided."))
            return

        logger.info(f"A2A request: {message[:100]}")

        result = await self.gateway.chat(message, A2A_USER_ID, source="a2a")

        await event_queue.enqueue_event(new_agent_text_message(result["response"]))

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

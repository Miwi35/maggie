import logging

import anthropic

from app.config import settings
from app.llm.capabilities import generate_capability_summary
from app.llm.client import create_llm_client, llm_configured
from app.llm.contexts import active_contexts_section, resolve_context, route_message
from app.llm.directives import behavior_directives_section
from app.llm.dry_run import DryRunToolRouter
from app.llm.history import build_history
from app.llm.last_exchange import last_exchange_section
from app.llm.prompt_cache import build_system
from app.llm.runner import ITERATION_LIMIT_MESSAGE, run_tool_loop
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.personality.engine import PersonalityEngine, current_datetime_line
from app.skills.index import skill_index

logger = logging.getLogger(__name__)

# What the user reads when the model could not be reached. French, and free of the API's
# own wording: since MAG-13 the answer is stored in the thread, so it is read back as part
# of the conversation — the same sentence the streamed path has always stored.
API_ERROR_MESSAGE = "Désolé, une erreur est survenue. Réessaie."
UNREACHABLE_MESSAGE = "Je n'arrive pas à joindre le service d'IA pour l'instant. Réessaie dans un moment."

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
        current_context_id: str | None = None,
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
        # (MAG-14). On the chat path the current thread is marked too, since the history is
        # made of its messages (MAG-13); a proaction has no thread yet and passes none.
        context_section = await active_contexts_section(user_id, current_context_id)
        # How long since the chat last moved, so a proaction or an answer can greet by the gap (MAG-10).
        last_exchange = await last_exchange_section(user_id, exclude_message_id=exclude_message_id)
        now = current_datetime_line()
        volatile = f"{memory_context}{directives}{context_section}\n\n{now}{last_exchange}{preamble}"
        return build_system(base + skill_context, volatile)

    async def proaction(self, prompt: str, user_id: str, *, silent: bool = False, dry_run: bool = False) -> dict:
        """Execute a proaction prompt, knowing what the open threads are about.

        Native tools (schedule_proaction, list_proactions) are available here.

        The result carries a `context_id` in execution mode: the thread the message is
        to be stored in, so the user's reply to a reminder stays in the same one
        (MAG-14). It is `None` when there is nothing to attach — a silent planning run,
        an empty answer, a run that gave up, a routing call that failed.

        Args:
            silent: If True, planning mode — output is an internal log, not sent to user.
                    If False, execution mode — output is a chat message for the user.
            dry_run: If True, nothing is written (MAG-249): tools that write are simulated and
                     listed under `simulated_tools`, and no thread is opened for the message.
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
        tool_router = DryRunToolRouter(self.tool_router) if dry_run else self.tool_router

        try:
            result = await run_tool_loop(
                system_prompt,
                messages,
                tools,
                client=self.client,
                tool_router=tool_router,
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

        if dry_run:
            result["dry_run"] = True
            result["simulated_tools"] = tool_router.simulated
        elif not silent:
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
        It decides two things at once: that the « last conversation » line must skip it
        (MAG-10), and that the history already holds it — so it is not appended a second
        time, which is how this path used to send the user's own message twice.

        The result carries a `context_id`, like `proaction()` does: the thread the message
        was routed into, so the caller stores the answer in the same one.
        """
        if self.client is None:
            return {
                "response": (
                    "I'm sorry, the AI service is not configured."
                    " Please set the ANTHROPIC_API_KEY environment variable."
                ),
                "tool_calls": [],
            }

        # The thread first: the history is its messages and the system prompt names it, so
        # neither can be built before it is known (MAG-13). `None` here — no answer from the
        # router — leaves the short global window as the whole conversation.
        #
        # Only for a caller that stored its message, which is what `exclude_message_id`
        # says. A thread is where a message and its answer live, and the A2A bridge stores
        # neither: routing there would buy a fast-model call per peer request and a context
        # row nobody ever writes in, kept awake by its own `touch()`.
        resolution = (
            await route_message(self.client, message, user_id, message_id=exclude_message_id)
            if exclude_message_id
            else None
        )
        context_id = resolution["id"] if resolution else None

        messages = await build_history(
            user_id,
            context_id=context_id,
            # An A2A call stores nothing, so its message only exists here. The chat routes
            # stored it, so for them it is the floor under a history that would not load.
            pending_message=None if exclude_message_id else message,
            fallback_message=message if exclude_message_id else None,
            current_message_id=exclude_message_id,
        )

        # Get all tools including proaction tools (so user can schedule reminders from chat);
        # an A2A call only gets the read-only ones
        tools = await self.tool_router.get_tool_definitions(include_native=True, source=source)

        try:
            system_prompt = await self._build_system_prompt(
                user_id,
                tools=tools,
                exclude_message_id=exclude_message_id,
                current_context_id=context_id,
            )
            result = await run_tool_loop(
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
            # The API's own wording stays in the log. It used to be the answer, and since
            # that answer is now stored in the thread it would be read back verbatim by
            # the next forty messages and by the summarizer — an English stack-trace-ish
            # sentence in the middle of a French conversation.
            logger.error(f"Anthropic API error: {e.message}")
            result = {"response": API_ERROR_MESSAGE, "tool_calls": [], "error": True}
        except anthropic.APIConnectionError as e:
            logger.error(f"Anthropic connection error: {e}")
            result = {"response": UNREACHABLE_MESSAGE, "tool_calls": [], "error": True}

        # Carried on the error paths too: the user's message is already tagged with the
        # thread, so leaving the answer out would keep an orphan question in it, and the
        # next summary would read half an exchange. What the user was shown is part of the
        # conversation whether or not the model produced it — the streamed path stores its
        # own error sentence in the thread for the same reason. `error` is what stops the
        # caller from spending a second model call summarizing a turn that failed.
        result["context_id"] = context_id
        return result

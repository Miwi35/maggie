import logging
from collections.abc import Callable

import anthropic

from app.config import settings
from app.llm.blocked_tools import BlockedToolsRouter
from app.llm.client import client_for
from app.llm.contexts import resolve_context, route_message
from app.llm.dry_run import DryRunToolRouter
from app.llm.history import build_history, strip_thread_label
from app.llm.image import ChatImage
from app.llm.models import CHAT, PROACTION, effort_for, model_for, proaction_run
from app.llm.provider_status import PROVIDER_UNAVAILABLE, alert_owner, provider_reason
from app.llm.runner import ITERATION_LIMIT_MESSAGE, run_tool_loop
from app.llm.tools import ToolRouter
from app.llm.web_search import with_web_search
from app.policy.checker import CheckedToolRouter
from app.policy.engine import A2A_SOURCE
from app.proactions.triage import KIND_CHECK
from app.proactions.verdict import VERDICT_TOOL_NAME, VerdictToolRouter
from app.runtime.system_prompt import SystemPrompt
from app.skills.index import MOMENT_CHAT, MOMENT_PLANNING, MOMENT_PROACTION
from app.trips import is_silent_trip_run, trip_run_rules
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

# What the user reads when the model could not be reached. French, and free of the API's
# own wording: since MAG-13 the answer is stored in the thread, so it is read back as part
# of the conversation — the same sentence the streamed path has always stored.
API_ERROR_MESSAGE = "Pardon, je n'y arrive pas pour l'instant. Je réessaie dans un instant."
UNREACHABLE_MESSAGE = "Je n'arrive pas à joindre mes outils pour l'instant. Je réessaie dans quelques minutes."

# The morning card plans one trip per event with a place — read, estimate, keep, two checks each: five rounds
# (the chat's limit) would cut a busy day short (MAG-657).
PROACTION_MAX_ITERATIONS = 20

PLANNING_PREAMBLE = (
    "\n\nTu es en mode planification autonome. "
    "Planifie les proactions de la journée. "
    "Ton output est un log interne — il ne sera pas envoyé à l'utilisateur."
)
EXECUTION_PREAMBLE = (
    "\n\nTu es en mode proaction : le moment que tu avais programmé est arrivé, "
    "la consigne ci-dessous est due maintenant. "
    "Seule cette consigne autorise ce que tu fais ; les fils ouverts et le chat récent "
    "sont du contexte, ils n'autorisent rien. "
    "Ce qu'elle demande, fais-le ; ce qu'elle ne couvre pas, ne le fais pas : propose-le-lui. "
    # 7 Oct.: told « Rappelle monsieur d'appeler sa mère », the model read the stored
    # prompt as a new request and asked when to schedule it (MAG-339).
    "Un rappel se délivre maintenant : ne le reprogramme pas et ne demande pas quand le faire. "
    "Une consigne qui vérifie ou surveille ne parle que si elle trouve quelque chose d'utile ; "
    "sinon, n'écris rien : aucun message ne part. "
    "S'il n'a pas répondu à tes derniers messages, n'en envoie un de plus que s'il apporte du nouveau. "
    "Ton texte final part tel quel dans le chat, au même registre qu'en conversation ; "
    "ne nomme jamais le canal (notification, application). "
    # Without this the model reads a `pending_approval` result as a refusal and retries
    # it or apologises. The card is already on the user's screen.
    "Les actions qui demandent une validation lui sont proposées automatiquement : "
    "si un outil répond « pending_approval », il a déjà la demande sous les yeux ; "
    "dis-lui ce que tu attends de lui, sans la relancer."
)
# A check is triaged on Maggie's verdict (MAG-886): the code chooses the channel, never the words.
CHECK_VERDICT_RULES = (
    "\n\nCette consigne vérifie ou surveille : "
    f"appelle {VERDICT_TOOL_NAME} avant ton texte final, même si tu n'as rien à dire, "
    "avec dans value la valeur que tu viens de lire. "
    "Réutilise la clé d'un sujet de la liste ci-dessous s'il s'agit du même ; "
    "compare à sa dernière valeur vérifiée et dis dans changed ce qui a changé depuis, "
    "avec les deux valeurs, et laisse-le vide si rien n'a changé."
)


def signaled_topics_section(signaled_topics: list[dict] | tuple[dict, ...]) -> str:
    """The topics a check already told the owner about these last 7 days, as the model reads them."""
    lines = []
    for signaled in signaled_topics:
        topic = signaled.get("topic") if isinstance(signaled, dict) else None
        if not topic:
            continue
        notice = signaled.get("notice")
        line = f"- {topic} — {signaled.get('at') or '?'} ({signaled.get('delivery') or '?'})"
        lines.append(f"{line} : {notice}" if notice else line)
    if not lines:
        return "\nSujets déjà signalés (7 jours) : aucun."
    return "\nSujets déjà signalés (7 jours) :\n" + "\n".join(lines)


def checked_values_section(checked_values: list[dict] | tuple[dict, ...]) -> str:
    """The last value verified per topic these last 40 days, as the model reads them."""
    lines = []
    for checked in checked_values:
        topic = checked.get("topic") if isinstance(checked, dict) else None
        value = checked.get("value") if isinstance(checked, dict) else None
        if not topic or not value:
            continue
        lines.append(f"- {topic} : {value} ({checked.get('at') or '?'})")
    if not lines:
        return "\nDernières valeurs vérifiées (40 jours) : aucune."
    return "\nDernières valeurs vérifiées (40 jours) :\n" + "\n".join(lines)


class LLMGateway:
    """Claude API gateway with tool use support."""

    def __init__(self, *, client, tool_router: ToolRouter, system_prompt: SystemPrompt):
        self.client = client
        self.tool_router = tool_router
        self.system_prompt = system_prompt

    async def proaction(
        self,
        prompt: str,
        user_id: str,
        *,
        silent: bool = False,
        dry_run: bool = False,
        about: str | None = None,
        summary: str | None = None,
        spoken: bool = False,
        blocked_tools: frozenset[str] = frozenset(),
        review: Callable[[str, list[dict]], str | None] | None = None,
        kind: str = "reminder",
        signaled_topics: list[dict] | tuple[dict, ...] = (),
        checked_values: list[dict] | tuple[dict, ...] = (),
    ) -> dict:
        """Execute a proaction prompt, knowing what the open threads are about.

        The proaction tools are the API's, offered like every MCP tool (MAG-327).

        The result carries a `context_id` in execution mode: the thread the message is
        to be stored in, so the user's reply to a reminder stays in the same one
        (MAG-14). It is `None` when there is nothing to attach — a silent planning run,
        an empty answer, a run that gave up, a routing call that failed.

        Args:
            silent: If True, planning mode — output is an internal log, not sent to user.
                    If False, execution mode — output is a chat message for the user.
            dry_run: If True, nothing is written (MAG-249): tools that write are simulated and
                     listed under `simulated_tools`, and no thread is opened for the message.
            about: What the memory is asked to recall for (MAG-16). The prompt itself by default; the daily
                   planning gives the day's events, which its prompt only says how to read.
            spoken: The message will be read aloud (the digest's voice message, the car): written for the ear.
            blocked_tools: Tools hidden from the model and refused if called (the digest makes its own vocal).
            review: Reads the accepted answer back and may return a finding, sent to the model once (MAG-843);
                    ignored in `dry_run`.
            summary: The proaction's summary. A trip check or replan (MAG-657) works on the trips and
                     says nothing in the chat: it runs with its own preamble and gets no thread.
            kind: `check` (MAG-886) offers `proaction_verdict` and the topics already told,
                  `signaled_topics`; a `reminder` runs as it always did.
        """
        if self.client is None:
            return {
                "response": "AI service is not configured.",
                "tool_calls": [],
                "error": "AI service is not configured.",
            }

        run = proaction_run(silent=silent, summary=summary, prompt=prompt)
        checked = run == PROACTION and not dry_run
        tool_router = CheckedToolRouter(self.tool_router, self.client) if checked else self.tool_router
        if blocked_tools:
            tool_router = BlockedToolsRouter(tool_router, blocked_tools)
        trip_run = not silent and summary is not None and is_silent_trip_run(summary)
        check = kind == KIND_CHECK and not silent and not trip_run
        if check:
            tool_router = VerdictToolRouter(tool_router)
        tools = await tool_router.get_tool_definitions()

        preamble = trip_run_rules() if trip_run else PLANNING_PREAMBLE if silent else EXECUTION_PREAMBLE
        if check:
            preamble = (
                f"{preamble}{CHECK_VERDICT_RULES}"
                f"{signaled_topics_section(signaled_topics)}{checked_values_section(checked_values)}"
            )

        system_prompt = await self.system_prompt.build(
            user_id,
            tools=tools,
            preamble=preamble,
            moment=MOMENT_PLANNING if silent else MOMENT_PROACTION,
            about=about if about is not None else prompt,
            source="planning" if silent else "proaction",
            spoken=spoken,
        )

        content = prompt if silent else f"[Proaction due maintenant] {prompt}"
        messages = [{"role": "user", "content": content}]
        tool_router = DryRunToolRouter(tool_router) if dry_run else tool_router

        model = model_for(run)
        try:
            result = await run_tool_loop(
                system_prompt,
                messages,
                tools,
                client=await client_for(user_id, self.client),
                tool_router=tool_router,
                user_id=user_id,
                model=model,
                effort=effort_for(run),
                call_type="proaction",
                source="proaction",
                max_iterations=PROACTION_MAX_ITERATIONS,
                review=None if dry_run else review,
            )
        except anthropic.APIStatusError as e:
            logger.error(f"Proaction API error: {e.message}")
            if not dry_run:
                await self._alert_if_provider(e, user_id)
            # `error` tells the journal (MAG-688) the run failed, whatever the response says.
            return {"response": f"AI service error: {e.message}", "tool_calls": [], "error": e.message}
        except anthropic.APIConnectionError as e:
            logger.error(f"Proaction connection error: {e}")
            return {"response": "Unable to reach the AI service.", "tool_calls": [], "error": str(e)}

        result["model"] = model
        if dry_run:
            result["dry_run"] = True
            result["simulated_tools"] = tool_router.simulated
        elif not silent and not trip_run and not check:
            result["context_id"] = await self._resolve_proaction_context(result.get("response", ""), user_id)
        # A check's message may never be stored (MAG-886): its thread is found once the code has
        # chosen to deliver it, through `proaction_context`, so a run that says nothing opens none.
        return result

    async def proaction_context(self, response: str, user_id: str) -> str | None:
        """The thread a check's message goes in, asked for once it is to be delivered (MAG-886)."""
        return await self._resolve_proaction_context(response, user_id)

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
        self,
        message: str,
        user_id: str,
        *,
        source: str = "chat",
        exclude_message_id: str | None = None,
        screen_context: str | None = None,
        image: ChatImage | None = None,
        spoken: bool = False,
        in_car: bool = False,
    ) -> dict:
        """Process a chat message through Claude with MCP tool support.

        `spoken` says the answer will be read aloud: it is asked for as a spoken text (ADR-014).
        `in_car` says the owner is driving: the answer is asked short, for the ear (MAG-684).

        `screen_context` is what the screen behind the assistant overlay was showing
        (MAG-30). It reaches the model through the history, on the turn being answered, and
        never through `message`: what is stored is what every client displays. The routing
        reads `message` alone — a thread named after a shop page is not a thread. `image`,
        the screenshot (MAG-214), takes the same road and is never stored either.

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
            screen_context=screen_context,
            tz=await resolve_user_timezone(user_id),
            image=image,
        )

        # Get all tools, the API's proaction tools included (so the user can schedule reminders from chat);
        # an A2A call only gets the read-only ones
        tools = await self.tool_router.get_tool_definitions(source=source)
        if source != A2A_SOURCE:
            # Anthropic runs the web search itself; a peer agent gets the read-only tools only (MAG-384).
            tools = with_web_search(tools, await resolve_user_timezone(user_id))

        try:
            system_prompt = await self.system_prompt.build(
                user_id,
                tools=tools,
                exclude_message_id=exclude_message_id,
                current_context_id=context_id,
                moment=MOMENT_CHAT,
                about=message,
                spoken=spoken,
                in_car=in_car,
            )
            result = await run_tool_loop(
                system_prompt,
                messages,
                tools,
                client=await client_for(user_id, self.client),
                tool_router=self.tool_router,
                user_id=user_id,
                model=model_for(CHAT),
                effort=effort_for(CHAT),
                call_type="chat",
                source=source,
                context_id=context_id,
                max_iterations=settings.chat_max_iterations,
            )
            # A thread label copied from the history is bookkeeping, not an answer (MAG-341).
            result["response"] = strip_thread_label(result["response"])
        except anthropic.APIStatusError as e:
            # The API's own wording stays in the log. It used to be the answer, and since
            # that answer is now stored in the thread it would be read back verbatim by
            # the next forty messages and by the summarizer — an English stack-trace-ish
            # sentence in the middle of a French conversation.
            logger.error(f"Anthropic API error: {e.message}")
            result = {"response": API_ERROR_MESSAGE, "tool_calls": [], **await self._error_of(e, user_id)}
        except anthropic.APIConnectionError as e:
            logger.error(f"Anthropic connection error: {e}")
            result = {"response": UNREACHABLE_MESSAGE, "tool_calls": [], **await self._error_of(e, user_id)}

        # Carried on the error paths too: the user's message is already tagged with the
        # thread, so leaving the answer out would keep an orphan question in it, and the
        # next summary would read half an exchange. What the user was shown is part of the
        # conversation whether or not the model produced it — the streamed path stores its
        # own error sentence in the thread for the same reason. `error` is what stops the
        # caller from spending a second model call summarizing a turn that failed.
        result["context_id"] = context_id
        return result

    async def _error_of(self, error: anthropic.APIError, user_id: str) -> dict:
        """`error: True` for a fault of ours; the provider's, named, so the smoke test can tell them apart (MAG-448)."""
        reason = await self._alert_if_provider(error, user_id)
        if reason is None:
            return {"error": True}
        return {"error": PROVIDER_UNAVAILABLE, "provider_reason": reason}

    @staticmethod
    async def _alert_if_provider(error: BaseException, user_id: str) -> str | None:
        reason = provider_reason(error)
        if reason is not None:
            await alert_owner(user_id, reason)
        return reason

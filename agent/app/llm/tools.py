import functools
import json
import logging

from app.agents.registry import subagent_registry
from app.bilan_consigne import BILAN_CONSIGNE_TOOL, handle_add_bilan_consigne
from app.coach_method import GET_COACH_METHOD_TOOL, handle_get_coach_method
from app.db.pending_action_repository import pending_action_repo
from app.instruction_tools import (
    INSTRUCTION_TOOLS,
    handle_add_instruction,
    handle_delete_instruction,
    handle_list_instructions,
)
from app.llm.message_reply import ANSWER_APPROVAL_TOOL, handle_answer_approval
from app.llm.questions import ASK_OWNER, ASK_OWNER_TOOL, handle_ask_owner
from app.llm.time_tool import DATE_TIME_TOOLS, handle_date_time
from app.llm.tool_failures import invalid_input
from app.mcp.client import mcp_client
from app.memory import conversations
from app.memory.tools import MEMORY_TOOL_NAMES, MEMORY_TOOLS
from app.memory.tools import NATIVE_HANDLERS as MEMORY_HANDLERS

# The policy module is where a `source` means something, so it owns the vocabulary.
from app.policy.engine import A2A_SOURCE, Mode, policy_engine
from app.policy.summary import build_summary
from app.redaction import redact
from app.skills.tools import (
    SKILL_TOOLS,
    handle_create_skill,
    handle_delete_skill,
    handle_get_skill,
    handle_list_skills,
    handle_update_skill,
)
from app.tts.voice_message import SEND_VOICE_MESSAGE_TOOL, handle_send_voice_message

logger = logging.getLogger(__name__)

# Voice message tool (always available — chat + proaction, MAG-685). Offered even when the bucket
# is off: the model then hears why it cannot send one, and says so, rather than never knowing the tool.
VOICE_TOOLS = [SEND_VOICE_MESSAGE_TOOL]

# Native tools that post into the thread the call was made in: they are handed its context id.
_THREAD_HANDLERS = {"send_voice_message": handle_send_voice_message}


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


# --- Handlers ---


async def _handle_delegate(arguments: dict, user_id: str) -> str:
    # Imported here: app.agents.delegate imports this module for ToolRouter.
    from app.agents.delegate import handle_delegate

    return await handle_delegate(arguments, user_id)


_NATIVE_HANDLERS = {
    "add_instruction": handle_add_instruction,
    "add_bilan_consigne": handle_add_bilan_consigne,
    "list_instructions": handle_list_instructions,
    "delete_instruction": handle_delete_instruction,
    "create_skill": handle_create_skill,
    "list_skills": handle_list_skills,
    "get_skill": handle_get_skill,
    "update_skill": handle_update_skill,
    "delete_skill": handle_delete_skill,
    "date_time": handle_date_time,
    "delegate": _handle_delegate,
    "get_coach_method": handle_get_coach_method,
    "answer_approval": handle_answer_approval,
    **MEMORY_HANDLERS,
}


# What the model reads back when the policy stops a call. Both are French: they end up
# in the conversation, the first as the tool's result, the second as what Maggie has to
# relay to the user.
POLICY_DENIED_MESSAGE = "Action interdite par la politique"
PENDING_APPROVAL_MESSAGE = (
    "En attente de validation par l'utilisateur. Ne réessaie pas ; explique-lui ce que tu t'apprêtes à faire."
)

# What an A2A peer may use: MCP tools that only read. No native tool (they write
# memory, skills, instructions and proactions) and no `manage_*` tool (one tool,
# read and write actions). A tool missing from this list is refused, so a new MCP
# tool stays out of A2A until someone decides it is read-only.
A2A_ALLOWED_TOOLS = frozenset(
    {
        "check_conflicts",
        "get_finance_projection",
        "get_events",
        "get_finance_summary",
        "get_grocery_list",
        "get_recipe",
        "get_tasks",
        "list_past_errands",
        "search",
        "search_ciqual_foods",
        "search_ingredients",
        "search_products",
        "search_recipes",
    }
)

# The API's tools for the proaction executor (MAG-326): claiming the due proactions and
# recording their results is the executor's job, never a conversation's — offered to the
# model, a claim would leave the user's proactions running forever.
# The daily rendezvous' settings of every user are the planner's, laid down by code (MAG-787).
EXECUTOR_ONLY_MCP_TOOLS = frozenset({"claim_due_proactions", "record_proaction_result", "list_daily_rendezvous"})


def _native_tools() -> list[dict]:
    return [
        *MEMORY_TOOLS,
        *INSTRUCTION_TOOLS,
        BILAN_CONSIGNE_TOOL,
        *SKILL_TOOLS,
        *DATE_TIME_TOOLS,
        GET_COACH_METHOD_TOOL,
        ANSWER_APPROVAL_TOOL,
        ASK_OWNER_TOOL,
        *VOICE_TOOLS,
    ]


@functools.cache
def _native_schemas() -> dict[str, dict]:
    return {tool["name"]: tool.get("input_schema") or {} for tool in _native_tools()}


def _missing_native_fields(name: str, arguments: dict) -> list[str]:
    schema = _native_schemas().get(name)
    if schema is None:
        return []
    return [field for field in schema.get("required", []) if field not in arguments]


def _native_tool_names() -> frozenset[str]:
    """Every tool the agent serves itself: an MCP tool by the same name is not offered.

    The Anthropic API refuses a request that names a tool twice, and `call_tool` routes a
    native name to the agent's own handler anyway. Proactions are not among them: the API
    serves them (MAG-327).
    """
    native = _native_tools()
    return frozenset({tool["name"] for tool in native} | {"delegate"})


class ToolRouter:
    """Routes tool calls to native handlers or MCP server.

    Tool categories:
    - Memory tools — always available (chat + proaction)
    - Instruction tools — always available (chat + proaction)
    - Skill tools — always available (chat + proaction)
    - Date and time tool — always available (chat + proaction)
    - Voice message tool — always available (chat + proaction)
    - `delegate` — only when sub-agents are loaded (never over A2A)
    - MCP tools — fetched from the Symfony MCP server, proactions included (MAG-327)

    Calls from the `a2a` source are narrowed to A2A_ALLOWED_TOOLS.

    Every call goes through the tool policy first (`app.policy.engine`): this is the one
    place a tool call passes, native or MCP, so it is the one place the guard belongs.
    """

    async def get_tool_definitions(self, source: str | None = None) -> list[dict]:
        """Get available tools in Anthropic tool format.

        Args:
            source: Who the tools are for. `a2a` gets the read-only MCP tools and nothing else.
        """
        a2a = source == A2A_SOURCE
        tools = [] if a2a else _native_tools()

        if not a2a and subagent_registry.agents:
            tools.append(delegate_tool_definition())

        native = _native_tool_names()
        mcp_tools = await mcp_client.list_tools()
        for tool in mcp_tools:
            if a2a and tool["name"] not in A2A_ALLOWED_TOOLS:
                continue
            if tool["name"] in native or tool["name"] in EXECUTOR_ONLY_MCP_TOOLS:
                continue
            tools.append(
                {
                    "name": tool["name"],
                    "description": tool.get("description", ""),
                    "input_schema": tool.get("inputSchema", {"type": "object", "properties": {}}),
                }
            )

        return tools

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
    ) -> str:
        """The only point every tool result goes through: it leaves masked (MAG-871),
        toward the model, the stored and replayed blocks, `tool_calls` and the stream."""
        return redact(await self._call_tool(name, arguments, user_id, source, context_id))

    async def _call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
    ) -> str:
        """Route a tool call to native handler or MCP server, unless the policy says otherwise.

        `source` says who triggered the call: chat, chat_stream, proaction, subagent:<name>, a2a or approval.
        `context_id` is the thread the call was made in, carried onto a held action so the
        announcement of its result lands in the same conversation.
        """
        if source == A2A_SOURCE and name not in A2A_ALLOWED_TOOLS:
            logger.warning(f"A2A call to a tool outside the read-only list refused: {name}")
            return json.dumps({"error": f"Tool '{name}' is not available over A2A"})

        if name in EXECUTOR_ONLY_MCP_TOOLS:
            logger.warning(f"Call to an executor-only tool refused: {name}")
            return json.dumps({"error": f"Tool '{name}' is not available"})

        if missing := _missing_native_fields(name, arguments):
            schema = _native_schemas()[name]
            return invalid_input(name, [{"field": f, "problem": "missing"} for f in missing], schema)

        held = await self._apply_policy(name, arguments, user_id, source, context_id)
        if held is not None:
            return held

        if name == ASK_OWNER:
            if user_id is None:
                return json.dumps({"error": "user_id required for native tools"})
            return await handle_ask_owner(arguments, user_id, source)

        if name in _THREAD_HANDLERS:
            if user_id is None:
                return json.dumps({"error": "user_id required for native tools"})
            try:
                return await _THREAD_HANDLERS[name](arguments, user_id, context_id)
            except Exception as e:
                logger.error(f"Native tool call failed: {name}: {e}")
                return json.dumps({"error": f"Tool call failed: {e}"})

        if name in _NATIVE_HANDLERS:
            if user_id is None:
                return json.dumps({"error": "user_id required for native tools"})
            if name in MEMORY_TOOL_NAMES:
                # What was just said is on file before a fiche cites it, whatever the router managed to tag.
                await conversations.record_thread(user_id, context_id)
            try:
                return await _NATIVE_HANDLERS[name](arguments, user_id)
            except Exception as e:
                logger.error(f"Native tool call failed: {name}({arguments}): {e}")
                return json.dumps({"error": f"Tool call failed: {e}"})

        # Fall through to MCP
        try:
            result = await mcp_client.call_tool(name, arguments, user_id=user_id)
            return result
        except Exception as e:
            logger.error(f"Tool call failed: {name}({arguments}): {e}")
            return f'{{"error": "Tool call failed: {e}"}}'

    async def _apply_policy(
        self,
        name: str,
        arguments: dict,
        user_id: str | None,
        source: str,
        context_id: str | None,
    ) -> str | None:
        """What to answer instead of running the call, or None to run it.

        `deny` is a plain error. `ask` persists the call with its arguments frozen and
        tells the model to stop and explain itself — the user answers from the web or the
        phone, and the approval endpoint replays those arguments (MAG-5).
        """
        mode = policy_engine.evaluate(name, arguments, source)
        if mode is Mode.ALLOW:
            return None

        if mode is Mode.DENY:
            logger.warning(f"Tool call refused by the policy: {name} (source={source})")
            return json.dumps({"error": POLICY_DENIED_MESSAGE})

        if user_id is None:
            # Nobody to ask. Running it anyway would be the one thing the policy exists to stop.
            logger.error(f"Tool call needing approval has no user to ask: {name}")
            return json.dumps({"error": "user_id required to request approval"})

        try:
            summary = await build_summary(name, arguments or {}, user_id)
        except Exception as e:
            # The card is still worth showing without its sentence: it falls back to the label.
            logger.warning(f"Could not build the summary of {name}: {e}")
            summary = None

        try:
            action = await pending_action_repo.create(
                user_id, name, arguments or {}, source=source, context_id=context_id, summary=summary
            )
        except Exception as e:
            # The action could not be stored, so nothing can approve it later: refusing
            # is the only answer that does not act behind the user's back.
            logger.error(f"Could not hold {name} for approval: {e}")
            return json.dumps({"error": "L'action n'a pas pu être soumise à validation"})

        return json.dumps(
            {
                "status": "pending_approval",
                "approval_id": str(action.id),
                "message": PENDING_APPROVAL_MESSAGE,
            }
        )

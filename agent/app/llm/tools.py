import json
import logging

from app.db.pending_action_repository import pending_action_repo
from app.llm.tool_failures import invalid_input
from app.mcp.client import mcp_client

# The policy module is where a `source` means something, so it owns the vocabulary.
from app.policy.engine import A2A_SOURCE, Mode, policy_engine
from app.policy.origin import hide_origin, stamp_origin
from app.policy.summary import build_summary
from app.redaction import redact
from app.runtime.tools.registry import NativeToolRegistry, ToolCallContext

logger = logging.getLogger(__name__)

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


class ToolRouter:
    """Routes tool calls to native handlers or MCP server.

    Tool categories:
    - Native tools — registered by the assembly (`app.native_tools`) in a `NativeToolRegistry`,
      always available (chat + proaction), `delegate` only when sub-agents are loaded (never over A2A)
    - MCP tools — fetched from the Symfony MCP server, proactions included (MAG-327)

    Calls from the `a2a` source are narrowed to A2A_ALLOWED_TOOLS.

    Every call goes through the tool policy first (`app.policy.engine`): this is the one
    place a tool call passes, native or MCP, so it is the one place the guard belongs.
    """

    def __init__(self, natives: NativeToolRegistry):
        self._natives = natives

    async def get_tool_definitions(self, source: str | None = None) -> list[dict]:
        """Get available tools in Anthropic tool format.

        Args:
            source: Who the tools are for. `a2a` gets the read-only MCP tools and nothing else.
        """
        a2a = source == A2A_SOURCE
        tools = [] if a2a else self._natives.definitions()

        # The Anthropic API refuses a request that names a tool twice, and a native name is
        # routed to the agent's own handler anyway: an MCP tool by the same name is not offered.
        native = self._natives.names()
        mcp_tools = await mcp_client.list_tools()
        for tool in mcp_tools:
            schema = tool.get("inputSchema", {"type": "object", "properties": {}})
            if a2a and tool["name"] not in A2A_ALLOWED_TOOLS:
                continue
            if tool["name"] in native or tool["name"] in EXECUTOR_ONLY_MCP_TOOLS:
                continue
            tools.append(
                {
                    "name": tool["name"],
                    "description": tool.get("description", ""),
                    # The origin of a proaction is the code's to set, never the model's (MAG-977).
                    "input_schema": hide_origin(tool["name"], schema),
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
        *,
        origin: dict | None = None,
    ) -> str:
        """The only point every tool result goes through: it leaves masked (MAG-871),
        toward the model, the stored and replayed blocks, `tool_calls` and the stream.

        `origin` is the owner's message the turn answers, set by `OriginToolRouter` alone (MAG-977)."""
        return redact(await self._call_tool(name, arguments, user_id, source, context_id, origin=origin))

    async def _call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
        *,
        origin: dict | None = None,
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

        # Before the policy: a held action is stored without the origin the model wrote.
        arguments = stamp_origin(name, arguments, origin)

        if missing := [f for f in self._natives.required_fields(name) if f not in arguments]:
            schema = self._natives.get(name).current_definition().get("input_schema") or {}
            return invalid_input(name, [{"field": f, "problem": "missing"} for f in missing], schema)

        held = await self._apply_policy(name, arguments, user_id, source, context_id)
        if held is not None:
            return held

        if (tool := self._natives.get(name)) is not None:
            if user_id is None:
                return json.dumps({"error": "user_id required for native tools"})
            try:
                return await tool.handler(arguments, ToolCallContext(user_id, source, context_id))
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

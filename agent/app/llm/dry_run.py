import json

from app.llm.tools import A2A_ALLOWED_TOOLS, ToolRouter

# Tools that only read. Everything else is simulated: a tool missing here is refused until someone
# decides it is read-only, the same rule as A2A_ALLOWED_TOOLS (which is where the MCP reads come from).
# `get_grocery_list` is left out: it creates the user's list when there is none (a fresh Recette account).
READ_ONLY_TOOLS = (A2A_ALLOWED_TOOLS - {"get_grocery_list"}) | {
    "get_user_timezone",
    "list_instructions",
    "list_proactions",
    "list_skills",
    "get_skill",
    "search_memory",
}
# One tool, read and write actions: only these actions read.
READ_ONLY_ACTIONS = {"list", "get"}
READ_ONLY_ACTIONS_BY_TOOL = {"monthly_review": {"review"}}

SIMULATED_RESULT = {
    "dry_run": True,
    "simulated": True,
    "message": "Simulation : cet outil écrit, il n'a pas été exécuté.",
}


def is_read_only(name: str, arguments: dict) -> bool:
    if name in READ_ONLY_TOOLS:
        return True
    actions = READ_ONLY_ACTIONS_BY_TOOL.get(name, READ_ONLY_ACTIONS if name.startswith("manage_") else set())
    action = arguments.get("action")
    return isinstance(action, str) and action in actions


class DryRunToolRouter:
    """A ToolRouter that reads for real and writes nowhere (MAG-249).

    The model sees what it would see, so what it plans and what it says is what it would plan and say; a
    tool that writes answers « simulated » instead of running, and is kept in `simulated` for the caller.
    """

    def __init__(self, router: ToolRouter):
        self._router = router
        self.simulated: list[dict] = []

    async def get_tool_definitions(self, *args, **kwargs) -> list[dict]:
        return await self._router.get_tool_definitions(*args, **kwargs)

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
    ) -> str:
        if is_read_only(name, arguments):
            return await self._router.call_tool(name, arguments, user_id=user_id, source=source, context_id=context_id)

        self.simulated.append({"name": name, "input": arguments})
        return json.dumps(SIMULATED_RESULT)

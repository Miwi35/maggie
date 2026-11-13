import json

from app.llm.tools import ToolRouter

BLOCKED_RESULT = {"error": "Cet outil n'est pas disponible pendant cette exécution : réponds par le texte seul."}


class BlockedToolsRouter:
    """A ToolRouter that hides some tools and refuses them if the model calls them anyway (MAG-807).

    The digest's vocal is the code's: an old skill copy that still says « send_voice_message » must not
    make a second message.
    """

    def __init__(self, router: ToolRouter, blocked: frozenset[str]):
        self._router = router
        self._blocked = blocked

    async def get_tool_definitions(self, *args, **kwargs) -> list[dict]:
        definitions = await self._router.get_tool_definitions(*args, **kwargs)
        return [d for d in definitions if d.get("name") not in self._blocked]

    async def call_tool(self, name: str, arguments: dict, **kwargs) -> str:
        if name in self._blocked:
            return json.dumps(BLOCKED_RESULT)
        return await self._router.call_tool(name, arguments, **kwargs)

    def __getattr__(self, attr):
        return getattr(self._router, attr)

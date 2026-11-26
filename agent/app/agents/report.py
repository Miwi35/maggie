import json

from app.llm.tools import ToolRouter

REPORT_TOOL = "report_to_maggie"

_STRINGS = {"type": "array", "items": {"type": "string"}}


def report_tool_definition() -> dict:
    """The tool a sub-agent ends with: its structured return to Maggie (ADR-013)."""
    return {
        "name": REPORT_TOOL,
        "description": (
            "Ton retour à Maggie, une fois, à la fin. Tu n'écris rien : ce que tu ferais va dans proposals."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "result": {"type": "string", "description": "Ta synthèse : les faits, chiffrés"},
                "sources": {**_STRINGS, "description": "D'où viennent les faits (outils, fiches, pages)"},
                "missing": {**_STRINGS, "description": "Ce que tu n'as pas pu trouver ou vérifier"},
                "questions": {**_STRINGS, "description": "Ce que Maggie doit demander au propriétaire"},
                "proposals": {
                    "type": "array",
                    "description": "Ce que Maggie pourrait écrire, avec ses outils",
                    "items": {
                        "type": "object",
                        "properties": {
                            "what": {"type": "string", "description": "L'action, par ex. « manage_scenarios suggest »"},
                            "target": {"type": "string", "description": "Ce qui est visé"},
                            "values": {"type": "object", "description": "Les valeurs à écrire"},
                            "why": {"type": "string", "description": "Pourquoi"},
                        },
                        "required": ["what", "target", "values", "why"],
                    },
                },
            },
            "required": ["result"],
        },
    }


class ReportingToolRouter:
    """Keeps the sub-agent's `report_to_maggie` call, and passes every other call to the router."""

    def __init__(self, router: ToolRouter):
        self._router = router
        self.report: dict | None = None

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        user_id: str | None = None,
        source: str = "chat",
        context_id: str | None = None,
    ) -> str:
        if name != REPORT_TOOL:
            return await self._router.call_tool(name, arguments, user_id=user_id, source=source, context_id=context_id)
        # Deliberately outside the policy: this tool is the sub-agent's return, it writes nothing (ADR-013).
        self.report = arguments
        return json.dumps({"ok": True, "message": "Retour reçu : termine sans rien ajouter."}, ensure_ascii=False)


def specialist_report(name: str, response: str, report: dict | None, tool_calls: list[str], complete: bool) -> dict:
    """What `delegate` hands Maggie. Without a report, the specialist's own text is the result."""
    report = report or {}
    return {
        "agent": name,
        "complete": complete,
        "result": report.get("result") or response,
        "sources": report.get("sources") or [],
        "missing": report.get("missing") or [],
        "questions": report.get("questions") or [],
        "proposals": report.get("proposals") or [],
        "tool_calls": tool_calls,
    }

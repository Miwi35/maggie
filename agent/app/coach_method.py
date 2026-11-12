"""The get_coach_method tool: the coach's method, read one section at a time (MAG-778).

The text lives in `data/coach/method.md`, a copy of the Linear document « Coach sportif · méthode »: one `##`
section per key below, in this order. A file rather than a skill: `get_skill` loads a whole skill, and skills
are listed in Maggie's index and editable in the admin.
"""

import json
import re
from pathlib import Path

METHOD_FILE = Path(__file__).resolve().parents[1] / "data" / "coach" / "method.md"
SECTIONS = ["principes", "semaine", "echelles", "progression", "seance", "degradees", "calibrage", "sources"]
_SECTION_HEADING = re.compile(r"^## ", re.MULTILINE)

GET_COACH_METHOD_TOOL = {
    "name": "get_coach_method",
    "description": (
        "Lit la méthode du coach sportif, une section à la fois. section : principes · semaine · echelles · "
        "progression · seance · degradees · calibrage · sources. Sans section : la table des sections."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "section": {"type": "string", "enum": SECTIONS, "description": "La section à lire (facultatif)"},
        },
    },
}


def _split(text: str) -> tuple[str, list[str]]:
    head, *sections = _SECTION_HEADING.split(text)
    return head.strip(), ["## " + section.strip() for section in sections]


async def handle_get_coach_method(arguments: dict, user_id: str) -> str:
    head, bodies = _split(METHOD_FILE.read_text(encoding="utf-8"))
    by_key = dict(zip(SECTIONS, bodies, strict=True))
    section = arguments.get("section")
    if not section:
        table = [{"section": key, "title": body.splitlines()[0].removeprefix("## ")} for key, body in by_key.items()]
        return json.dumps({"intro": head, "sections": table}, ensure_ascii=False)
    if section not in by_key:
        return json.dumps({"error": f"Unknown section '{section}'", "sections": SECTIONS}, ensure_ascii=False)
    return json.dumps({"section": section, "text": by_key[section]}, ensure_ascii=False)

"""The tools Maggie has on her memory folder: search, open, write a fiche.

All three go through `MemoryService`, never the index directly. There is no tool to write a conversation
(the code writes each exchange as it happens, `conversations.py`) and none to delete a fiche (what stops
being true is marked replaced and dated, `fiches.py`): the owner deletes in the folder, with the files.
"""

import json
import logging
from datetime import datetime
from typing import Any

from app.db.memory_index_repository import IndexedFile
from app.memory import carte as carte_mod
from app.memory import fiches, paths
from app.memory.service import memory_service
from app.user_timezone import resolve_user_timezone

logger = logging.getLogger(__name__)

SEARCH_LIMIT = 8
MAX_SEARCH_LIMIT = 20
EXCERPT = 240
MAX_OPEN_CHARS = 20000

_PROVENANCE_HELP = (
    "`dit` = the owner said it (source: the conversation, with the minute); `observé` = seen in the app's data "
    "(source: `app : <la donnée>`); `déduit` = you inferred it (always announced as such when used)."
)

MEMORY_TOOLS = [
    {
        "name": "memory_search",
        "description": (
            "Search the memory folder: fiches (what is known) and conversations (what was said, whole). Returns "
            "the path, the summary and an excerpt of each match — an excerpt is not the fiche: open it with "
            "memory_open before stating anything from it."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "requete": {"type": "string", "description": "Words to look for (a name, a subject, a phrase)"},
                "limite": {
                    "type": "integer",
                    "description": f"How many results (default {SEARCH_LIMIT}, max {MAX_SEARCH_LIMIT})",
                },
            },
            "required": ["requete"],
        },
    },
    {
        "name": "memory_open",
        "description": (
            "Open a file of the memory folder, whole or a passage. A fiche comes back with the claims it holds "
            "you must announce as deduced (`a_annoncer`). Always open the fiche before answering what you know, "
            "and open the conversation behind a claim when asked where it comes from."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "chemin": {"type": "string", "description": "Path from memory_search or memory_write_fiche, as given"},
                "debut": {"type": "integer", "description": "Optional: first line of the passage (1 = first line)"},
                "fin": {"type": "integer", "description": "Optional: last line of the passage"},
            },
            "required": ["chemin"],
        },
    },
    {
        "name": "memory_write_fiche",
        "description": (
            "Write or rewrite a fiche: durable knowledge about a person, a story, a habit, a place. The title is a "
            "TRUE SENTENCE (« Françoise n'aime pas les roses »), not a label. Folders are yours to choose, few and "
            "shallow. Every claim carries its provenance and its source. " + _PROVENANCE_HELP + " To correct a fiche "
            "that exists, open it, then rewrite it with `chemin`: send every claim that stays true in "
            "`affirmations`, and move what stopped being true to `remplacees` with the reason — it is dated and "
            "kept, never erased. Never write as `dit` or `observé` what you added yourself. The owner's conversation "
            "is already on file whole: this is for what is worth knowing again later."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "titre": {"type": "string", "description": "A true sentence, e.g. « Françoise n'aime pas les roses »"},
                "dossiers": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Folders, outermost first, at most 3 (new fiche only)",
                },
                "chemin": {"type": "string", "description": "Path of the fiche to rewrite (from memory_search)"},
                "resume": {"type": "string", "description": "A few words on what the fiche is about"},
                "affirmations": {
                    "type": "array",
                    "description": "Everything the fiche says as true now",
                    "items": {
                        "type": "object",
                        "properties": {
                            "texte": {"type": "string", "description": "One short sentence"},
                            "provenance": {"type": "string", "enum": list(carte_mod.PROVENANCES)},
                            "source": {
                                "type": "string",
                                "description": "Conversation or fiche path it comes from, or `app : <la donnée>`",
                            },
                            "heure": {
                                "type": "string",
                                "description": "Conversation source: message minute `AAAA-MM-JJ HHhMM`, as in the file",
                            },
                        },
                        "required": ["texte", "provenance", "source"],
                    },
                },
                "remplacees": {
                    "type": "array",
                    "description": "Claims of the existing fiche that are no longer true",
                    "items": {
                        "type": "object",
                        "properties": {
                            "texte": {"type": "string", "description": "As written in the fiche"},
                            "motif": {"type": "string", "description": "What replaced it, or why it stopped"},
                        },
                        "required": ["texte"],
                    },
                },
                "personnes": {"type": "array", "items": {"type": "string"}, "description": "People involved"},
                "evoque": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Situations or themes in which this fiche should come back",
                },
                "alias": {"type": "array", "items": {"type": "string"}, "description": "Other names for the subject"},
            },
            "required": ["titre", "affirmations"],
        },
    },
]

MEMORY_TOOL_NAMES = frozenset(tool["name"] for tool in MEMORY_TOOLS)


def _dump(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False)


def _unavailable() -> str | None:
    if memory_service.enabled:
        return None
    return _dump({"error": "The memory folder is not available right now: say so, do not claim to remember."})


def _excerpt(text: str, query: str) -> str:
    flat = " ".join(text.split())
    lowered = flat.casefold()
    for word in [query.casefold(), *query.casefold().split()]:
        at = lowered.find(word) if word else -1
        if at >= 0:
            start = max(0, at - EXCERPT // 3)
            return ("…" if start else "") + flat[start : start + EXCERPT] + ("…" if start + EXCERPT < len(flat) else "")
    return flat[:EXCERPT] + ("…" if len(flat) > EXCERPT else "")


def _hit(found: IndexedFile, query: str) -> dict[str, Any]:
    return {
        "chemin": found.path,
        "genre": found.kind,
        "titre": found.title,
        "resume": found.resume,
        "extrait": _excerpt(found.body, query),
    }


async def handle_search(arguments: dict, user_id: str) -> str:
    if (down := _unavailable()) is not None:
        return down
    query = str(arguments.get("requete") or "").strip()
    if not query:
        return _dump({"error": "'requete' is required"})
    try:
        limit = min(max(int(arguments.get("limite") or SEARCH_LIMIT), 1), MAX_SEARCH_LIMIT)
    except (TypeError, ValueError):
        limit = SEARCH_LIMIT
    found = await memory_service.search(user_id, query, limit)
    return _dump(
        {
            "resultats": [_hit(f, query) for f in found],
            "rappel": "Un extrait n'est pas la fiche : ouvre-la (memory_open) avant d'affirmer quoi que ce soit.",
        }
    )


def _passage(text: str, debut: Any, fin: Any) -> tuple[str, dict[str, Any]]:
    lines = text.splitlines()
    info: dict[str, Any] = {"lignes": len(lines)}
    try:
        first = int(debut) if debut is not None else None
        last = int(fin) if fin is not None else None
    except (TypeError, ValueError):
        first = last = None
    if first is None and last is None:
        if len(text) <= MAX_OPEN_CHARS:
            return text, info
        cut = text[:MAX_OPEN_CHARS].rsplit("\n", 1)[0]
        info["tronque"] = f"Lines 1-{len(cut.splitlines())} of {len(lines)}: ask for the rest with `debut` and `fin`."
        return cut, info
    first = max(first or 1, 1)
    last = min(last if last is not None else len(lines), len(lines))
    chosen = "\n".join(lines[first - 1 : last])
    if len(chosen) > MAX_OPEN_CHARS:
        chosen = chosen[:MAX_OPEN_CHARS]
        info["tronque"] = "The passage is too long: ask for a shorter one."
    info["passage"] = f"{first}-{last}"
    return chosen, info


async def handle_open(arguments: dict, user_id: str) -> str:
    if (down := _unavailable()) is not None:
        return down
    chemin = str(arguments.get("chemin") or "").strip()
    if not chemin:
        return _dump({"error": "'chemin' is required"})
    try:
        rel = paths.clean_relative(chemin)
    except paths.UnsafePath as e:
        return _dump({"error": f"{chemin!r} is not a path of the memory folder: {e}"})
    text = await memory_service.read(user_id, rel)
    if text is None:
        return _dump({"error": f"No file at {rel!r}: search first, use the path as given."})
    result: dict[str, Any] = {"chemin": rel}
    content, info = _passage(text, arguments.get("debut"), arguments.get("fin"))
    result.update(info)
    result["contenu"] = content
    if paths.kind_of(rel) == "fiche" and not paths.is_generated(rel):
        try:
            result["a_annoncer"] = fiches.to_announce(text)
        except carte_mod.UnreadableFile:
            result["a_annoncer"] = []
        indexed = await memory_service.indexed(user_id, rel)
        if indexed is not None:
            await memory_service.record_use(user_id, indexed.card_id)
    return _dump(result)


async def handle_write_fiche(arguments: dict, user_id: str) -> str:
    if (down := _unavailable()) is not None:
        return down
    tz = await resolve_user_timezone(user_id)
    try:
        written = await fiches.write_fiche(
            memory_service,
            user_id,
            titre=arguments.get("titre", ""),
            affirmations=arguments.get("affirmations") or [],
            remplacees=arguments.get("remplacees"),
            resume=arguments.get("resume"),
            dossiers=arguments.get("dossiers"),
            chemin=arguments.get("chemin"),
            personnes=arguments.get("personnes"),
            evoque=arguments.get("evoque"),
            alias=arguments.get("alias"),
            today=datetime.now(tz).date(),
        )
    except (fiches.FicheError, paths.UnsafePath, carte_mod.UnreadableFile) as e:
        return _dump({"error": str(e), "enregistre": False})
    return _dump(written | {"enregistre": True})


NATIVE_HANDLERS = {
    "memory_search": handle_search,
    "memory_open": handle_open,
    "memory_write_fiche": handle_write_fiche,
}

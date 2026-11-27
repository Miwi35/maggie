"""The orphans: tie each fiche to the conversations that justify it, report those with no source."""

from app.db.memory_index_repository import memory_index_repo
from app.db.memory_night_repository import memory_night_repo
from app.memory import fiches
from app.memory.night_passes.common import Pass, _with, rewrite_carte


async def run(p: Pass) -> None:
    files = await memory_index_repo.list_files(p.user_id)
    by_id = {f.card_id: f for f in files if f.kind == "conversation"}
    listed = [f for f in files if f.kind == "fiche" and not f.unreadable]
    no_source: list[str] = []
    unpointed: list[str] = []
    for fiche in listed:
        pointers = set(await memory_index_repo.linked_from(p.user_id, fiche.card_id))
        for source in fiche.sources:
            conversation = by_id.get(str(source.get("id") or ""))
            if conversation is not None and conversation.path not in pointers:
                await rewrite_carte(
                    p,
                    conversation.path,
                    lambda c, fid=fiche.card_id: _with(c, liens=list(dict.fromkeys([*c.liens, fid]))),
                    p.reason(f"lien vers la fiche « {fiche.title} »"),
                )
                pointers.add(conversation.path)
                p.note("liens", {"conversation": conversation.path, "fiche": fiche.path})
        if not fiche.sources:
            no_source.append(fiche.path)
        known = fiches.parse_body(fiche.body).known
        from_the_app = bool(known) and all(c.source.startswith(fiches.APP_SOURCE) for c in known)
        if not pointers and not from_the_app:
            unpointed.append(fiche.path)
    found = {"sans_source": no_source, "non_pointees": unpointed}
    last = await memory_night_repo.last_done(p.user_id)
    # Said once: the journal repeats an orphan only when the list changes.
    p.run.report["orphelines"] = found
    p.run.report["orphelines_deja_dites"] = last is not None and (last.report.get("orphelines") or {}) == found

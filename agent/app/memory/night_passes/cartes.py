"""The missing cartes: encode the quiet threads, then write the cartes still absent."""

from datetime import timedelta

from app.config import settings
from app.db.context_repository import context_repo
from app.db.memory_index_repository import memory_index_repo
from app.memory import conversations
from app.memory.encoder import ProposalError, merge_carte
from app.memory.night_passes.common import ENCODING_MAX_PAGES, ENCODING_PAGE, Pass, _line, _stem, transcript

CARTE_SYSTEM = (
    "Tu fais l'entretien de nuit de la mémoire de Maggie : une conversation n'a pas de carte, tu l'écris.\n"
    "Réponds UNIQUEMENT par un objet JSON : "
    '{"resume": "…", "moments": [{"heure": "AAAA-MM-JJ HHhMM", "texte": "…"}], "personnes": ["…"], "evoque": ["…"]}\n'
    "`resume` : 3 lignes au maximum, en français. `moments` : les moments clés, chacun avec l'heure EXACTE d'un "
    "message (telle qu'elle est écrite devant le message). `evoque` : les situations et thèmes pour lesquels ce "
    "souvenir devrait revenir, même sans mot commun. N'invente rien."
)


async def run(p: Pass) -> None:
    silent_before = p.now - timedelta(seconds=settings.memory_encode_after_seconds)
    offset = 0
    for _ in range(ENCODING_MAX_PAGES):
        page = await context_repo.find_to_encode(silent_before, p.now, ENCODING_PAGE, offset, p.user_id)
        encoded_here = 0
        for ctx in page:
            p.spend()
            report = await p.keeper.encoder.encode(ctx, silent_before)
            if report is None:
                continue
            encoded_here += 1
            p.note("cartes", str(ctx.label or "sans titre"))
            for written in report.written:
                p.note("ecrites", written)
        if len(page) < ENCODING_PAGE:
            break
        offset += len(page) - encoded_here

    for conversation in await memory_index_repo.list_files(p.user_id, kind="conversation"):
        if conversation.resume or conversation.unreadable:
            continue
        unit = f"carte:{conversation.card_id}"
        if p.gave_up(unit):
            continue
        text = p.service.folder(p.user_id).read(conversation.path) or ""
        messages = conversations.messages_of(text)
        if not messages:
            continue
        try:
            proposal = await p.ask(
                CARTE_SYSTEM, "Conversation :\n\n" + transcript(messages) + "\n\nRéponds par le JSON."
            )
        except ProposalError as e:
            p.failed(unit, str(e))
            continue
        if not _line(proposal.get("resume")):
            p.failed(unit, "le modèle n'a rendu aucun résumé")
            continue
        written = await conversations.update_carte_of_file(
            p.user_id,
            conversation.path,
            lambda carte, proposal=proposal, text=text: merge_carte(carte, proposal, text),
            reason=p.reason("carte rattrapée"),
            service=p.service,
        )
        if not written:
            p.failed(unit, "la conversation n'a pas de fil connu")
            continue
        p.note("cartes", _stem(conversation.path))

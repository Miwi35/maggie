"""From a file to its rows in the index.

The index never invents: it holds what the file says. A file that cannot be read is still indexed
(its text stays searchable) and flagged `unreadable`, so a broken carte is visible, not silently
dropped. A fiche with no `id` — or whose `id` another file already holds, as after a copy — gets a
new one: the stamped text is handed back to the caller, which writes it like any other write of
Maggie's (local copy first, bucket after, never over a newer edit of the owner's).
"""

import logging
from dataclasses import dataclass

from app.db.memory_index_repository import fallback_card_id, memory_index_repo
from app.memory import carte as carte_mod
from app.memory import paths
from app.memory.carte import Carte, UnreadableFile

logger = logging.getLogger(__name__)


@dataclass
class Indexed:
    card_id: str
    # The text with an `id` written into its carte, when the file needed one: to be saved by the caller.
    stamped: str | None = None
    unreadable: str | None = None


async def index_file(user_id: str, rel: str, text: str) -> Indexed:
    """(Re)build the index rows of one file. Returns the card id, and a stamped text if the file needs an id."""
    kind = paths.kind_of(rel)
    digest = carte_mod.content_hash(text)
    try:
        parsed = carte_mod.parse(text)
    except UnreadableFile as e:
        logger.warning("Unreadable carte in %s of %s: %s", rel, user_id, e)
        card_id = fallback_card_id(rel)
        await memory_index_repo.upsert(
            user_id, rel, kind=kind, card_id=card_id, carte=Carte(), body=text, digest=digest, unreadable=str(e)[:300]
        )
        return Indexed(card_id, unreadable=str(e))

    carte = parsed.carte
    stamped: str | None = None
    if kind == "fiche":
        holder = (await memory_index_repo.card_ids(user_id)).get(carte.id) if carte.id else None
        if carte.id is None or (holder is not None and holder != rel):
            if carte.id is not None:
                logger.warning("%s of %s copies the id of %s: giving it one of its own", rel, user_id, holder)
            carte.id = carte_mod.new_id()
            stamped = carte_mod.with_id(text, carte.id)
            digest = carte_mod.content_hash(stamped)
    card_id = carte.id or fallback_card_id(rel)
    await memory_index_repo.upsert(
        user_id, rel, kind=kind, card_id=card_id, carte=carte, body=parsed.body, digest=digest
    )
    return Indexed(card_id, stamped=stamped)

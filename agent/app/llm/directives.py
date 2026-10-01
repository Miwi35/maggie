"""The behaviour preferences the user stated, as a system prompt section (MAG-22).

A directive of kind `behavior` — « tutoie-moi », « moins d'emojis » — is about *how*
Maggie answers, so it is worth nothing unless it is in front of the model on every
turn. This is the one place that renders it, read by both gateways: the streamed chat
and the proactions, which the user reads in the same conversation and would otherwise
answer in two different voices.
"""

import logging

from app.db.instruction_model import InstructionKind
from app.db.instruction_repository import instruction_repo

logger = logging.getLogger(__name__)

HEADER = "\n\nPréférences de l'utilisateur sur ta façon de répondre — respecte-les dans chaque message :"


async def behavior_directives_section(user_id: str) -> str:
    """The user's behaviour preferences, or an empty string if there are none.

    Never raises: a database hiccup must cost the user the preferences, not the answer.
    """
    try:
        directives = await instruction_repo.find_by_user(user_id, kind=InstructionKind.BEHAVIOR)
    except Exception as e:
        logger.warning(f"Could not load behaviour directives: {e}")
        return ""

    if not directives:
        return ""

    lines = [HEADER]
    # Oldest first: `find_by_user` returns the newest first, and the last line of a
    # list is the one a model weighs most — so the most recent preference goes last.
    for directive in reversed(directives):
        lines.append(f"- {directive.content}")
    return "\n".join(lines)

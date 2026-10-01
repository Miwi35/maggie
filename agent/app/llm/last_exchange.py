"""When the conversation last happened, for every path that answers in it (MAG-10).

Maggie used to know the hour and nothing of the silence before it, so she could neither
say « rebonjour » after an evening away nor offer to pick the subject back up. The line
built here goes in the volatile system block — it changes with every message, so in the
cached prefix it would invalidate the cache every time.

Never raises: a database that will not answer costs the model the greeting's context,
not the user the answer.
"""

import logging

from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.personality.engine import last_exchange_line

logger = logging.getLogger(__name__)


async def last_exchange_section(user_id: str, *, exclude_message_id: str | None = None) -> str:
    """The last message's time, gap and thread label as a system-prompt line, or an empty string.

    `exclude_message_id` is the message being answered: the chat routes store it before
    the gateway runs, so without it the « last conversation » would always be the one in
    progress, a few milliseconds ago.
    """
    try:
        last = await message_repo.find_last(user_id, exclude_id=exclude_message_id)
    except Exception as e:
        logger.warning(f"Could not read the last message: {e}")
        return ""
    if last is None:
        return ""

    topic = None
    if last.context_id:
        try:
            context = await context_repo.get(last.context_id)
            topic = context.label if context else None
        except Exception as e:
            logger.warning(f"Could not read the thread of the last message: {e}")

    return "\n" + last_exchange_line(last.created_at, topic)

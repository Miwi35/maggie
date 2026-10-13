"""The move out of the agent's own `proaction` table (MAG-327).

Before ADR-012 the agent stored its proactions in `maggie_agent.proaction`. Each one still
`pending` is scheduled again through the API, then deleted from the table; once none is
left, the table is dropped. A row the API refuses (an empty prompt) is dropped with a
warning; an API that does not answer keeps it for the next attempt, so the move resumes
where it stopped and never schedules one twice.
"""

import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import text

from app.db.agent_engine import agent_engine
from app.proactions import api as proactions_api
from app.proactions.api import ProactionApiError, ProactionApiUnreachable

logger = logging.getLogger(__name__)

LEGACY_TABLE = "proaction"
# The API refuses a time already passed: one that fell due while the agent was being
# deployed runs as soon as it can, a minute later, rather than never.
OVERDUE_DELAY = timedelta(minutes=1)

_done = False


def _utcnow() -> datetime:
    return datetime.now(UTC)


async def _table_exists(conn) -> bool:
    return await conn.run_sync(lambda sync: sync.dialect.has_table(sync, LEGACY_TABLE))


def _as_utc(value: datetime | str) -> datetime:
    at = datetime.fromisoformat(value) if isinstance(value, str) else value
    return at if at.tzinfo else at.replace(tzinfo=UTC)


async def migrate_legacy_proactions() -> bool:
    """Move the pending proactions to the API and drop the table; True once there is no table left.

    Cheap once done: the executor calls it every minute until it returns True.
    """
    global _done
    if _done:
        return True

    async with agent_engine.connect() as conn:
        if not await _table_exists(conn):
            _done = True
            return True
        rows = (
            await conn.execute(
                text(
                    f"SELECT id, user_id, prompt, scheduled_at FROM {LEGACY_TABLE} "
                    "WHERE status = 'pending' ORDER BY scheduled_at"
                )
            )
        ).all()

    earliest = _utcnow() + OVERDUE_DELAY
    for row in rows:
        try:
            await proactions_api.schedule(row.user_id, row.prompt, max(_as_utc(row.scheduled_at), earliest))
        except ProactionApiUnreachable as e:
            logger.warning(f"Legacy proaction {row.id} not moved yet: {e}")
            return False
        except ProactionApiError as e:
            # The API answered, and refused it: asking again would be refused again.
            logger.warning(f"Legacy proaction {row.id} refused by the API, dropped: {e}")
        except Exception as e:
            logger.warning(f"Legacy proaction {row.id} not moved yet: {e}")
            return False

        async with agent_engine.begin() as conn:
            await conn.execute(text(f"DELETE FROM {LEGACY_TABLE} WHERE id = :id"), {"id": row.id})

    async with agent_engine.begin() as conn:
        await conn.execute(text(f"DROP TABLE IF EXISTS {LEGACY_TABLE}"))

    logger.info(f"Legacy proaction table dropped, {len(rows)} pending proaction(s) moved to the API")
    _done = True
    return True

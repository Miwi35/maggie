import logging

from app.memory.index import Change
from app.mercure import topics
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class MemoryNotifier:
    """Tells the `memory` Mercure stream what changed. A hub that is down must never fail a sync."""

    def __init__(self, publisher: MercurePublisher | None = None) -> None:
        self._publisher = publisher

    async def publish(self, changes: list[Change]) -> None:
        if not changes:
            return
        publisher = self._publisher or MercurePublisher()
        for change in changes:
            try:
                await publisher.publish(
                    topics.for_user(topics.MEMORY, change.user_id),
                    {
                        "type": change.kind,
                        "noteId": change.note_id,
                        "path": change.path,
                        "title": change.title,
                        "status": change.status,
                    },
                )
            except Exception as e:
                logger.warning(f"Failed to publish memory change {change.kind} for {change.path}: {e}")

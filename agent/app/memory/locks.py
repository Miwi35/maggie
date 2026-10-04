import asyncio
from collections import defaultdict
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager


class PathLocks:
    """One lock per note path, so the write door, the outbox flush and the reconciler never work one note at once.

    The agent runs as a single replica, so a process-local lock is the whole of it: the bucket's
    conditional writes are what protect against the owner, not against ourselves.
    """

    def __init__(self) -> None:
        self._locks: dict[tuple[str, str], asyncio.Lock] = defaultdict(asyncio.Lock)

    @asynccontextmanager
    async def hold(self, user_id: str, *paths: str) -> AsyncIterator[None]:
        """Take the locks of every path, always in sorted order, so two moves cannot wait on each other."""
        ordered = sorted(set(paths))
        taken: list[asyncio.Lock] = []
        try:
            for path in ordered:
                lock = self._locks[(user_id, path)]
                await lock.acquire()
                taken.append(lock)
            yield
        finally:
            for lock in reversed(taken):
                lock.release()

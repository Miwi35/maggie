"""`python -m app.memory.rebuild [USER_ID]` — drop the note index and read it back from the bucket."""

import asyncio
import json
import sys

from app.db.memory_note_model import MemoryEvent, MemoryNote, MemoryOutbox  # noqa: F401 — register models
from app.db.proaction_repository import proaction_repo
from app.memory import service as memory_service


async def run(user_id: str | None) -> int:
    await proaction_repo.ensure_table()
    service = memory_service.configure()
    if service is None:
        print("MEMORY_BUCKET is not set: nothing to rebuild from", file=sys.stderr)
        return 2
    report = await service.sync.rebuild(user_id)
    print(json.dumps(report.to_dict(), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(run(sys.argv[1] if len(sys.argv) > 1 else None)))

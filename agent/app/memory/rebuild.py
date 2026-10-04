"""`python -m app.memory.rebuild --offline [USER_ID]` — drop the note index and read it back from the bucket.

This runs its own sync engine, so the agent must be stopped first: a second engine next to a live
one would race it on every note. It refuses to start without `--offline`. With the agent running,
use `POST /agent/memory/rebuild` instead (it rebuilds the caller's own notes).
"""

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


def main(argv: list[str]) -> int:
    if "--offline" not in argv:
        print("Refusing to run: the agent must be stopped first. Pass --offline to confirm it is.", file=sys.stderr)
        return 2
    users = [arg for arg in argv if arg != "--offline"]
    return asyncio.run(run(users[0] if users else None))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

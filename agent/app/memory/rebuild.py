"""Rebuild the memory index from the files: `python -m app.memory.rebuild [--user <ULID>]`.

The index is throwaway, the files are the truth: this empties it and reads every file again, so
the cartes, the evoked terms, the aliases, the links and the full-text search come back as they
were. The usage counters live in their own table and are not touched. Run it in the agent's
container, where the settings and the database are.
"""

import argparse
import asyncio
import logging
import sys

from app.memory.service import memory_service

logger = logging.getLogger(__name__)


async def run(user_id: str | None) -> int:
    report = await memory_service.rebuild(user_id)
    print(f"Index rebuilt: {report.files} file(s) for {report.users} user(s).")
    for skipped in report.skipped:
        print(f"Left alone (no local copy to rebuild from): {skipped}", file=sys.stderr)
    return 1 if report.skipped else 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Rebuild the memory index from the Markdown files.")
    parser.add_argument("--user", help="only this user (ULID); default: every user")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s: %(message)s")
    sys.exit(asyncio.run(run(args.user)))


if __name__ == "__main__":
    main()

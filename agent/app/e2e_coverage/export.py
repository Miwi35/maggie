"""Turn the agent's coverage data into one raw file per journey (spec contract 2).

    python -m app.e2e_coverage.export --data-dir .e2e-coverage --out .e2e-coverage/raw

Reads every data file coverage.py left in `--data-dir` (one per process, `parallel = true`),
without combining or deleting them — running it twice gives the same files, and a later run sees
what the agent recorded since. Writes `<out>/<slug>.json`:

    {"journey": "e2e/web/tests/chat.spec.ts", "files": {"agent/app/api/routes.py": [12, 13, 40]}}

Paths are made relative to `--root` (the agent's directory inside the container) and prefixed
with `--prefix` (its place in the repository). Lines recorded outside any journey — start-up,
the scheduler, the proaction consumer — belong to none and are dropped.
"""

import argparse
import json
from collections import defaultdict
from pathlib import Path

from coverage import CoverageData

from app.e2e_coverage import slug, valid_journey


def collect(data_dir: Path, root: str, prefix: str) -> dict[str, dict[str, set[int]]]:
    """journey -> repo path -> executed lines, over every data file in `data_dir`."""
    root_prefix = root.rstrip("/") + "/"
    journeys: dict[str, dict[str, set[int]]] = defaultdict(lambda: defaultdict(set))

    for path in sorted(data_dir.glob(".coverage*")):
        if not path.is_file():
            continue
        data = CoverageData(basename=str(path))
        try:
            data.read()
        except Exception:
            # A process killed mid-write leaves a partial file: the others still count.
            continue
        for measured in data.measured_files():
            if not measured.startswith(root_prefix):
                continue
            repo_path = f"{prefix.rstrip('/')}/{measured[len(root_prefix) :]}"
            for line, contexts in data.contexts_by_lineno(measured).items():
                for context in contexts:
                    journey = valid_journey(context)
                    if journey:
                        journeys[journey][repo_path].add(line)

    return journeys


def write(journeys: dict[str, dict[str, set[int]]], out_dir: Path) -> int:
    """One file per journey in `out_dir`, emptied first so a renamed journey leaves nothing behind."""
    out_dir.mkdir(parents=True, exist_ok=True)
    for stale in out_dir.glob("*.json"):
        stale.unlink()

    for journey, files in journeys.items():
        payload = {
            "journey": journey,
            "files": {path: sorted(lines) for path, lines in sorted(files.items())},
        }
        (out_dir / f"{slug(journey)}.json").write_text(json.dumps(payload, separators=(",", ":")) + "\n")

    return len(journeys)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data-dir", type=Path, default=Path(".e2e-coverage"))
    parser.add_argument("--out", type=Path, default=Path(".e2e-coverage/raw"))
    parser.add_argument("--root", default="/app")
    parser.add_argument("--prefix", default="agent")
    args = parser.parse_args(argv)

    count = write(collect(args.data_dir, args.root, args.prefix), args.out)
    print(f"agent coverage: {count} journeys written to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

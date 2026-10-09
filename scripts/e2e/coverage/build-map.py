#!/usr/bin/env python3
"""The line -> journeys map of the nightly (spec « Sélection e2e par couverture », part C).

Usage:
    build-map.py [--raw DIR] [--out FILE] [--commit SHA] [--now ISO]

Reads every raw coverage file under DIR (default e2e/coverage/raw), written by
the nightly with E2E_COVERAGE=1, one per component and journey:

    e2e/coverage/raw/<component>/<journey slug>.json
    {"journey": "<repo path of the journey>", "files": {"<repo path>": [executed lines]}}

and writes the map `scripts/e2e/impacted.sh select --coverage-map` reads:

    {"version": 1, "commit": "<sha>", "generatedAt": "<UTC ISO, seconds>",
     "journeys": ["<id>", ...],
     "files": {"<repo path>": [[start, end, "<hex bitmask>"], ...]}}

Bit i of the bitmask is journeys[i]. Consecutive lines executed by the same set
of journeys are one range; a line no journey executed is in no range. The
components of one journey are merged (the API lines a web journey reached and
its admin lines are the same journey). A component that left no file brings no
line: the selection falls back to e2e/impact-map.yml for its files.

--expect names the lots the night played (web-<shard>, mobile-phone-<lot>):
each must have left its completion mark (<raw>/_lots/<lot>.ok, collect-lot.sh).

Exit 0 with the map written; 3 when there is no usable raw file (no map is
better than an empty one); 4 when an expected lot is not complete (nor is a
partial one); 2 on a usage error. A malformed raw file is skipped
with a warning, never fatal.

Python 3 standard library only: it runs on the runner of the nightly's last job.
"""

import argparse
import datetime
import glob
import json
import os
import subprocess
import sys


def warn(message):
    print(f"::warning::build-map: {message}", file=sys.stderr)


def normalise(path):
    path = path.replace("\\", "/")
    while path.startswith("./"):
        path = path[2:]
    return path.lstrip("/")


def read_raw(raw_dir):
    """{journey id: {path: set(lines)}} from every raw file."""
    lines_by_journey = {}
    for name in sorted(glob.glob(os.path.join(raw_dir, "**", "*.json"), recursive=True)):
        try:
            with open(name, encoding="utf-8") as handle:
                raw = json.load(handle)
            journey = raw["journey"]
            files = raw["files"]
            if not isinstance(journey, str) or not journey or not isinstance(files, dict):
                raise ValueError("journey must be a non-empty string and files an object")
        except (OSError, ValueError, KeyError, TypeError) as error:
            warn(f"{name} skipped: {error}")
            continue
        per_file = lines_by_journey.setdefault(normalise(journey), {})
        for path, lines in files.items():
            if not isinstance(lines, list):
                warn(f"{name}: {path} skipped, its lines are not a list")
                continue
            per_file.setdefault(normalise(path), set()).update(
                n for n in lines if isinstance(n, int) and not isinstance(n, bool) and n > 0
            )
    return lines_by_journey


def build(lines_by_journey, commit, generated_at):
    journeys = sorted(lines_by_journey)
    masks = {}  # path -> {line: mask}
    for index, journey in enumerate(journeys):
        bit = 1 << index
        for path, lines in lines_by_journey[journey].items():
            per_line = masks.setdefault(path, {})
            for line in lines:
                per_line[line] = per_line.get(line, 0) | bit
    files = {}
    for path in sorted(masks):
        ranges = []
        for line in sorted(masks[path]):
            mask = masks[path][line]
            if ranges and ranges[-1][1] == line - 1 and ranges[-1][2] == mask:
                ranges[-1][1] = line
            else:
                ranges.append([line, line, mask])
        if ranges:
            files[path] = [[start, end, format(mask, "x")] for start, end, mask in ranges]
    return {
        "version": 1,
        "commit": commit,
        "generatedAt": generated_at,
        "journeys": journeys,
        "files": files,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw", default="e2e/coverage/raw")
    parser.add_argument("--out", default="e2e-coverage-map.json")
    parser.add_argument("--commit", help="the commit the journeys ran on (default: git rev-parse HEAD)")
    parser.add_argument("--now", help="generatedAt, for the tests (default: now, UTC)")
    parser.add_argument(
        "--expect",
        nargs="*",
        default=[],
        metavar="LOT",
        help="lots that must be marked complete (<raw>/_lots/<lot>.ok, written by collect-lot.sh)",
    )
    args = parser.parse_args()

    # A lot missing would leave lines "covered" by only some of the journeys that
    # run them: a pull request would play too few. No map is safe, a partial one is not.
    missing = [lot for lot in args.expect if not os.path.isfile(os.path.join(args.raw, "_lots", f"{lot}.ok"))]
    if missing:
        warn(f"incomplete coverage, no map: no complete collection for {', '.join(missing)}")
        return 4

    commit = args.commit
    if not commit:
        try:
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True
            ).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            commit = "unknown"
    generated_at = args.now or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    lines_by_journey = read_raw(args.raw)
    lines_by_journey = {j: f for j, f in lines_by_journey.items() if any(f.values())}
    if not lines_by_journey:
        warn(f"no executed line under {args.raw}: no map")
        return 3

    coverage_map = build(lines_by_journey, commit, generated_at)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(coverage_map, handle, separators=(",", ":"))
        handle.write("\n")
    ranges = sum(len(r) for r in coverage_map["files"].values())
    print(
        f"{args.out}: {len(coverage_map['journeys'])} journeys, {len(coverage_map['files'])} files, "
        f"{ranges} ranges, {os.path.getsize(args.out) // 1024} KB, commit {commit[:12]}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""A JaCoCo XML report → the raw coverage of one journey (« Sélection e2e par couverture »).

Usage: jacoco_lines.py <report.xml> <journey id> <repo root> <source dir>... > <slug>.json

Writes {"journey": "<id>", "files": {"<path from the repo root>": [executed lines]}}.

JaCoCo names a source by its package and its file name only
(`com/maggie/app/MainActivity.kt`); the source directories given — the
variant's, `mobile/app/src/main/java` and the like — say where it is in the
repository. A line counts as executed when JaCoCo saw at least one of its
instructions run (`ci > 0`). A file found in no source directory (generated
code: BuildConfig, Room's `_Impl`) is dropped.
"""

import json
import os
import sys
import xml.etree.ElementTree as ET


def executed_lines(report_path: str, repo_root: str, source_dirs: list[str]) -> dict[str, list[int]]:
    files: dict[str, set[int]] = {}
    root = ET.parse(report_path).getroot()
    for package in root.iter("package"):
        package_path = package.get("name", "")
        for source in package.iter("sourcefile"):
            lines = {int(line.get("nr")) for line in source.iter("line") if int(line.get("ci", "0")) > 0}
            if not lines:
                continue
            relative = f"{package_path}/{source.get('name')}" if package_path else source.get("name", "")
            for directory in source_dirs:
                candidate = os.path.join(directory, relative)
                if os.path.isfile(os.path.join(repo_root, candidate)):
                    files.setdefault(candidate.replace(os.sep, "/"), set()).update(lines)
                    break
    return {path: sorted(lines) for path, lines in sorted(files.items())}


def main(argv: list[str]) -> int:
    if len(argv) < 5:
        print(__doc__, file=sys.stderr)
        return 64
    report, journey, repo_root, *source_dirs = argv[1:]
    json.dump({"journey": journey, "files": executed_lines(report, repo_root, source_dirs)}, sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

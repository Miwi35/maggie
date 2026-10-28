#!/usr/bin/env python3
"""PreToolUse guard for Bash: refuses the commands CLAUDE.md forbids, before they run.

Prose rules were not enough (28 Oct.: a session raised the Kotlin daemon's heap twice and rebuilt
everything with --rerun-tasks, leaving three compilers on the machine). Exit 2 blocks the call and
hands the reason back to the agent.
"""

import json
import re
import sys

# Command position: start of a line or after ; & | ( or `$(`.
CMD = r"(?:^|[;&|(]|\$\()\s*(?:sudo\s+)?(?:[A-Z_]+=\S*\s+)*"

RULES = [
    (
        CMD + r"(?:\S*/)?gradlew\b",
        "Gradle by hand is forbidden: run `task wt:test:mobile -- --tests '<class>'` (one build at a time, bounded daemons).",
    ),
    (
        r"--rerun-tasks|--no-build-cache",
        "Rebuilding everything is forbidden on this shared machine: the build cache is correct, run the targeted tests as they are.",
    ),
    (
        r"\b(?:WT_KOTLIN_DAEMON_JVMARGS|WT_GRADLE_JVMARGS|WT_GRADLE_MEMORY_HIGH|WT_GRADLE_MEMORY_MAX|"
        r"WT_GRADLE_MAX_WORKERS|WT_GRADLE_CPU_QUOTA)=|kotlin\.daemon\.jvmargs|org\.gradle\.jvmargs",
        "The Gradle and Kotlin memory limits are fixed in wt/gradle.sh: changing them starts a new daemon beside the warm one.",
    ),
    (
        CMD + r"(?:php|composer|bin/console|npm|npx|pnpm|yarn|pytest|uv|python3?\s+-m\s+pytest)\b",
        "Runtimes live in containers: use the Taskfile (`task fix:all`, `task wt:*`, `task api:console -- …`).",
    ),
    (
        CMD + r"(?:maestro|emulator)\b",
        "Maestro and emulators only through `task e2e:mobile`, to write or debug a journey; CI runs the journeys.",
    ),
    (
        CMD + r"task\s+wt:test:mobile\b(?![^\n;&|]*--tests)",
        "The whole mobile suite is CI's job: name the classes, `task wt:test:mobile -- --tests '<class>'`.",
    ),
    (
        CMD + r"task\s+wt:test:(?:api|admin|agent|ciqual)\s*(?:$|[;&|)\n])",
        "Never the full suite locally: CI runs it. Narrow it (`-- --filter …`, `-- --testsuite <Module>`, one file, one pytest path).",
    ),
]


def main() -> int:
    event = json.load(sys.stdin)
    if event.get("tool_name") != "Bash":
        return 0
    command = event.get("tool_input", {}).get("command", "")
    for pattern, reason in RULES:
        if re.search(pattern, command, re.MULTILINE):
            print(f"Refused by .claude/hooks/guard_bash.py: {reason}", file=sys.stderr)
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
#
# Create the directories a container bind-mounts from this worktree, as the
# host user (MAG-287).
#
# Docker makes a missing mount source itself — and a mount point nested inside
# another bind mount (`-v cache:/app/vendor` under `-v ./api:/app`) — owned by
# root. In a fresh worktree the first `task fix:all`, `wt:*` or `e2e:up` then
# leaves an empty root-owned `api/vendor`, and the next `composer install`,
# running as the host user, cannot write into it.
#
# Usage: wt/mountpoints.sh <path>...      (relative to the repository root)
#
# Missing → created. Owned by another user and empty → removed and recreated
# (the parent is ours, so no sudo is needed). Owned by another user and not
# empty → the one case this cannot repair: it names the directory and the
# command to run. Messages go to stderr, so callers can capture stdout.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ME="$(id -u)"

for rel in "$@"; do
    dir="$REPO_ROOT/$rel"

    if [ -d "$dir" ] && [ "$(stat -c %u "$dir")" != "$ME" ]; then
        owner="$(stat -c %U "$dir")"
        if [ -z "$(ls -A "$dir")" ] && rmdir "$dir" 2>/dev/null; then
            printf 'Recreated %s: Docker had made it empty and owned by %s.\n' "$rel" "$owner" >&2
        else
            {
                printf '%s is owned by %s, so this user cannot write to it.\n' "$dir" "$owner"
                printf 'Docker created it as root the first time it bind-mounted a missing directory. Remove it, then run the command again:\n'
                printf '  sudo rm -rf %s\n' "$dir"
            } >&2
            exit 1
        fi
    fi

    mkdir -p "$dir"
done

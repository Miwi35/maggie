#!/usr/bin/env bash
# A gh that stands in for `gh pr view` and `gh pr diff` in agent-guard-batch.test.sh.
# Every answer is a file of $FAKE_GH_DIR: pr-<n>.json (labels, body, state) and
# pr-<n>.diff. A missing file, or `no-diff-<n>` for the diff, is a failure.
set -euo pipefail

dir="${FAKE_GH_DIR:?}"
echo "$*" >> "$dir/calls"

[ "$1" = "pr" ] || { echo "fake-gh-pr: unexpected call: $*" >&2; exit 2; }
n="$3"
case "$2" in
  view) file="$dir/pr-$n.json" ;;
  diff) file="$dir/pr-$n.diff"; [ ! -f "$dir/no-diff-$n" ] || { echo "gh: HTTP 406 diff too large" >&2; exit 1; } ;;
  *) echo "fake-gh-pr: unexpected call: $*" >&2; exit 2 ;;
esac
[ -f "$file" ] || { echo "gh: PR $n not found" >&2; exit 1; }
cat "$file"

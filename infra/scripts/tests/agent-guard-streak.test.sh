#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/agent-guard/ci-streak.sh (MAG-128): when "two red CI runs in
# a row" is true, and when a cancelled or repeated run must not change the answer.
#
# Usage: infra/scripts/tests/agent-guard-streak.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STREAK="$HERE/../../../scripts/agent-guard/ci-streak.sh"
failures=0

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# check <name> <expected status> <json: runs, newest first>
check() {
  local out status
  out="$("$STREAK" <<< "$3")"
  status=$?
  [ "$status" -eq "$2" ] && ok "$1" || bad "$1 — expected $2, got $status ($out)"
}

check "two red commits in a row" 10 \
  '[{"conclusion":"failure","headSha":"b"},{"conclusion":"failure","headSha":"a"}]'
check "a single red run" 0 \
  '[{"conclusion":"failure","headSha":"a"}]'
check "red then green" 0 \
  '[{"conclusion":"success","headSha":"b"},{"conclusion":"failure","headSha":"a"}]'
check "green then red" 0 \
  '[{"conclusion":"failure","headSha":"b"},{"conclusion":"success","headSha":"a"}]'
check "two runs on the same commit are one failure" 0 \
  '[{"conclusion":"failure","headSha":"a"},{"conclusion":"failure","headSha":"a"}]'
check "a cancelled run does not break the streak" 10 \
  '[{"conclusion":"failure","headSha":"c"},{"conclusion":"cancelled","headSha":"b"},{"conclusion":"failure","headSha":"a"}]'
check "a cancelled run does not make one" 0 \
  '[{"conclusion":"failure","headSha":"c"},{"conclusion":"cancelled","headSha":"b"}]'
check "no run at all" 0 '[]'
check "the red run of a draft is not a failure of the code" 0 \
  '[{"conclusion":"failure","headSha":"b"},{"conclusion":"failure","headSha":"a","displayTitle":"PR #1 · title · draft"}]'
check "red drafts between two real reds do not hide them" 10 \
  '[{"conclusion":"failure","headSha":"c"},{"conclusion":"failure","headSha":"b","displayTitle":"PR #1 · t · draft"},{"conclusion":"failure","headSha":"a","displayTitle":"PR #1 · t"}]'

[ "$failures" -eq 0 ] && echo "All good." || { echo "$failures failed."; exit 1; }

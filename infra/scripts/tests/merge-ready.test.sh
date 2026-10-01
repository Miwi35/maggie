#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/merge-ready.sh (MAG-200): when an armed pull request that
# stays open may be merged by `task ci:watch`, and when it must be left alone.
#
# Usage: infra/scripts/tests/merge-ready.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
READY="$HERE/../merge-ready.sh"
failures=0

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

ARMED='{"enabledAt":"2026-10-01T07:32:18Z"}'

# check <name> <expected status> <expected text in the verdict> <json>
check() {
  local out status
  out="$("$READY" <<< "$4")"
  status=$?
  { [ "$status" -eq "$2" ] && [[ "$out" == *"$3"* ]]; } && ok "$1" || bad "$1 — expected $2 / $3, got $status ($out)"
}

check "armed, everything green" 0 ready \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\",\"B\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"},{\"name\":\"B\",\"conclusion\":\"SUCCESS\"}]}"
check "a skipped job is green" 0 ready \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\",\"B\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"},{\"name\":\"B\",\"conclusion\":\"SKIPPED\"}]}"
check "a commit status counts too" 0 ready \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\"],
    \"statusCheckRollup\":[{\"context\":\"A\",\"state\":\"SUCCESS\"}]}"
check "checks that are not required do not matter" 0 ready \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"},{\"name\":\"Mobile\",\"conclusion\":\"FAILURE\"}]}"
check "a required check never reported" 1 "B: never reported" \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\",\"B\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"}]}"
check "a required check is red" 1 "B: FAILURE" \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\",\"B\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"},{\"name\":\"B\",\"conclusion\":\"FAILURE\"}]}"
check "a required check is still running" 1 "B: IN_PROGRESS" \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\",\"B\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"},{\"name\":\"B\",\"status\":\"IN_PROGRESS\",\"conclusion\":\"\"}]}"
check "the last run of a check wins" 0 ready \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"FAILURE\"},{\"name\":\"A\",\"conclusion\":\"SUCCESS\"}]}"
check "auto-merge not armed" 1 "not armed" \
  '{"state":"OPEN","autoMergeRequest":null,"labels":[],"required":["A"],"statusCheckRollup":[{"name":"A","conclusion":"SUCCESS"}]}'
check "handed to a human" 1 needs-human \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[{\"name\":\"needs-human\"}],\"required\":[\"A\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"}]}"
check "already merged" 1 "not open" \
  "{\"state\":\"MERGED\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\"],
    \"statusCheckRollup\":[{\"name\":\"A\",\"conclusion\":\"SUCCESS\"}]}"
check "no rollup at all" 1 "A: never reported" \
  "{\"state\":\"OPEN\",\"autoMergeRequest\":$ARMED,\"labels\":[],\"required\":[\"A\"],\"statusCheckRollup\":[]}"

[ "$failures" -eq 0 ] && echo "All good." || { echo "$failures failed."; exit 1; }

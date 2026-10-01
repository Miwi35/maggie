#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/should-deploy.sh against a fake gh (MAG-189).
#
# A CD run starts for every CI that finishes, including the late CI of an older
# commit. It used to build and deploy the head of main anyway: new digests under
# the same tag, a failed digest assertion, a rollback for nothing. What matters:
# only the CI of the current head of main, not yet in production, deploys.
#
# Usage: infra/scripts/tests/should-deploy.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../should-deploy.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

h() { printf '%40s' '' | tr ' ' "$1"; }
HEAD_SHA="$(h a)"
OLDER_SHA="$(h b)"
OLDEST_SHA="$(h c)"

# fresh_world <main-head> — no CD run has succeeded yet.
fresh_world() {
  rm -rf "$work/gh" "$work/output"
  mkdir -p "$work/gh"
  : > "$work/gh/calls"
  : > "$work/gh/deployed-shas"
  : > "$work/output"
  echo "$1" > "$work/gh/main-sha"
}

# run_gate <ci-sha>
run_gate() {
  : > "$work/output"
  OUTPUT="$(FAKE_GH_DIR="$work/gh" GH="$HERE/fake-gh.sh" GITHUB_REPOSITORY=Miwi35/maggie \
    GITHUB_OUTPUT="$work/output" GITHUB_RUN_ID="${RUN_ID-}" CANCEL_WAIT=0 CI_SHA="$1" "$SCRIPT" 2>&1)"
  STATUS=$?
  PROCEED="$(sed -n 's/^proceed=//p' "$work/output")"
}

printf '\n\033[1mThe CI of the head of main, not deployed yet\033[0m\n'
fresh_world "$HEAD_SHA"
run_gate "$HEAD_SHA"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$PROCEED" = "true" ] && ok "deploys" || bad "proceed='$PROCEED' — $OUTPUT"

printf '\n\033[1mThe late CI of an older commit (the incident of a6af4c7)\033[0m\n'
fresh_world "$HEAD_SHA"
run_gate "$OLDER_SHA"
[ "$STATUS" -eq 0 ] && ok "exits 0: a clean stop, not a failure" || bad "exit $STATUS — $OUTPUT"
[ "$PROCEED" = "false" ] && ok "does not deploy" || bad "proceed='$PROCEED' although main moved on"
printf '%s' "$OUTPUT" | grep -qF "${OLDER_SHA:0:7}" && ok "names the commit it ignores" || bad "silent about the commit — $OUTPUT"
printf '%s' "$OUTPUT" | grep -qF "${HEAD_SHA:0:7}" && ok "names the current head" || bad "silent about the head — $OUTPUT"

printf '\n\033[1mTwo late CIs: neither deploys, whatever their order\033[0m\n'
fresh_world "$HEAD_SHA"
run_gate "$OLDEST_SHA"
[ "$PROCEED" = "false" ] && ok "the oldest does not" || bad "proceed='$PROCEED'"
run_gate "$OLDER_SHA"
[ "$PROCEED" = "false" ] && ok "the older does not" || bad "proceed='$PROCEED'"

printf '\n\033[1mA second CD on a SHA already deployed\033[0m\n'
fresh_world "$HEAD_SHA"
echo "$HEAD_SHA" >> "$work/gh/deployed-shas"
run_gate "$HEAD_SHA"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$PROCEED" = "false" ] && ok "does nothing" || bad "proceed='$PROCEED' for a SHA already in production"
printf '%s' "$OUTPUT" | grep -qi 'already' && ok "says why" || bad "silent about the earlier deployment — $OUTPUT"

printf '\n\033[1mAnother commit deployed earlier does not count\033[0m\n'
fresh_world "$HEAD_SHA"
echo "$OLDER_SHA" >> "$work/gh/deployed-shas"
run_gate "$HEAD_SHA"
[ "$PROCEED" = "true" ] && ok "the head still deploys" || bad "proceed='$PROCEED' — a run on another SHA was taken for this one"

printf '\n\033[1mA failed or rolled-back deployment of the head can be retried\033[0m\n'
fresh_world "$HEAD_SHA"
run_gate "$HEAD_SHA"
grep -qF 'status=success' "$work/gh/calls" && ok "only successful runs count" || bad "does not filter on successful runs — $(cat "$work/gh/calls")"
grep -qF "head_sha=$HEAD_SHA" "$work/gh/calls" && ok "asks about this SHA only" || bad "does not filter on the SHA — $(cat "$work/gh/calls")"

printf '\n\033[1mA run that stops is cancelled, never left green\033[0m\n'
# A green stopped run would count as "this commit was handled": the next gate would
# call the head deployed, and the change detection would diff from a commit that
# never shipped.
RUN_ID=4242
fresh_world "$HEAD_SHA"
run_gate "$OLDER_SHA"
grep -qx 'run cancel 4242' "$work/gh/calls" && ok "stale commit: cancels its own run" || bad "stale run left to end green — $(cat "$work/gh/calls")"
fresh_world "$HEAD_SHA"
echo "$HEAD_SHA" >> "$work/gh/deployed-shas"
run_gate "$HEAD_SHA"
grep -qx 'run cancel 4242' "$work/gh/calls" && ok "already deployed: cancels its own run" || bad "duplicate run left to end green — $(cat "$work/gh/calls")"
fresh_world "$HEAD_SHA"
run_gate "$HEAD_SHA"
! grep -q 'run cancel' "$work/gh/calls" && ok "deploying run is left alone" || bad "cancelled the run that must deploy"
fresh_world "$HEAD_SHA"
touch "$work/gh/cancel-down"
run_gate "$OLDER_SHA"
[ "$STATUS" -ne 0 ] && [ "$PROCEED" = "false" ] && ok "cancel refused: fails, so the run ends red, and does not deploy" || bad "exit $STATUS, proceed='$PROCEED'"
printf '%s' "$OUTPUT" | grep -qF 'could not cancel' && ok "says why" || bad "silent about the failed cancel — $OUTPUT"
fresh_world "$HEAD_SHA"
touch "$work/gh/cancel-survives"
run_gate "$OLDER_SHA"
[ "$STATUS" -ne 0 ] && ok "cancel accepted but the run lives on: fails instead of ending green" || bad "exit 0 although the run was not cancelled"
RUN_ID=

printf '\n\033[1mThe API is down: fail closed\033[0m\n'
fresh_world "$HEAD_SHA"
touch "$work/gh/gh-down"
run_gate "$HEAD_SHA"
[ "$STATUS" -ne 0 ] && ok "fails" || bad "exit 0 without being able to read main"
[ "$PROCEED" != "true" ] && ok "does not deploy blind" || bad "proceed=true although nothing could be checked"

printf '\n\033[1mNo CI SHA: refused\033[0m\n'
fresh_world "$HEAD_SHA"
run_gate ""
[ "$STATUS" -ne 0 ] && ok "fails" || bad "accepted an empty SHA"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mAll cases pass\033[0m\n'

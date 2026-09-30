#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/rollback-k3s.sh against a fake kubectl (MAG-106).
#
# The rollback runs unattended on production, so what it must NOT do matters as
# much as what it does: undoing a deployment the deploy never touched would send
# it to an older release than the one it serves.
#
# Usage: infra/scripts/tests/rollback-k3s.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROLLBACK="$HERE/../rollback-k3s.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# fresh_world <pre-deploy record…> — lines like "php 3".
fresh_world() {
  rm -rf "$work/kube" "$work/state" "$work/backups"
  mkdir -p "$work/kube" "$work/state" "$work/backups"
  : > "$work/kube/calls"
  if [ "$#" -gt 0 ]; then
    printf '%s\n' "$@" > "$work/state/pre-deploy-revisions"
  fi
}

run_rollback() {
  OUTPUT="$(FAKE_KUBECTL_DIR="$work/kube" KUBECTL="$HERE/fake-kubectl.sh" \
    MAGGIE_STATE_DIR="$work/state" MAGGIE_BACKUP_DIR="$work/backups" "$ROLLBACK" 2>&1)"
  STATUS=$?
}

undone() { grep -c '^rollout undo' "$work/kube/calls" || true; }

printf '\n\033[1mNo record: the deploy died before the apply\033[0m\n'
fresh_world
echo 7 > "$work/kube/rev-php"
run_rollback
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(undone)" -eq 0 ] && ok "undoes nothing: a stale record must never be guessed" || bad "undid something without a record"

printf '\n\033[1mOnly what the deploy moved is undone\033[0m\n'
fresh_world "php 3" "agent 2" "nginx 5"
echo 4 > "$work/kube/rev-php"     # moved by the deploy
echo 2 > "$work/kube/rev-agent"   # untouched
echo 6 > "$work/kube/rev-nginx"   # moved by the deploy
run_rollback
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
grep -qF 'rollout undo deployment/php -n maggie --to-revision=3' "$work/kube/calls" \
  && ok "php goes back to the exact revision it had" || bad "php was not restored to revision 3"
grep -qF 'rollout undo deployment/nginx -n maggie --to-revision=5' "$work/kube/calls" \
  && ok "nginx goes back to the exact revision it had" || bad "nginx was not restored to revision 5"
grep -qF 'rollout undo deployment/agent' "$work/kube/calls" \
  && bad "agent was undone although this deploy never changed it" || ok "agent, untouched, is left alone"
[ -e "$work/state/pre-deploy-revisions" ] \
  && bad "the record survives: a second run would undo again" || ok "the record is consumed"

printf '\n\033[1mA second run does nothing\033[0m\n'
before="$(undone)"
run_rollback
[ "$(undone)" -eq "$before" ] && ok "no further undo" || bad "a second run undid again"

printf '\n\033[1mA failed undo fails the rollback\033[0m\n'
fresh_world "php 3"
echo 4 > "$work/kube/rev-php"
touch "$work/kube/fail-undo-php"
run_rollback
[ "$STATUS" -ne 0 ] && ok "exits non-zero, so the workflow says so" || bad "exit 0 although php could not be restored"
[ -e "$work/state/pre-deploy-revisions" ] && ok "the record is kept so the rollback can be retried" || bad "the record was consumed by a failed rollback"

printf '\n\033[1mIt says what it cannot undo\033[0m\n'
fresh_world "php 3"
echo 4 > "$work/kube/rev-php"
touch "$work/backups/maggie_predeploy_20260930_120000.sql.gz"
run_rollback
printf '%s' "$OUTPUT" | grep -qF 'NOT reverted' && ok "migrations are flagged as not reverted" || bad "no warning about migrations"
printf '%s' "$OUTPUT" | grep -qF 'maggie_predeploy_20260930_120000.sql.gz' && ok "the pre-deploy dump is named" || bad "the dump is not named"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf 'All cases pass.\n'

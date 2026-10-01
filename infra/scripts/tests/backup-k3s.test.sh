#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/backup-k3s.sh against a fake kubectl (MAG-188).
#
# The pre-deploy backup must cover both databases — the API's and the agent's
# (memory, messages, contexts, directives, proactions, personality) — and stop the
# deploy when either dump is empty.
#
# Usage: infra/scripts/tests/backup-k3s.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP="$HERE/../backup-k3s.sh"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# fresh_world: both secrets set, both databases with content.
fresh_world() {
  rm -rf "$work/kube" "$work/backups"
  mkdir -p "$work/kube" "$work/backups"
  : > "$work/kube/calls"
  echo "pg-0" > "$work/kube/pods-postgres"
  printf 'postgresql://maggie:s3cret@postgres.shared.svc:5432/maggie?serverVersion=16' > "$work/kube/secret-DATABASE_URL"
  printf 'postgresql://maggie:s3cret@postgres.shared.svc:5432/maggie_agent' > "$work/kube/secret-AGENT_DATABASE_URL"
  echo "-- api dump" > "$work/kube/dump-maggie"
  echo "-- agent dump" > "$work/kube/dump-maggie_agent"
}

run_backup() {
  OUTPUT="$(FAKE_KUBECTL_DIR="$work/kube" KUBECTL="$HERE/fake-kubectl.sh" \
    MAGGIE_BACKUP_DIR="$work/backups" "$BACKUP" 2>&1)"
  STATUS=$?
}

count() { find "$work/backups" -name "$1" | wc -l; }
content() { gzip -dc "$(find "$work/backups" -name "$1" | head -1)"; }

printf '\n\033[1mBoth databases are dumped, one file each\033[0m\n'
fresh_world
run_backup
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(count 'maggie_predeploy_*.sql.gz')" -eq 1 ] && ok "one API dump" || bad "no single API dump"
[ "$(count 'maggie_agent_predeploy_*.sql.gz')" -eq 1 ] && ok "one agent dump" || bad "no single agent dump"
[ "$(content 'maggie_predeploy_*.sql.gz')" = "-- api dump" ] && ok "the API dump holds the maggie database" || bad "wrong API dump content"
[ "$(content 'maggie_agent_predeploy_*.sql.gz')" = "-- agent dump" ] && ok "the agent dump holds the maggie_agent database" || bad "wrong agent dump content"
api_stamp="$(basename "$(find "$work/backups" -name 'maggie_predeploy_*')" .sql.gz)"
agent_stamp="$(basename "$(find "$work/backups" -name 'maggie_agent_predeploy_*')" .sql.gz)"
[ "${api_stamp#maggie_predeploy_}" = "${agent_stamp#maggie_agent_predeploy_}" ] \
  && ok "both files carry the same timestamp" || bad "timestamps differ: $api_stamp / $agent_stamp"

printf '\n\033[1mAn empty API dump stops the deploy\033[0m\n'
fresh_world
: > "$work/kube/dump-maggie"
run_backup
[ "$STATUS" -ne 0 ] && ok "exits non-zero" || bad "exit 0 with an empty API dump"
[ "$(count '*.sql.gz')" -eq 0 ] && ok "no dump is left behind" || bad "a dump file survives"

printf '\n\033[1mAn empty agent dump stops the deploy\033[0m\n'
fresh_world
: > "$work/kube/dump-maggie_agent"
run_backup
[ "$STATUS" -ne 0 ] && ok "exits non-zero" || bad "exit 0 with an empty agent dump"
printf '%s' "$OUTPUT" | grep -qF 'AGENT_DATABASE_URL' && ok "the message names the agent database" || bad "the message does not say which dump failed — $OUTPUT"
[ "$(count 'maggie_agent_predeploy_*.sql.gz')" -eq 0 ] && ok "the empty dump is removed" || bad "the empty agent dump survives"

printf '\n\033[1mA missing AGENT_DATABASE_URL stops the deploy\033[0m\n'
fresh_world
rm "$work/kube/secret-AGENT_DATABASE_URL"
run_backup
[ "$STATUS" -ne 0 ] && ok "exits non-zero" || bad "exit 0 without AGENT_DATABASE_URL"
printf '%s' "$OUTPUT" | grep -qF 'AGENT_DATABASE_URL' && ok "the message names the missing key" || bad "no message about the key — $OUTPUT"

printf '\n\033[1mRotation keeps the last 10 of each database\033[0m\n'
fresh_world
for i in $(seq -w 1 12); do
  touch -d "2026-09-$((10#$i + 10)) 12:00" \
    "$work/backups/maggie_predeploy_202609${i}_120000.sql.gz" \
    "$work/backups/maggie_agent_predeploy_202609${i}_120000.sql.gz"
done
run_backup
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
[ "$(count 'maggie_predeploy_*.sql.gz')" -eq 10 ] && ok "10 API dumps kept" || bad "API dumps: $(count 'maggie_predeploy_*.sql.gz')"
[ "$(count 'maggie_agent_predeploy_*.sql.gz')" -eq 10 ] && ok "10 agent dumps kept" || bad "agent dumps: $(count 'maggie_agent_predeploy_*.sql.gz')"
[ -e "$work/backups/maggie_agent_predeploy_20260901_120000.sql.gz" ] \
  && bad "the oldest agent dump is still there" || ok "the oldest ones went first"

printf '\n\033[1mA failed run does not rotate\033[0m\n'
fresh_world
for i in $(seq -w 1 10); do
  touch "$work/backups/maggie_predeploy_202609${i}_120000.sql.gz"
done
: > "$work/kube/dump-maggie_agent"
run_backup
[ "$(count 'maggie_predeploy_*.sql.gz')" -ge 10 ] && ok "older API dumps are kept" || bad "a failed run ate older dumps"

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '\033[31m%d failing case(s)\033[0m\n' "$failures"
  exit 1
fi
printf 'All cases pass.\n'

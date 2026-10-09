#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/migrate-k3s.sh against a fake kubectl (MAG-360).
#
# `Meal` extends `Event` (joined inheritance): every query on events reads the
# `meal` table. Migrating after the pods roll to the new image left a window in
# which the cron's check-reminders and the API selected a column that did not
# exist yet (cron exit status 7 in GlitchTip). The migrations now run in a
# one-shot job on the NEW image, before the manifests are applied, and a failed
# migration stops the deploy.
#
# Usage: infra/scripts/tests/migrate-k3s.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATE="$HERE/../migrate-k3s.sh"
IMAGE="ghcr.io/miwi35/maggie-php:sha-abc1234"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# fresh_world <job status> — "1/" succeeded, "/1" failed, "/" still running.
fresh_world() {
  rm -rf "$work/kube"
  mkdir -p "$work/kube"
  : > "$work/kube/calls"
  printf '%s' "$1" > "$work/kube/job-status"
}

run_migrate() {
  OUTPUT="$(FAKE_KUBECTL_DIR="$work/kube" KUBECTL="$HERE/fake-kubectl.sh" \
    MIGRATE_TIMEOUT=2 MIGRATE_POLL=1 "$MIGRATE" "$@" 2>&1)"
  STATUS=$?
}

printf '\n\033[1mThe migrations run on the new image, before anything else\033[0m\n'
fresh_world "1/"
run_migrate "$IMAGE"
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
grep -qF "image: $IMAGE" "$work/kube/applied" 2>/dev/null \
  && ok "the job runs the image it was given" || bad "the applied job does not use $IMAGE"
grep -q '__IMAGE__' "$work/kube/applied" 2>/dev/null \
  && bad "the image placeholder was left in the manifest" || ok "no placeholder left"
grep -q 'migrations:migrate' "$work/kube/applied" 2>/dev/null \
  && ok "the job migrates the database" || bad "the job does not run doctrine:migrations:migrate"
grep -q 'dedupe-google-agendas.*migrations:migrate' "$work/kube/applied" 2>/dev/null \
  && ok "agendas are deduplicated before the migrations (MAG-148)" || bad "the dedupe does not precede the migrations"
first_apply="$(grep -n '^apply' "$work/kube/calls" | head -1 | cut -d: -f1)"
first_delete="$(grep -n '^delete job' "$work/kube/calls" | head -1 | cut -d: -f1)"
[ -n "$first_delete" ] && [ -n "$first_apply" ] && [ "$first_delete" -lt "$first_apply" ] \
  && ok "a job left by a previous deploy is removed first" || bad "no cleanup of a previous job before the apply"

printf '\n\033[1mA failed migration stops the deploy\033[0m\n'
fresh_world "/1"
run_migrate "$IMAGE"
[ "$STATUS" -ne 0 ] && ok "exits non-zero" || bad "exit 0 although the migration failed"
grep -q '^logs' "$work/kube/calls" && ok "the job's logs are shown" || bad "the logs of the failed job were not fetched"

printf '\n\033[1mA job that never finishes fails too\033[0m\n'
fresh_world "/"
run_migrate "$IMAGE"
[ "$STATUS" -ne 0 ] && ok "exits non-zero after the timeout" || bad "exit 0 although the job never completed"

printf '\n\033[1mThe deploy migrates before it records revisions and applies\033[0m\n'
DEPLOY="$HERE/../deploy-k3s.sh"
line_of() { grep -n "$1" "$DEPLOY" | head -1 | cut -d: -f1; }
m="$(line_of 'migrate-k3s.sh')"; r="$(line_of 'Recorded pre-deploy revisions')"; a="$(line_of 'apply -k "\$KUSTOMIZE_DIR"')"
[ -n "$m" ] && [ -n "$r" ] && [ -n "$a" ] && [ "$m" -lt "$r" ] && [ "$r" -lt "$a" ] \
  && ok "migrate-k3s.sh runs before the revision record and the apply" || bad "deploy-k3s.sh does not migrate first (migrate=$m record=$r apply=$a)"

printf '\n\033[1mNo image, no migration\033[0m\n'
fresh_world "1/"
run_migrate
[ "$STATUS" -ne 0 ] && ok "exits non-zero without an image" || bad "exit 0 without an image"
[ ! -s "$work/kube/calls" ] && ok "the cluster is not touched" || bad "kubectl was called without an image"

echo
[ "$failures" -eq 0 ] && { printf '\033[32mAll good\033[0m\n'; exit 0; } || { printf '\033[31m%d failure(s)\033[0m\n' "$failures"; exit 1; }

#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/deploy-k3s.sh against a fake kubectl and docker (MAG-361).
#
# What matters is the ORDER of the calls. The migrations must be applied, with
# the image about to be deployed, before the new pods start serving: an entity
# that selects a column the schema does not have yet fails every query that
# reads it (Meal is a joined child of Event, so every event read), and that is
# what the first deploy of a migration did in production.
#
# Usage: infra/scripts/tests/deploy-k3s.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$HERE/../deploy-k3s.sh"
K8S="$HERE/../../k8s"
failures=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# fresh_world: shared services up, secrets set, a kustomization to rewrite.
fresh_world() {
  rm -rf "${work:?}/kube" "${work:?}/state" "${work:?}/backups" "${work:?}/k8s" "${work:?}/bin"
  mkdir -p "$work/kube" "$work/state" "$work/backups" "$work/bin"
  cp -r "$K8S" "$work/k8s"
  ln -s "$HERE/fake-docker.sh" "$work/bin/docker"
  : > "$work/kube/calls"
  echo "pg-0" > "$work/kube/pods-postgres"
  printf 'postgresql://maggie:s3cret@postgres.shared.svc:5432/maggie?serverVersion=16' > "$work/kube/secret-DATABASE_URL"
  printf 'postgresql://maggie:s3cret@postgres.shared.svc:5432/maggie_agent' > "$work/kube/secret-AGENT_DATABASE_URL"
  echo "-- api dump" > "$work/kube/dump-maggie"
  echo "-- agent dump" > "$work/kube/dump-maggie_agent"
  echo 3 > "$work/kube/rev-php"
}

run_deploy() {
  OUTPUT="$(PATH="$work/bin:$PATH" FAKE_DOCKER_TAGS="${TAGS_ON_GHCR:-}" \
    FAKE_KUBECTL_DIR="$work/kube" KUBECTL="$HERE/fake-kubectl.sh" \
    MAGGIE_STATE_DIR="$work/state" MAGGIE_BACKUP_DIR="$work/backups" \
    MAGGIE_KUSTOMIZE_DIR="$work/k8s" MAGGIE_HEALTH_URL="http://127.0.0.1:9/" \
    MAGGIE_POLL_SECONDS=0.1 MAGGIE_MIGRATE_TIMEOUT="${MIGRATE_TIMEOUT:-5}" \
    "$DEPLOY" "$1" 2>&1)"
  STATUS=$?
}

# line_of <pattern>: line number of the first call matching, empty when absent.
line_of() { grep -n -m1 -E "$1" "$work/kube/calls" | cut -d: -f1; }

printf '\n\033[1mThe migrations run, with the new image, before the new pods\033[0m\n'
fresh_world
TAGS_ON_GHCR="sha-abc" run_deploy sha-abc
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
migrate="$(line_of '^run migrate ')"
apply="$(line_of '^apply -k')"
[ -n "$migrate" ] && ok "a one-shot migration pod runs" || bad "no migration pod was run"
[ -n "$apply" ] && [ -n "$migrate" ] && [ "$migrate" -lt "$apply" ] \
  && ok "it runs before the manifests are applied" || bad "migrations at call '$migrate', apply at call '$apply'"
grep -qE '^run migrate .*--image=ghcr.io/miwi35/maggie-php:sha-abc ' "$work/kube/calls" \
  && ok "it runs the php image being deployed, not the one being replaced" || bad "wrong migration image: $(grep '^run migrate' "$work/kube/calls")"
grep -qE '^run migrate .*--command -- bin/console doctrine:migrations:migrate --no-interaction' "$work/kube/calls" \
  && ok "it runs doctrine:migrations:migrate" || bad "wrong migration command"
grep -qE '^run migrate .*maggie-env' "$work/kube/calls" \
  && ok "it reads the application environment" || bad "the migration pod has no maggie-env"
grep -qE '^exec deployment/php .*doctrine:migrations:migrate' "$work/kube/calls" \
  && bad "migrations also run in the php pod, after the rollout" || ok "migrations are no longer run after the rollout"

printf '\n\033[1mThe agenda repair still comes before the migrations\033[0m\n'
dedupe="$(line_of 'dedupe-google-agendas')"
[ -n "$dedupe" ] && [ -n "$migrate" ] && [ "$dedupe" -lt "$migrate" ] \
  && ok "dedupe-google-agendas runs first" || bad "dedupe at call '$dedupe', migrations at call '$migrate'"

printf '\n\033[1mA php image not rebuilt migrates with the digest it runs\033[0m\n'
fresh_world
echo "ghcr.io/miwi35/maggie-php@sha256:aaaa" > "$work/kube/pods-php"
TAGS_ON_GHCR="" run_deploy sha-abc
[ "$STATUS" -eq 0 ] && ok "exits 0" || bad "exit $STATUS — $OUTPUT"
grep -qE '^run migrate .*--image=ghcr.io/miwi35/maggie-php@sha256:aaaa ' "$work/kube/calls" \
  && ok "the migration pod uses the pinned digest" || bad "wrong migration image: $(grep '^run migrate' "$work/kube/calls")"

printf '\n\033[1mThe migration pod is well formed\033[0m\n'
fresh_world
TAGS_ON_GHCR="sha-abc" run_deploy sha-abc
overrides="$(grep '^run migrate ' "$work/kube/calls" | sed -n 's/.* --overrides=\(.*\) --command .*/\1/p')"
printf '%s' "$overrides" | python3 -c '
import json, sys
spec = json.load(sys.stdin)["spec"]
container = spec["containers"][0]
assert container["name"] == "migrate", container["name"]
assert spec["imagePullSecrets"] == [{"name": "ghcr-pull"}]
refs = [e.get("secretRef", {}).get("name") for e in container["envFrom"]]
assert "maggie-env" in refs, refs
' && ok "valid overrides: container named like the pod, pull secret, maggie-env" || bad "bad overrides: $overrides"
grep -qE '^run migrate -n maggie --restart=Never ' "$work/kube/calls" \
  && ok "a plain pod, its phase is read afterwards" || bad "wrong run flags: $(grep '^run migrate' "$work/kube/calls")"
[ -n "$(line_of '^delete pod migrate ')" ] && ok "the pod is removed once read" || bad "the migration pod is left behind"

printf '\n\033[1mNo tag and no running digest: the php image is latest\033[0m\n'
fresh_world
TAGS_ON_GHCR="" run_deploy sha-abc
grep -qE '^run migrate .*--image=ghcr.io/miwi35/maggie-php:latest ' "$work/kube/calls" \
  && ok "the migration pod uses latest, as the manifests will" || bad "wrong migration image: $(grep '^run migrate' "$work/kube/calls")"

printf '\n\033[1mA failed migration stops the deploy before anything changes\033[0m\n'
fresh_world
echo Failed > "$work/kube/migrate-phase"
TAGS_ON_GHCR="sha-abc" run_deploy sha-abc
[ "$STATUS" -ne 0 ] && ok "exits non-zero although kubectl run itself returned 0" || bad "exit 0 although the migration pod failed"
[ -z "$(line_of '^apply -k')" ] && ok "the new pods are never rolled out" || bad "manifests applied after a failed migration"
[ -e "$work/state/pre-deploy-revisions" ] \
  && bad "a rollback record was left for a deploy that changed nothing" || ok "no rollback record: nothing to undo"
grep -qF 'migration output' <<<"$OUTPUT" && ok "the migration log is in the deploy log" || bad "the migration log is lost"

printf '\n\033[1mA migration pod that cannot be created stops the deploy\033[0m\n'
fresh_world
touch "$work/kube/fail-run"
TAGS_ON_GHCR="sha-abc" run_deploy sha-abc
[ "$STATUS" -ne 0 ] && ok "exits non-zero" || bad "exit 0 although no migration ran"
[ -z "$(line_of '^apply -k')" ] && ok "the new pods are never rolled out" || bad "manifests applied without migrating"

printf '\n\033[1mA migration that never ends stops the deploy\033[0m\n'
fresh_world
echo Running > "$work/kube/migrate-phase"
TAGS_ON_GHCR="sha-abc" MIGRATE_TIMEOUT=1 run_deploy sha-abc
[ "$STATUS" -ne 0 ] && ok "exits non-zero after the timeout" || bad "exit 0 with a migration still running"
[ -z "$(line_of '^apply -k')" ] && ok "the new pods are never rolled out" || bad "manifests applied with a migration still running"
[ -n "$(line_of '^delete pod migrate ')" ] && ok "the pod is removed" || bad "the migration pod is left behind"

[ "$failures" -eq 0 ] && printf '\n\033[32mAll good\033[0m\n' || printf '\n\033[31m%d failure(s)\033[0m\n' "$failures"
exit "$((failures > 0))"

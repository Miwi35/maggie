#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Maggie pre-rollout migration (MAG-360)
# Usage: scripts/migrate-k3s.sh <php-image>
#
# Run by deploy-k3s.sh before the manifests are applied. `Meal` extends `Event`
# (joined inheritance), so the cron and the API read the `meal` table on every
# event query: pods on the new image with the old schema fail with an undefined
# column. The migrations therefore run first, in a one-shot job on the NEW
# image, while the old pods keep serving. A migration must stay compatible with
# the release still running (additive; drop in a later release).
# A failed or unfinished migration exits non-zero: the deploy stops, nothing
# has rolled.
# =============================================================================

IMAGE="${1:?Usage: migrate-k3s.sh <php-image>}"
NAMESPACE="maggie"
KUBECTL="${KUBECTL:-sudo k3s kubectl}"
TEMPLATE="${MIGRATE_TEMPLATE:-$(dirname "${BASH_SOURCE[0]}")/../k8s/jobs/migrate.yaml}"
TIMEOUT="${MIGRATE_TIMEOUT:-300}"
POLL="${MIGRATE_POLL:-3}"

log()  { echo "==> $*"; }
fail() { echo "FATAL: $*" >&2; exit 1; }

$KUBECTL delete job migrate -n "$NAMESPACE" --ignore-not-found >/dev/null

log "Migrating the database with $IMAGE..."
sed "s#__IMAGE__#$IMAGE#" "$TEMPLATE" | $KUBECTL apply -f - -n "$NAMESPACE"

waited=0
while :; do
  # "<succeeded>/<failed>": a counter appears in the status once it is non-zero.
  status=$($KUBECTL get job migrate -n "$NAMESPACE" -o 'jsonpath={.status.succeeded}/{.status.failed}' || true)
  case "$status" in
    1/*)
      $KUBECTL logs job/migrate -n "$NAMESPACE" || true
      log "Migrations done."
      exit 0
      ;;
    */[1-9]*)
      $KUBECTL logs job/migrate -n "$NAMESPACE" || true
      fail "The migration job failed. Nothing was rolled out."
      ;;
  esac
  if [ "$waited" -ge "$TIMEOUT" ]; then
    $KUBECTL logs job/migrate -n "$NAMESPACE" || true
    $KUBECTL delete job migrate -n "$NAMESPACE" --ignore-not-found >/dev/null || true
    fail "The migration job did not finish within ${TIMEOUT}s. Nothing was rolled out."
  fi
  sleep "$POLL"
  waited=$((waited + POLL))
done

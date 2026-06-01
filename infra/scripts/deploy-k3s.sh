#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Maggie K3s Deploy Script
# Usage: scripts/deploy-k3s.sh <image-tag>
# Example: scripts/deploy-k3s.sh sha-abc1234
#
# Philosophy mirrored from hilo/scripts/deploy-k3s.sh:
#   1. Preflight   — shared services up
#   2. Backup      — pg_dump before any change
#   3. Apply       — bump image tags in kustomization, kubectl apply -k
#   4. Wait        — rollout status on every Maggie deployment
#   5. Post-deploy — migrations, cache:clear, ES mapping + reindex
#   6. Verify      — pod list + healthcheck
# =============================================================================

TAG="${1:?Usage: deploy-k3s.sh <image-tag>}"
NAMESPACE="maggie"
SHARED_NS="shared"
KUSTOMIZE_DIR="/opt/maggie/infra/k8s"
BACKUP_DIR="/opt/maggie/backups"
KUBECTL="${KUBECTL:-sudo k3s kubectl}"
HEALTH_URL="https://maggieai.fr/api/docs"

# GHCR images managed by Kustomize
IMAGES=(
  "ghcr.io/miwi35/maggie-php"
  "ghcr.io/miwi35/maggie-nginx"
  "ghcr.io/miwi35/maggie-agent"
  "ghcr.io/miwi35/maggie-ciqual"
)

# Deployments to wait for rollout
DEPLOYMENTS=(php nginx worker cron agent ciqual mercure)

log()  { echo "==> $*"; }
warn() { echo "WARNING: $*" >&2; }
fail() { echo "FATAL: $*" >&2; exit 1; }

# === PHASE 1 : PREFLIGHT ===
log "Phase 1: Preflight checks..."

$KUBECTL get nodes >/dev/null 2>&1 || fail "kubectl not available"

for svc in postgres elasticsearch rabbitmq; do
  if ! $KUBECTL get deployment "$svc" -n "$SHARED_NS" -o jsonpath='{.status.readyReplicas}' 2>/dev/null | grep -q '[1-9]'; then
    fail "Shared service $svc is not ready in namespace $SHARED_NS"
  fi
done
log "All shared services are ready."

# === PHASE 2 : BACKUP ===
log "Phase 2: Backing up database..."

mkdir -p "$BACKUP_DIR"
DUMP_FILE="$BACKUP_DIR/maggie_predeploy_$(date +%Y%m%d_%H%M%S).sql.gz"

DB_URL=$($KUBECTL get secret maggie-env -n "$NAMESPACE" -o jsonpath='{.data.DATABASE_URL}' | base64 -d)
DB_USER=$(echo "$DB_URL" | sed -n 's|.*://\([^:]*\):.*|\1|p')
DB_PASS=$(echo "$DB_URL" | sed -n 's|.*://[^:]*:\([^@]*\)@.*|\1|p')
DB_NAME=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')

PG_POD=$($KUBECTL get pod -n "$SHARED_NS" -l app=postgres -o jsonpath='{.items[0].metadata.name}')
$KUBECTL exec -n "$SHARED_NS" "$PG_POD" -- sh -c "PGPASSWORD='$DB_PASS' pg_dump -U '$DB_USER' -d '$DB_NAME'" 2>/dev/null | gzip > "$DUMP_FILE"

if [ ! -s "$DUMP_FILE" ]; then
  rm -f "$DUMP_FILE"
  fail "Database backup is empty or failed"
fi

log "Backup saved: $DUMP_FILE ($(du -h "$DUMP_FILE" | cut -f1))"

# Rotation: keep last 10
ls -1t "$BACKUP_DIR"/maggie_*.sql.gz 2>/dev/null | tail -n +11 | xargs -r rm --

# === PHASE 3 : APPLY MANIFESTS ===
log "Phase 3: Updating image tags to $TAG (only when image exists on GHCR)..."

# Per-service build jobs only run when their scope changed, so a commit that
# touches only one service produces a sha-tagged image for that service alone.
# Updating every image's newTag to that sha would point the others at a
# nonexistent tag → ImagePullBackOff. Skip the update when the tag is missing
# (those images keep their previous tag).

image_tag_exists() {
  if ! command -v docker >/dev/null 2>&1; then
    return 0
  fi
  docker manifest inspect "$1:$2" >/dev/null 2>&1
}

for image in "${IMAGES[@]}"; do
  if image_tag_exists "$image" "$TAG"; then
    sed -i "/name: ${image//\//\\/}$/{n;s|newTag:.*|newTag: ${TAG}|;}" "$KUSTOMIZE_DIR/kustomization.yaml"
    log "  $image → $TAG"
  else
    log "  $image → (skipped — tag $TAG not on GHCR)"
  fi
done

log "Applying manifests..."
$KUBECTL apply -k "$KUSTOMIZE_DIR"

# === PHASE 4 : WAIT ROLLOUT ===
log "Phase 4: Waiting for rollouts to complete..."

ROLLOUT_FAILED=0
for deploy in "${DEPLOYMENTS[@]}"; do
  if ! $KUBECTL get "deployment/$deploy" -n "$NAMESPACE" >/dev/null 2>&1; then
    continue
  fi
  log "  Waiting for $deploy..."
  if ! $KUBECTL rollout status "deployment/$deploy" -n "$NAMESPACE" --timeout=180s; then
    warn "Rollout failed for $deploy — fetching logs:"
    POD=$($KUBECTL get pod -n "$NAMESPACE" -l "app=$deploy" --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1].metadata.name}' 2>/dev/null || true)
    if [ -n "$POD" ]; then
      $KUBECTL logs -n "$NAMESPACE" "$POD" --tail=30 2>/dev/null || true
    fi
    ROLLOUT_FAILED=1
  fi
done

if [ "$ROLLOUT_FAILED" -eq 1 ]; then
  fail "One or more rollouts failed. Aborting post-deploy tasks."
fi

log "All rollouts complete."

# === PHASE 5 : POST-DEPLOY TASKS ===
log "Phase 5a: Running migrations..."
$KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console doctrine:migrations:migrate --no-interaction --allow-no-migration

log "Phase 5b: Clearing cache..."
$KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console cache:clear

log "Phase 5c: Updating Elasticsearch mappings..."
if ! $KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console app:elasticsearch:mapping:update --all --no-interaction; then
  warn "ES mapping update failed (non-blocking). Run manually: kubectl exec deployment/php -n $NAMESPACE -- bin/console app:elasticsearch:mapping:update --all"
fi

log "Phase 5d: Reindexing Elasticsearch..."
if ! $KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console app:elasticsearch:reindex --all --no-interaction; then
  warn "ES reindex failed (non-blocking). Run manually: kubectl exec deployment/php -n $NAMESPACE -- bin/console app:elasticsearch:reindex --all"
fi

# === PHASE 6 : VERIFICATION ===
log "Phase 6: Final verification..."

echo ""
$KUBECTL get pods -n "$NAMESPACE"
echo ""

if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
  log "Health check OK: $HEALTH_URL"
else
  warn "Health check failed: $HEALTH_URL (may need a moment to warm up)"
fi

log "Deploy $TAG complete."

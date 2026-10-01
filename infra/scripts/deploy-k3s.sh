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
#   5. Post-deploy — data repairs, migrations, ES mapping + reindex
#   6. Verify      — pod list + healthcheck
#
# The revisions the deployments had before phase 3 are recorded for
# rollback-k3s.sh, which the CD workflow runs if the deploy or the post-deploy
# smoke suite fails (MAG-106).
# =============================================================================

TAG="${1:?Usage: deploy-k3s.sh <image-tag>}"
NAMESPACE="maggie"
SHARED_NS="shared"
KUSTOMIZE_DIR="/opt/maggie/infra/k8s"
BACKUP_DIR="${MAGGIE_BACKUP_DIR:-/opt/maggie/backups}"
KUBECTL="${KUBECTL:-sudo k3s kubectl}"
HEALTH_URL="https://maggieai.fr/api/docs"
STATE_DIR="${MAGGIE_STATE_DIR:-/opt/maggie/state}"
REVISIONS_FILE="$STATE_DIR/pre-deploy-revisions"
DIGESTS_FILE="$STATE_DIR/pre-deploy-digests"

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

# A record left by the previous deploy describes a world that no longer exists:
# if this run dies before writing its own, rolling back with it would undo a
# deploy that was fine. No record means nothing to roll back.
mkdir -p "$STATE_DIR"
rm -f "$REVISIONS_FILE" "$DIGESTS_FILE"

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
# nonexistent tag → ImagePullBackOff.
#
# Those other images must not fall back to the `latest` the repo ships either:
# the manifests are copied from the repo on every deploy, so a service that is
# not rebuilt would be re-pinned to a mobile tag. With imagePullPolicy: Always
# that means a restarted pod silently picks up whatever `latest` points at, and
# `kubectl rollout undo` rolls back to the very same tag. Instead we pin such a
# service to the digest it is running right now: immutable, so restarts and
# rollbacks land on known code.

# One deployment per image, to read back the digest actually running.
image_deployment() {
  case "$1" in
    *maggie-php) echo "php" ;;
    *maggie-nginx) echo "nginx" ;;
    *maggie-agent) echo "agent" ;;
    *maggie-ciqual) echo "ciqual" ;;
    *) echo "" ;;
  esac
}

image_tag_exists() {
  if ! command -v docker >/dev/null 2>&1; then
    return 0
  fi
  docker manifest inspect "$1:$2" >/dev/null 2>&1
}

# Digest of the image the deployment's pod is running, as repo@sha256:…
running_digest() {
  local deploy image image_id
  deploy=$(image_deployment "$1")
  image="$1"

  [ -n "$deploy" ] || return 0

  image_id=$($KUBECTL get pod -n "$NAMESPACE" -l "app=$deploy" \
    --field-selector=status.phase=Running \
    -o jsonpath='{.items[0].status.containerStatuses[0].imageID}' 2>/dev/null || true)

  case "$image_id" in
    "$image"@sha256:*) echo "${image_id#*@}" ;;
    *) return 0 ;;
  esac
}

set_image_field() {
  # Rewrites the `newTag:`/`digest:` line right after `name: <image>`, keeping
  # its indentation. The two fields are mutually exclusive in Kustomize, so one
  # replaces the other rather than being added next to it.
  local image="$1" field="$2" value="$3"
  sed -i "/name: ${image//\//\\/}$/{n;s#^\([[:space:]]*\)[A-Za-z]*:.*#\1${field}: ${value}#;}" "$KUSTOMIZE_DIR/kustomization.yaml"
}

# What each image runs before anything changes: verify-digests.sh holds every
# service not rebuilt by this deploy to it.
: > "$DIGESTS_FILE.tmp"
for image in "${IMAGES[@]}"; do
  before=$(running_digest "$image")
  if [ -n "$before" ]; then
    echo "$image $before" >> "$DIGESTS_FILE.tmp"
  fi
done
mv "$DIGESTS_FILE.tmp" "$DIGESTS_FILE"

for image in "${IMAGES[@]}"; do
  if image_tag_exists "$image" "$TAG"; then
    set_image_field "$image" "newTag" "$TAG"
    log "  $image → $TAG"
  else
    digest=$(running_digest "$image")
    if [ -n "$digest" ]; then
      set_image_field "$image" "digest" "$digest"
      log "  $image → pinned to running ${digest:0:26}… (tag $TAG not on GHCR)"
    else
      warn "  $image → left at latest (tag $TAG not on GHCR, no running digest found)"
    fi
  fi
done

# Revision of every deployment right before the apply. `rollout undo` alone
# would be wrong: a deployment this deploy did not touch has no new revision, so
# undoing it would send it back to an older release than the one it serves.
# Recording lets the rollback restore exactly the ones that moved.
: > "$REVISIONS_FILE.tmp"
for deploy in "${DEPLOYMENTS[@]}"; do
  revision=$($KUBECTL get "deployment/$deploy" -n "$NAMESPACE" \
    -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}' 2>/dev/null || true)
  if [ -n "$revision" ]; then
    echo "$deploy $revision" >> "$REVISIONS_FILE.tmp"
  fi
done
mv "$REVISIONS_FILE.tmp" "$REVISIONS_FILE"
log "Recorded pre-deploy revisions: $(tr '\n' ' ' < "$REVISIONS_FILE")"

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
# Must run before the migrations: the unique index on (user_id,
# google_calendar_id) cannot be created while a user still holds two agendas
# for the same Google calendar (MAG-148). A no-op once that index exists, and
# safe to run again, so it stays here rather than being a one-off by hand.
#
# Running before the migrations means it sees the previous schema. It is built
# for that — with nothing to merge it answers from plain SQL and never loads an
# entity — and that is what keeps it safe to leave here. A repair step added
# later that does touch entities belongs after the migrations instead.
log "Phase 5a: Merging agendas that share a Google calendar..."
$KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console app:calendar:dedupe-google-agendas --no-interaction

log "Phase 5b: Running migrations..."
$KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console doctrine:migrations:migrate --no-interaction --allow-no-migration

# No cache:clear here (MAG-146): the image ships a warmed cache, and deleting
# var/cache/prod under a pod that is serving requests fails at random.

log "Phase 5c: Updating Elasticsearch mappings..."
if ! $KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console app:elasticsearch:mapping:update --all --no-interaction; then
  warn "ES mapping update failed (non-blocking). Run manually: kubectl exec deployment/php -n $NAMESPACE -- bin/console app:elasticsearch:mapping:update --all"
fi

log "Phase 5d: Reindexing Elasticsearch..."
if ! $KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console app:elasticsearch:reindex --all --no-interaction; then
  warn "ES reindex failed (non-blocking). Run manually: kubectl exec deployment/php -n $NAMESPACE -- bin/console app:elasticsearch:reindex --all"
fi

# Google keeps pushing to the address a channel was created with (MAG-193), and
# only the expiry is stored, so every deploy replaces the channels with ones
# for the address this release is configured with. Safe to repeat; the 5-minute
# cron covers the instant between the old channel and the new one.
log "Phase 5e: Recreating the Google push channels..."
if ! $KUBECTL exec "deployment/php" -n "$NAMESPACE" -- bin/console maggie:google-calendar:renew-watch --all --no-interaction; then
  warn "Google watch channels not recreated (non-blocking). Run manually: kubectl exec deployment/php -n $NAMESPACE -- bin/console maggie:google-calendar:renew-watch --all"
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

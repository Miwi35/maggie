#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Maggie pre-deploy backup (MAG-188)
# Usage: scripts/backup-k3s.sh
#
# Run by deploy-k3s.sh (phase 2). Dumps BOTH databases of the maggie-env secret,
# each into its own timestamped file:
#   DATABASE_URL        → maggie_predeploy_<ts>.sql.gz        (API)
#   AGENT_DATABASE_URL  → maggie_agent_predeploy_<ts>.sql.gz  (memory, messages,
#                                                              contexts, directives,
#                                                              proactions, personality)
# An empty or failed dump aborts with a non-zero exit, so the deploy stops
# before anything changes. Each prefix keeps its last 10 files.
# =============================================================================

NAMESPACE="maggie"
SHARED_NS="shared"
BACKUP_DIR="${MAGGIE_BACKUP_DIR:-/opt/maggie/backups}"
KUBECTL="${KUBECTL:-sudo k3s kubectl}"
KEEP=10

log()  { echo "==> $*"; }
fail() { echo "FATAL: $*" >&2; exit 1; }

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"
PG_POD=$($KUBECTL get pod -n "$SHARED_NS" -l app=postgres -o jsonpath='{.items[0].metadata.name}')
[ -n "$PG_POD" ] || fail "No postgres pod found in namespace $SHARED_NS"

# A gzip of nothing is still ~20 bytes: look at what it holds, not its size.
dump_is_empty() {
  [ -z "$(gzip -dc "$1" 2>/dev/null | head -c 1 || true)" ]
}

# dump_database <secret key> <file prefix>
dump_database() {
  local key="$1" prefix="$2" url user pass name file
  file="$BACKUP_DIR/${prefix}_${STAMP}.sql.gz"

  url=$($KUBECTL get secret maggie-env -n "$NAMESPACE" -o jsonpath="{.data.$key}" | base64 -d)
  [ -n "$url" ] || fail "$key is missing from the maggie-env secret"

  user=$(echo "$url" | sed -n 's|.*://\([^:]*\):.*|\1|p')
  pass=$(echo "$url" | sed -n 's|.*://[^:]*:\([^@]*\)@.*|\1|p')
  name=$(echo "$url" | sed -n 's|.*/\([^?]*\).*|\1|p')

  if ! $KUBECTL exec -n "$SHARED_NS" "$PG_POD" -- sh -c "PGPASSWORD='$pass' pg_dump -U '$user' -d '$name'" 2>/dev/null | gzip > "$file" \
    || dump_is_empty "$file"; then
    rm -f "$file"
    fail "Backup of $key (database $name) is empty or failed"
  fi

  log "Backup saved: $file ($(du -h "$file" | cut -f1))"
}

dump_database DATABASE_URL maggie_predeploy
dump_database AGENT_DATABASE_URL maggie_agent_predeploy

# Rotation, once both dumps exist: a failed run must not eat the older ones.
for prefix in maggie_predeploy maggie_agent_predeploy; do
  # shellcheck disable=SC2012 # names are generated above, plain ASCII
  ls -1t "$BACKUP_DIR/${prefix}_"*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm --
done

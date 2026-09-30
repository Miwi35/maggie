#!/usr/bin/env bash
set -uo pipefail

# =============================================================================
# Maggie post-deploy digest assertion (MAG-96)
# Usage: EXPECTED_DIGESTS="php=sha256:… agent=sha256:…" scripts/verify-digests.sh
#
# Eleven past fixes were one outage replayed: the CD redeployed a stale image
# tag when not every service was rebuilt. A rollout that goes green says the pods
# are healthy, not that they run the code that was just built. This asserts it,
# for every workload that runs one of our images.
#
# Expected digest of an image:
#   - built by this CD run: the digest the build pushed, given in
#     EXPECTED_DIGESTS as <php|nginx|agent|ciqual>=sha256:<64 hex>;
#   - not built: the digest it was running before the deploy, which
#     deploy-k3s.sh recorded in pre-deploy-digests. A service that drifts
#     although nothing rebuilt it is the same failure.
#
# Exits 1 when any running pod differs, so the CD workflow rolls back.
# =============================================================================

NAMESPACE="maggie"
STATE_DIR="${MAGGIE_STATE_DIR:-/opt/maggie/state}"
DIGESTS_FILE="$STATE_DIR/pre-deploy-digests"
KUBECTL="${KUBECTL:-sudo k3s kubectl}"
REGISTRY="ghcr.io/miwi35/maggie-"
EXPECTED_DIGESTS="${EXPECTED_DIGESTS:-}"

# <deployment>:<image>. worker and cron run the php image.
WORKLOADS=(php:php worker:php cron:php nginx:nginx agent:agent ciqual:ciqual)

failed=0
checked=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; failed=1; }
warn() { echo "WARNING: $*" >&2; }

valid_digest() { [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]; }

# expected_digest <image> — prints the digest, or nothing when none is known.
expected_digest() {
  local image="$1" pair digest
  for pair in $EXPECTED_DIGESTS; do
    if [ "${pair%%=*}" = "$image" ]; then
      digest="${pair#*=}"
      # Empty means "not built by this run": fall through to the record.
      if [ -n "$digest" ]; then
        echo "$digest"
        return 0
      fi
    fi
  done
  if [ -f "$DIGESTS_FILE" ]; then
    awk -v name="$REGISTRY$image" '$1 == name { print $2; exit }' "$DIGESTS_FILE"
  fi
}

for pair in $EXPECTED_DIGESTS; do
  digest="${pair#*=}"
  if [ -n "$digest" ] && ! valid_digest "$digest"; then
    echo "FATAL: malformed expected digest '$pair'" >&2
    exit 1
  fi
done

for workload in "${WORKLOADS[@]}"; do
  deploy="${workload%%:*}"
  image="${workload#*:}"
  expected="$(expected_digest "$image")"

  if [ -z "$expected" ]; then
    warn "$deploy: no expected digest for maggie-$image (not built, none recorded before the deploy) — not checked"
    continue
  fi

  pods="$($KUBECTL get pod -n "$NAMESPACE" -l "app=$deploy" \
    --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.deletionTimestamp}|{.status.containerStatuses[0].imageID}{"\n"}{end}' 2>/dev/null || true)"

  seen=0
  while IFS='|' read -r name deleting image_id; do
    [ -n "$name" ] || continue
    # A pod being terminated is the old revision on its way out.
    [ -z "$deleting" ] || continue
    seen=$((seen + 1))
    running="${image_id#*@}"
    if [ "$running" = "$expected" ]; then
      ok "$deploy ($name) runs maggie-$image@${expected:0:19}…"
    else
      bad "$deploy ($name) runs ${running:-an unknown image} but maggie-$image should be ${expected}"
    fi
  done <<< "$pods"

  if [ "$seen" -eq 0 ]; then
    bad "$deploy: no running pod to check"
  fi
  checked=$((checked + seen))
done

if [ "$failed" -ne 0 ]; then
  echo "Running images do not match what this deploy built: a stale tag was deployed." >&2
  exit 1
fi

echo "All $checked pod(s) run the expected image digest."

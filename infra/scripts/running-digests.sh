#!/usr/bin/env bash
set -uo pipefail

# =============================================================================
# Maggie running image digests (MAG-430)
# Usage: scripts/running-digests.sh > pre-deploy-digests
#
# Prints `<image> sha256:<digest>` for every image the deploy may pin, read once
# from the pod its deployment runs now. deploy-k3s.sh pins a service it does not
# rebuild to this digest, and verify-digests.sh holds the service to the same
# record: one read for both, so they can never disagree.
#
# They used to read it twice, each time from the first Running pod. After a
# failed deploy two agent pods were Running, one on each image: the record named
# one, the pin the other, and the smoke suite rolled back a sound deploy.
#
# Among the Running pods, a pod being terminated is ignored, a ready pod wins
# over one that is not, and the newest wins among equals. An image with no such
# pod is not printed: the deploy leaves it at its tag, and nothing is checked.
# =============================================================================

NAMESPACE="maggie"
KUBECTL="${KUBECTL:-sudo k3s kubectl}"
REGISTRY="ghcr.io/miwi35/maggie-"

# <deployment>:<image>, one deployment per image.
WORKLOADS=(php:php nginx:nginx agent:agent ciqual:ciqual)

for workload in "${WORKLOADS[@]}"; do
  deploy="${workload%%:*}"
  image="$REGISTRY${workload#*:}"

  # Oldest first: the last pod read is the newest.
  pods="$($KUBECTL get pod -n "$NAMESPACE" -l "app=$deploy" \
    --field-selector=status.phase=Running --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.deletionTimestamp}|{.status.containerStatuses[0].ready}|{.status.containerStatuses[0].imageID}{"\n"}{end}' 2>/dev/null || true)"

  ready=""
  any=""
  while IFS='|' read -r name deleting is_ready image_id; do
    [ -n "$name" ] && [ -z "$deleting" ] || continue
    case "$image_id" in
      "$image"@sha256:*) ;;
      *) continue ;;
    esac
    any="${image_id#*@}"
    if [ "$is_ready" = "true" ]; then
      ready="$any"
    fi
  done <<< "$pods"

  digest="${ready:-$any}"
  if [ -n "$digest" ]; then
    echo "$image $digest"
  fi
done

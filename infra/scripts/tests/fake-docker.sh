#!/usr/bin/env bash
# A docker for deploy-k3s.test.sh: `manifest inspect <image>:<tag>` succeeds when
# the tag is listed in $FAKE_DOCKER_TAGS (space separated), fails otherwise.
set -euo pipefail

ref="${*: -1}"
for tag in ${FAKE_DOCKER_TAGS:-}; do
  case "$ref" in *":$tag") exit 0 ;; esac
done
exit 1

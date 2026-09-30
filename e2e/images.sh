#!/usr/bin/env bash
#
# The e2e stack's own images, built once per set of build inputs (MAG-135).
#
# None of them embeds code — ./api, ./agent and ./ciqual are bind-mounted — so
# an image only changes when something its Dockerfile reads changes. Each one
# is tagged with the hash of exactly those inputs:
#
#   ghcr.io/miwi35/maggie-e2e-<service>:<hash>
#
# and CI pulls it when the tag exists, builds and pushes it when it does not.
# php and worker share one image. Locally nothing changes: the Compose file
# falls back to `build:` and its own tag when no E2E_IMAGE_* variable is set.
#
#   e2e/images.sh env      print E2E_IMAGE_<SERVICE>=<ref> lines (>> $GITHUB_ENV)
#   e2e/images.sh ensure   pull or build+push each image, and pull the third-party
#                          ones meanwhile; needs `docker login ghcr.io` to push
#
# `env` must be in effect for `ensure` (it reads the same variables), which is
# why `ensure` exports them itself.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Compose reads UID and GID from the environment for the build args.
GID="$(id -g)"
export UID GID

REGISTRY="${E2E_IMAGE_REGISTRY:-ghcr.io/miwi35/maggie-e2e}"
COMPOSE_FILE="$ROOT/docker-compose.e2e.yml"

# The services that own an image, and the build inputs of each: every file the
# Dockerfile COPYs, plus the build args. Anything else in .docker/ (WireMock
# stubs, Postgres init) is mounted, so editing it must not rebuild anything.
SERVICES=(php nginx agent ciqual)

inputs() {
  case "$1" in
    php)    find .docker/php -type f ;;
    nginx)  find .docker/nginx -type f ;;
    agent)  echo .docker/python/Dockerfile ;;
    ciqual) echo .docker/ciqual/Dockerfile; echo ciqual/pyproject.toml ;;
  esac
}

# The resolved `build:` stanza of the Compose file — context aside, which is an
# absolute path — so a new build arg, another target or another Dockerfile path
# changes the tag without anyone remembering to mirror it here. UID/GID are in
# it: they are baked into the image's `app` user, so a runner and a laptop must
# not share a tag.
build_of() {
  docker compose -f "$COMPOSE_FILE" config --format json |
    jq -cS --arg svc "$1" '.services[$svc].build | del(.context)'
}

hash_of() {
  {
    build_of "$1"
    inputs "$1" | LC_ALL=C sort | xargs -r sha256sum
  } | sha256sum | cut -c1-12
}

var_of() { printf 'E2E_IMAGE_%s' "$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"; }
ref_of() { printf '%s-%s:%s' "$REGISTRY" "$1" "$(hash_of "$1")"; }

print_env() {
  local svc
  for svc in "${SERVICES[@]}"; do
    printf '%s=%s\n' "$(var_of "$svc")" "$(ref_of "$svc")"
  done
}

ensure_one() {
  local svc="$1" ref
  ref="$(ref_of "$svc")"
  if docker pull --quiet "$ref" >/dev/null 2>&1; then
    echo "[$svc] pulled $ref"
    return 0
  fi
  echo "[$svc] $ref not found, building it"
  docker compose -f "$COMPOSE_FILE" build --quiet "$svc"
  # A failed push (a fork's read-only token, a registry blip) must not fail the
  # run: the image is on this machine and the stack can start from it.
  if docker push --quiet "$ref" >/dev/null; then
    echo "[$svc] pushed $ref"
  else
    echo "::warning::could not push $ref — the next run will rebuild it"
  fi
}

ensure() {
  while IFS='=' read -r name value; do export "$name=$value"; done < <(print_env)

  local pids=() names=() i failed=0

  # Third-party images download while the four above pull or build. The
  # Playwright one is behind a profile, hence the flag: without it the first
  # `web:run` downloads it in the middle of the journeys.
  docker compose -f "$COMPOSE_FILE" --profile tools pull --ignore-buildable --quiet &
  pids+=("$!"); names+=("third-party images")

  for svc in "${SERVICES[@]}"; do
    ensure_one "$svc" &
    pids+=("$!"); names+=("$svc")
  done

  for i in "${!pids[@]}"; do
    if ! wait "${pids[$i]}"; then
      echo "::error::${names[$i]} failed" >&2
      failed=1
    fi
  done
  return "$failed"
}

case "${1:-}" in
  env)    print_env ;;
  ensure) ensure ;;
  *)      echo "usage: $0 env|ensure" >&2; exit 2 ;;
esac

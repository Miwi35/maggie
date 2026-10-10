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
#   e2e/images.sh hash <svc>  the hash alone (wt/image.sh tags its php image with it)
#   e2e/images.sh ref <svc>   the full registry reference for that hash
#   e2e/images.sh pull     pull the third-party images, one at a time, retried
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
  # UID and GID given explicitly: a caller that exports them empty (Task does,
  # while it evaluates a `sh:` variable) would hash the 1000 fallback instead.
  env UID="$(id -u)" GID="$(id -g)" docker compose -f "$COMPOSE_FILE" config --format json |
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

# Builds one image, seeding the layer cache from the last image pushed under the
# service's moving `:cache` tag (MAG-180). The cache travels inside the image
# (`type=inline`), so it needs no second package on the registry: a Dockerfile
# edit rebuilds the layers from that line down and pulls the ones above it.
# Every stage the e2e images ship is a chain from `base`, so the final image
# holds every layer worth caching.
build_one() {
  local svc="$1" cache="$2" log
  log="$(mktemp)"
  local attempt
  # The base images come from ECR Public, which turns away more than one
  # anonymous pull per second and per IP: a build refused for that is retried.
  for attempt in 1 2 3; do
    if docker buildx bake -f "$COMPOSE_FILE" --load --progress=plain \
        --set "$svc.cache-from=type=registry,ref=$cache" \
        --set "$svc.cache-to=type=inline" "$svc" >"$log" 2>&1; then
      echo "[$svc] $(grep -c ' CACHED$' "$log" || true) steps from cache"
      rm -f "$log"
      return 0
    fi
    if [ "$attempt" -lt 3 ] && grep -qE 'toomanyrequests|Rate exceeded' "$log"; then
      echo "::warning::[$svc] base image pull rate-limited (attempt $attempt), retrying" >&2
      sleep $((attempt * 10))
      continue
    fi
    break
  done
  cat "$log" >&2
  rm -f "$log"
  return 1
}

ensure_one() {
  local svc="$1" ref cache start="$SECONDS"
  ref="$(ref_of "$svc")"
  cache="$REGISTRY-$svc:cache"
  if docker pull --quiet "$ref" >/dev/null 2>&1; then
    echo "[$svc] pulled $ref in $((SECONDS - start))s"
    return 0
  fi
  echo "[$svc] $ref not found, building it"
  build_one "$svc" "$cache"
  echo "[$svc] built in $((SECONDS - start))s"
  # A failed push (a fork's read-only token, a registry blip) must not fail the
  # run: the image is on this machine and the stack can start from it.
  if docker push --quiet "$ref" >/dev/null; then
    echo "[$svc] pushed $ref"
    # The next build, on any branch, starts from this one's layers.
    docker tag "$ref" "$cache" && docker push --quiet "$cache" >/dev/null ||
      echo "::warning::could not push $cache — the next build starts from the previous cache"
  else
    echo "::warning::could not push $ref — the next run will rebuild it"
  fi
}

# The third-party images come from ECR Public and the GHCR mirror, never Docker
# Hub (10 Oct.). ECR Public allows one anonymous pull per second and per IP and
# answers `toomanyrequests: Rate exceeded` beyond: the images are pulled one
# service at a time, and a failed pull is retried after a pause.
pull_third_party() {
  local svc attempt services
  services="$(docker compose -f "$COMPOSE_FILE" config --services)"
  [ -n "$services" ] || { echo "::error::no service in $COMPOSE_FILE" >&2; return 1; }
  for svc in $services; do
    for attempt in 1 2 3 4; do
      docker compose -f "$COMPOSE_FILE" pull --ignore-buildable --quiet "$svc" && break
      if [ "$attempt" -eq 4 ]; then
        echo "::error::third-party image of $svc: pull failed 4 times" >&2
        return 1
      fi
      echo "::warning::third-party image of $svc: pull failed (attempt $attempt), retrying" >&2
      sleep $((attempt * 5))
    done
  done
}

ensure() {
  while IFS='=' read -r name value; do export "$name=$value"; done < <(print_env)

  local pids=() names=() i failed=0

  # Third-party images download while the four above pull or build. The
  # Playwright one (~2 GB, behind a profile) is not among them: it is not needed
  # before the journeys and would take the bandwidth the php image is waiting for.
  pull_third_party &
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

  # The Playwright image downloads while the stack starts, detached: the first
  # `web:run` would otherwise pull it in the middle of the journeys.
  if [ "$failed" -eq 0 ] && [ -n "${CI:-}" ]; then
    setsid nohup docker compose -f "$COMPOSE_FILE" --profile tools pull --quiet playwright >/dev/null 2>&1 &
  fi
  return "$failed"
}

case "${1:-}" in
  env)    print_env ;;
  ensure) ensure ;;
  hash)   hash_of "${2:?service}" ;;
  ref)    ref_of "${2:?service}" ;;
  pull)   pull_third_party ;;
  *)      echo "usage: $0 env|ensure|pull|hash <svc>|ref <svc>" >&2; exit 2 ;;
esac

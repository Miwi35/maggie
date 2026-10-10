#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of infra/scripts/docker-hub-guard.sh.
#
# Docker Hub's anonymous pull limit took CI down twice (9–10 Oct.): the guard
# must be red on any image CI would pull from it — no registry host, docker.io,
# a BuildKit left at its default — and green on the mirrors, the project's own
# images and the allow-listed exceptions.
#
# Usage: infra/scripts/tests/docker-hub-guard.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../docker-hub-guard.sh"
failures=0

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# repo — a fresh repository with a clean Dockerfile, compose file and workflow.
repo() {
  rm -rf "$WORK/repo"
  mkdir -p "$WORK/repo/.docker/php" "$WORK/repo/.github/workflows"
  cd "$WORK/repo" || exit 1
  git init -q .
  cat > .docker/php/Dockerfile <<'EOF'
ARG PHP_VERSION=8.4
ARG PHP_IMAGE=maggie-php:latest
FROM public.ecr.aws/docker/library/php:${PHP_VERSION}-fpm-alpine AS base
COPY --from=public.ecr.aws/docker/library/composer:latest /usr/bin/composer /usr/bin/composer
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
# FROM php:8.4 in a comment is not a pull
FROM base AS dev
COPY --from=base /app /app
COPY --from=0 /app /app
FROM ${PHP_IMAGE} AS source
FROM scratch
EOF
  cat > docker-compose.e2e.yml <<'EOF'
services:
  php:
    image: ${E2E_IMAGE_PHP:-${COMPOSE_PROJECT_NAME:-maggie-e2e}-php}
  database:
    image: public.ecr.aws/docker/library/postgres:${POSTGRES_VERSION:-17}-alpine
  mercure:
    image: "ghcr.io/miwi35/mirror/mercure:v1.0.2@sha256:e5d8f06a60f2feb5e91f7ba2046d9184f6339b3498b5d2f5adaeb5bcb080bfc7"
  playwright:
    image: mcr.microsoft.com/playwright:v1.63.0-noble
  worker:
    image: ${WT_PHP_IMAGE:?the Taskfile must pass the image tag}
    # image: postgres:17 in a comment is not a pull
EOF
  cat > .github/workflows/ci.yml <<'EOF'
jobs:
  api-test:
    services:
      postgres:
        image: public.ecr.aws/docker/library/postgres:17
    steps:
      - uses: docker/setup-buildx-action@v3
        with:
          driver-opts: image=ghcr.io/miwi35/mirror/buildkit:buildx-stable-1
      - run: docker run --rm -v "$PWD":/app -w /app ghcr.io/astral-sh/uv:python3.12-alpine uv --version
EOF
}

# run — the guard's exit status in $status, its output in $out.
run() {
  out="$(bash "$SCRIPT" "$WORK/repo" 2>&1)"
  status=$?
}

expect_green() {
  run
  [[ $status -eq 0 ]] && ok "$1" || bad "$1 — exit $status: $out"
}

# expect_red <description> <text the finding must contain>
expect_red() {
  run
  [[ $status -eq 1 && "$out" == *"$2"* ]] && ok "$1" || bad "$1 — exit $status: $out"
}

echo "docker-hub-guard.sh"

repo
expect_green "mirrors, own images, stages, scratch and caller-chosen images pass"

repo
sed -i 's#^FROM public.ecr.aws/docker/library/php:#FROM php:#' .docker/php/Dockerfile
expect_red "an official image without a registry is red, its ARG resolved" ".docker/php/Dockerfile:3: php:8.4-fpm-alpine pulls from Docker Hub"

repo
sed -i 's#COPY --from=public.ecr.aws/docker/library/composer:latest#COPY --from=composer:latest#' .docker/php/Dockerfile
expect_red "COPY --from an image on Docker Hub is red" "composer:latest pulls from Docker Hub"

repo
printf 'FROM --platform=linux/amd64 docker.io/library/node:22-alpine\n' > .docker/node.Dockerfile
expect_red "an explicit docker.io in a *.Dockerfile is red, --platform skipped" "docker.io/library/node:22-alpine pulls from Docker Hub"

repo
sed -i 's#public.ecr.aws/docker/library/postgres:${POSTGRES_VERSION:-17}-alpine#postgres:${POSTGRES_VERSION:-17}-alpine#' docker-compose.e2e.yml
expect_red "a compose image is red, its default resolved" "docker-compose.e2e.yml:5: postgres:17-alpine pulls from Docker Hub"

repo
sed -i 's#"ghcr.io/miwi35/mirror/mercure:#"dunglas/mercure:#' docker-compose.e2e.yml
expect_red "a quoted non-official image is red" "dunglas/mercure:v1.0.2@sha256:"

repo
sed -i 's#image: public.ecr.aws/docker/library/postgres:17$#image: postgres:17#' .github/workflows/ci.yml
expect_red "a workflow service image is red" ".github/workflows/ci.yml:5: postgres:17 pulls from Docker Hub"

repo
sed -i '/with:/d; /driver-opts/d' .github/workflows/ci.yml
expect_red "setup-buildx-action without driver-opts is red" ".github/workflows/ci.yml:7: docker/setup-buildx-action without driver-opts"

repo
printf '      - uses: docker/setup-buildx-action@v3\n' >> .github/workflows/ci.yml
expect_red "setup-buildx-action as the last step of a file is red" "docker/setup-buildx-action without driver-opts"

repo
sed -i 's#ghcr.io/astral-sh/uv:python3.12-alpine uv#rhysd/actionlint:latest -color#' .github/workflows/ci.yml
expect_red "docker run of a Docker Hub image is red, options skipped" "rhysd/actionlint:latest pulls from Docker Hub"

repo
printf '    container: node:22\n' >> .github/workflows/ci.yml
expect_red "a job container on Docker Hub is red" "node:22 pulls from Docker Hub"

repo
sed -i 's#mcr.microsoft.com/playwright#quay.io/someone/playwright#' docker-compose.e2e.yml
expect_red "a registry not allowed is red" "comes from quay.io, not an allowed registry"

repo
sed -i 's#mcr.microsoft.com/playwright#quay.io/someone/playwright#' docker-compose.e2e.yml
printf 'quay.io/someone/playwright:v1.63.0-noble only published there\n' > .github/docker-hub-allowlist.txt
expect_green "an allow-listed image with a reason passes"

repo
sed -i 's#mcr.microsoft.com/playwright#quay.io/someone/playwright#' docker-compose.e2e.yml
printf '# a comment\nquay.io/someone/playwright:v1.63.0-noble\n' > .github/docker-hub-allowlist.txt
expect_red "an allow-list line without a reason is red" "has no reason"

echo
if [[ $failures -gt 0 ]]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"

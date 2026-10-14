# Multi-Stage Dockerfiles

The php image (and the other multi-stage Dockerfiles) use 3-stage builds: `base` → `dev` → `prod`. The php Dockerfile adds an `e2e` stage (between `dev` and `prod`) for the e2e stack, which mounts `./api` of the checkout it starts from.

```dockerfile
ARG PHP_VERSION
FROM ghcr.io/miwi35/mirror/php:${PHP_VERSION}-fpm-alpine AS base
# shared deps

FROM base AS dev
# dev tools (xdebug, etc), bind-mount volumes

FROM base AS prod
COPY . /app
# self-contained, no volumes
```

## Rules
- Version pinned via build ARG from `.env`
- Non-root user with host UID/GID (`ARG UID`, `ARG GID`) — prevents permission issues on bind-mounted files
- Dev stage: bind-mount source code, install dev tools
- Prod stage: COPY source code, no bind mounts, no dev deps
- `docker-compose.yml` targets `dev` stage via `target: dev`
- Every `FROM` and `COPY --from` pulls from the project's mirror, `ghcr.io/miwi35/mirror/<name>:<tag>`, never from Docker Hub directly: adding an image means adding it to the mirror list (see *Docker Image Registry* in [ci-cd-pipeline](../deployment/ci-cd-pipeline.md))

## Production Images
Registry `ghcr.io/miwi35/maggie-*`, tagged `:<git-sha>` + `:latest` — the image table and tagging rules are in [ci-cd-pipeline](../deployment/ci-cd-pipeline.md), *Docker Image Registry*.

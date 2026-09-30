---
name: docker-builds
description: "Multi-stage Dockerfile conventions. Use when creating or modifying Dockerfiles in .docker/ including base/dev/prod stages, UID/GID matching, and build ARGs."
user-invocable: false
---

# Multi-Stage Dockerfiles

All services use 3-stage builds: `base` → `dev` → `prod`.

## Pattern

```dockerfile
ARG PHP_VERSION
FROM php:${PHP_VERSION}-fpm-alpine AS base
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

## Production Images

- Registry: `ghcr.io/miwi35/maggie-*`
- Tags: `:<git-sha>` + `:latest`

| Image | Source |
|-------|--------|
| `ghcr.io/miwi35/maggie-php` | `.docker/php/Dockerfile` |
| `ghcr.io/miwi35/maggie-nginx` | `.docker/nginx/Dockerfile` |
| `ghcr.io/miwi35/maggie-agent` | `.docker/python/Dockerfile` |

## Reference

For full details, read `agent-os/standards/docker/dockerfiles.md`

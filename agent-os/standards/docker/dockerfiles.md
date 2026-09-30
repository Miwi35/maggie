# Multi-Stage Dockerfiles

All services use 3-stage builds: `base` → `dev` → `prod`.

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

---
name: docker-builds
description: "Multi-stage Dockerfile conventions. Use when creating or modifying Dockerfiles in .docker/ including base/dev/prod stages, UID/GID matching, and build ARGs."
user-invocable: false
---

# Multi-Stage Dockerfiles

**Use when** creating or changing a Dockerfile in `.docker/`.

**Read** `agent-os/standards/docker/dockerfiles.md`; images and mirror: `agent-os/standards/deployment/ci-cd-pipeline.md`.

## Gotchas

- Stages are `base` → `dev` → `prod` (the php image adds `e2e`); `docker-compose.yml` targets `dev`.
- Versions come from build ARGs in `.env`; the non-root user takes the host `UID`/`GID`.
- Every `FROM` and `COPY --from` uses `ghcr.io/miwi35/mirror/<name>:<tag>`, never Docker Hub directly.
- Prod copies the source and carries no dev dependency or bind mount.

# CI/CD Pipeline — Maggie v3

## Pipeline Architecture

```
Push to main → CI (lint + test) → CD (build → push → deploy)
                                         ↓
                                  Requires approval
                                  (production environment)
```

---

## CI Workflow (`.github/workflows/ci.yml`)

**Trigger:** Push and PRs on `main` (excludes `mobile/**` and `**.md`)

### Jobs (run in parallel)

| Job | Runtime | What it does |
|-----|---------|-------------|
| API Lint | PHP 8.4 | PHPStan static analysis on `api/` |
| Agent Lint | Python 3.12 | Ruff linting on `agent/` |
| Admin Lint | Node 22 | ESLint + TypeScript check on `admin/` |
| API Tests | PHP 8.4 + PostgreSQL 17 | PHPUnit tests (`bin/phpunit`) |
| Agent Tests | Python 3.12 | pytest unit tests |
| Admin Tests | Node 22 | Vitest component tests |

### API Test Job Details
- Spins up PostgreSQL 17 as a service container
- Installs Composer deps
- Creates test database + runs migrations
- Runs `php bin/phpunit`

### Agent Test Job Details
- Installs with `uv pip install --system -e '.[dev]'`
- Runs `pytest`

### Admin Test Job Details
- Installs with `npm ci`
- Runs `npm test`

---

## CD Workflow (`.github/workflows/cd.yml`)

**Trigger:** Successful completion of CI workflow on `main`

### Build Jobs (parallel)

#### 1. build-php
```yaml
docker/build-push-action:
  context: .
  file: .docker/php/Dockerfile
  target: prod
  tags:
    - ghcr.io/miwi35/maggie-php:${{ github.sha }}
    - ghcr.io/miwi35/maggie-php:latest
  cache-from: type=gha,scope=php
  cache-to: type=gha,mode=max,scope=php
```

#### 2. build-agent
```yaml
docker/build-push-action:
  context: .
  file: .docker/python/Dockerfile
  target: prod
  tags:
    - ghcr.io/miwi35/maggie-agent:${{ github.sha }}
    - ghcr.io/miwi35/maggie-agent:latest
  cache-from: type=gha,scope=agent
  cache-to: type=gha,mode=max,scope=agent
```

#### 3. build-nginx (depends on build-php)
```yaml
docker/build-push-action:
  context: .
  file: .docker/nginx/Dockerfile
  target: prod
  build-args:
    PHP_IMAGE: ghcr.io/miwi35/maggie-php:${{ github.sha }}
  tags:
    - ghcr.io/miwi35/maggie-nginx:${{ github.sha }}
    - ghcr.io/miwi35/maggie-nginx:latest
```
> Nginx depends on PHP image to copy API public directory.

### Deploy Job

**Requires:** build-php, build-nginx, build-agent
**Environment:** `production` (requires GitHub approval)

**Steps (SSH to VPS):**
1. `scp` `infra/k8s/` and `infra/scripts/deploy-k3s.sh` to `/opt/maggie/`
2. Run `deploy-k3s.sh <github.sha>`, which does:
   1. **Preflight** — kubectl reachable, shared `postgres`/`elasticsearch`/`rabbitmq` ready in the `shared` namespace
   2. **Backup** — `pg_dump | gzip` into `/opt/maggie/backups`, keeping the last 10 (a failed or empty dump aborts the deploy)
   3. **Apply** — bump the image tags in `kustomization.yaml` (only for images actually published for that SHA), then `kubectl apply -k`
   4. **Wait** — `rollout status` on php, nginx, worker, cron, agent, ciqual, mercure
   5. **Post-deploy** — migrations, `cache:clear`, Elasticsearch mapping update and reindex
   6. **Verify** — pod list plus an HTTP check on `https://maggieai.fr/api/docs`

> Deployment targets k3s, not Docker Compose: manifests live in `infra/k8s/`
> (Deployments, Services, Ingress, ConfigMap, Secret, Kustomization) and
> production reads its environment from the `maggie-env` secret in the
> `maggie` namespace.

---

## Mobile CI (`.github/workflows/mobile.yml`)

**Trigger:** Push/PR with changes to `mobile/**`

**Job:** Mobile Unit Tests
- Runtime: Java 17 (Temurin), Gradle
- Command: `./gradlew :app:testDebugUnitTest`

> Mobile is tested separately — not part of the main CI/CD pipeline.

---

## GitHub Actions Secrets

| Secret | Purpose |
|--------|---------|
| `VPS_HOST` | IP or domain of production VPS |
| `VPS_USER` | Deploy user on VPS |
| `VPS_SSH_KEY` | Private SSH key for deployment |
| `GITHUB_TOKEN` | Auto-provided, used for GHCR login |

### GitHub Environment
- Name: `production`
- Requires manual approval before deploy job runs

---

## Docker Image Registry

**Registry:** `ghcr.io/miwi35/maggie-*`

| Image | Source |
|-------|--------|
| `ghcr.io/miwi35/maggie-php` | `.docker/php/Dockerfile` |
| `ghcr.io/miwi35/maggie-nginx` | `.docker/nginx/Dockerfile` |
| `ghcr.io/miwi35/maggie-agent` | `.docker/python/Dockerfile` |

**Tagging Strategy:**
- `:<full-git-sha>` — Unique per commit, used for deployment
- `:latest` — Always points to most recent build

---

## Build Caching

All images use GitHub Actions cache (`type=gha`) with dedicated scopes:
- `php` scope for PHP image layers
- `agent` scope for Agent image layers
- Nginx reuses PHP image directly (no separate cache)

---

## Deployment Flow Summary

```
1. Developer pushes to main
2. CI runs 6 jobs in parallel (lint + test for API, Agent, Admin)
3. All CI jobs pass → CD workflow triggered
4. CD builds 3 Docker images (PHP + Agent in parallel, then Nginx)
5. Images pushed to GHCR
6. Deploy job waits for manual approval
7. SSH to VPS:
   - Pull new images
   - docker compose up -d
   - Run database migrations
   - Health check
   - Prune old images
8. Traefik (already running) auto-routes to new containers
```

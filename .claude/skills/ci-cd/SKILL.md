---
name: ci-cd
description: "CI/CD pipeline conventions. Use when modifying GitHub Actions workflows in .github/, Docker image builds, deployment scripts, or GHCR registry configuration."
user-invocable: false
---

# CI/CD Pipeline

```
Push to main → CI (lint + test) → CD (build → push → deploy)
                                        ↓
                                 Requires approval
                                 (production environment)
```

## CI Workflow (`.github/workflows/ci.yml`)

**Trigger:** Push and PRs on `main` (excludes `mobile/**` and `**.md`)

### Jobs (parallel)

| Job | Runtime | What |
|-----|---------|------|
| API Lint | PHP 8.4 | PHPStan static analysis |
| Agent Lint | Python 3.12 | Ruff linting |
| Admin Lint | Node 22 | ESLint + TypeScript check |
| API Tests | PHP 8.4 + PostgreSQL 17 | PHPUnit tests |
| Agent Tests | Python 3.12 | pytest unit tests |
| Admin Tests | Node 22 | Vitest component tests |

## CD Workflow (`.github/workflows/cd.yml`)

**Trigger:** Successful CI on `main`

### Build Jobs

- **build-php** + **build-agent** — run in parallel
- **build-nginx** — depends on build-php (copies API public dir)

All pushed to `ghcr.io/miwi35/maggie-*` with `:<git-sha>` + `:latest` tags.

### Deploy Job

**Requires:** All builds + manual approval (`production` environment)

Steps (SSH to VPS):
1. Pull new images
2. `docker compose up -d`
3. Run database migrations
4. Health check
5. Prune old images

## Mobile CI (`.github/workflows/mobile.yml`)

Separate pipeline — triggered by `mobile/**` changes only.
Command: `./gradlew :app:testDebugUnitTest`

## GitHub Actions Secrets

| Secret | Purpose |
|--------|---------|
| `VPS_HOST` | Production VPS IP/domain |
| `VPS_USER` | Deploy user |
| `VPS_SSH_KEY` | SSH key for deployment |
| `GITHUB_TOKEN` | Auto-provided, GHCR login |

## Build Caching

All images use GitHub Actions cache (`type=gha`) with dedicated scopes: `php`, `agent`.

## Reference

For full details, read `agent-os/standards/deployment/ci-cd-pipeline.md`

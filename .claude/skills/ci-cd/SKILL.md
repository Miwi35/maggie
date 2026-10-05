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

### Gate (MAG-189)

CD's `gate` job (`infra/scripts/should-deploy.sh`) lets a run deploy only when its CI's commit is the head of `main` and not already deployed. Build, tag and deploy on `env.RELEASE_SHA` (`workflow_run.head_sha`), never `github.sha`.

### Smoke and rollback (MAG-106)

After **deploy**, the **smoke** job runs `infra/scripts/smoke-prod.sh` on production as a technical account (it writes only that account's own data: its chat history, wiped before each question, and a test agenda deleted afterwards — MAG-253). If a build, the deploy script or the smoke fails, **rollback** runs `infra/scripts/rollback-k3s.sh` (`rollout undo` to the recorded revisions; not after a failed build), moves the shipped tickets to the Linear state « Emergency » (an `incident` ticket when none can carry it) and leaves the run red. Migrations are not reverted. Details in the standard below.

## Mobile CI (`.github/workflows/mobile.yml`)

Separate pipeline — triggered by `mobile/**` and `api/contract/**` changes (and the nightly run).
Command: `./gradlew :app:testProdReleaseUnitTest` (the variant that ships)

## Nightly (`.github/workflows/nightly.yml`)

Calls `ci.yml` and `mobile.yml` with no path filter; a failure opens a `nightly-failure` issue.

## Digest assertion (MAG-96)

`infra/scripts/verify-digests.sh` runs first in the CD smoke job: every pod must run the digest just built, or the pre-deploy one for a service not rebuilt.

## Required checks

A new job in `ci.yml` must be added to the required checks of `main` in the same delivery.

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

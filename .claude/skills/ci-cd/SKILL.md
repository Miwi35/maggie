---
name: ci-cd
description: "CI/CD pipeline conventions. Use when modifying GitHub Actions workflows in .github/, Docker image builds, deployment scripts, or GHCR registry configuration."
user-invocable: false
---

# CI/CD Pipeline

One line per event in the Actions list (MAG-244), each with a `run-name`:

- PR push → `ci.yml` (`Pull request`): path detection, guard, lint, tests, e2e, mobile unit tests
- Merge on `main` → `main.yml` (`Main`): CI (calls `ci.yml`) → gate → build → deploy → smoke → lift the freeze → release the held PRs, or rollback
- Night → `nightly.yml`: CI with no path filter + real-model eval
- No `workflow_run`, no 10-minute cron (an hourly safety net, `incident-gate-release.yml`)

## CI Workflow (`.github/workflows/ci.yml`)

**Trigger:** `pull_request` on `main`, and `workflow_call` from `main.yml` and `nightly.yml`. Jobs stay in `ci.yml`: a required check is a job name, and a job behind `uses:` is renamed `Caller / Job`.

### Jobs (parallel)

| Job | Runtime | What |
|-----|---------|------|
| API Lint | PHP 8.4 | PHPStan static analysis |
| Agent Lint | Python 3.12 | Ruff linting |
| Admin Lint | Node 22 | ESLint + TypeScript check |
| API Tests | PHP 8.4 + PostgreSQL 17 | PHPUnit tests |
| Agent Tests | Python 3.12 | pytest unit tests |
| Admin Tests | Node 22 | Vitest component tests |

## Main pipeline (`.github/workflows/main.yml`)

**Trigger:** push on `main`; `ci` job first, a red CI stops the run before the gate. One run at a time (`main-pipeline` group, never cancels a started run).

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

The `gate` job (`infra/scripts/should-deploy.sh`) lets a run deploy only when its commit is the head of `main` and not already deployed by an earlier successful run of `main.yml`. Build, tag and deploy on `env.RELEASE_SHA` (`github.sha`).

### Smoke and rollback (MAG-106)

After **deploy**, the **smoke** job runs `infra/scripts/smoke-prod.sh` on production as a technical account (it writes only that account's own data: its chat history, wiped before each question, and a test agenda deleted afterwards — MAG-253). If a build, the deploy script or the smoke fails, **rollback** runs `infra/scripts/rollback-k3s.sh` (`rollout undo` to the recorded revisions; not after a failed build), moves the shipped tickets to the Linear state « Emergency » (an `incident` ticket when none can carry it) and leaves the run red. Migrations are not reverted. Details in the standard below.

## Mobile unit tests (job of `ci.yml`)

Job `Mobile Unit Tests (release)` — runs on `mobile/**` and `api/contract/**` changes (and the nightly run); skipped otherwise.
Command: `./gradlew :app:testProdReleaseUnitTest` (the variant that ships)

## Nightly (`.github/workflows/nightly.yml`)

Calls `ci.yml` with no path filter, beside the real-model eval (formerly `eval.yml`); a CI failure opens a Linear `Bug` labelled `nightly-failure`, or comments the one already open (`infra/scripts/alert-ticket.sh`; GitHub issues are disabled).

## Digest assertion (MAG-96)

`infra/scripts/verify-digests.sh` runs first in the smoke job of `main.yml`: every pod must run the digest just built, or the pre-deploy one for a service not rebuilt.

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

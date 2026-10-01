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

### Gate (MAG-189)

Every CI that finishes on `main` starts a CD run, including the late CI of an older commit. For `workflow_run`, `github.sha` is the head of `main`, not the commit of that CI, so such a run used to build the head again (new digests, same tag), fail the digest assertion and roll back for nothing.

- The `gate` job runs `infra/scripts/should-deploy.sh` (tested by `infra/scripts/tests/should-deploy.test.sh`): the run deploys only if `workflow_run.head_sha` is the head of `main` right now and no earlier successful CD run exists for that SHA. Otherwise every other job is skipped: no build, no deploy, no incident, no rollback. A stopped run is never left green, because a successful CD run is the record that a commit was handled (the "already deployed" check, the base of the change detection): a SHA that is not the head of `main` at trigger time skips the gate (run `skipped`), a later stop cancels its own run (`actions: write`). It fails closed (exit 1) when the GitHub API cannot be read.
- Everything is built, tagged, copied and verified on `env.RELEASE_SHA` (= `workflow_run.head_sha`), never on `github.sha`.
- A run whose SHA is not the head of `main` at trigger time gets its own concurrency group, so it cannot displace the head's pending run.

### Build Jobs (parallel)

#### 1. build-php
```yaml
docker/build-push-action:
  context: .
  file: .docker/php/Dockerfile
  target: prod
  tags:
    - ghcr.io/miwi35/maggie-php:${{ env.RELEASE_SHA }}
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
    - ghcr.io/miwi35/maggie-agent:${{ env.RELEASE_SHA }}
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
    PHP_IMAGE: ghcr.io/miwi35/maggie-php:${{ env.RELEASE_SHA }}
  tags:
    - ghcr.io/miwi35/maggie-nginx:${{ env.RELEASE_SHA }}
    - ghcr.io/miwi35/maggie-nginx:latest
```
> Nginx depends on PHP image to copy API public directory.

### Deploy Job

**Requires:** build-php, build-nginx, build-agent
**Environment:** `production` (requires GitHub approval)

**Steps (SSH to VPS):**
1. `scp` `infra/k8s/`, `infra/scripts/deploy-k3s.sh`, `backup-k3s.sh`, `rollback-k3s.sh` and `verify-digests.sh` to `/opt/maggie/`
2. Run `deploy-k3s.sh <RELEASE_SHA>`, which does:
   1. **Preflight** — kubectl reachable, shared `postgres`/`elasticsearch`/`rabbitmq` ready in the `shared` namespace
   2. **Backup** (`backup-k3s.sh`, MAG-188) — `pg_dump | gzip` of **both** databases into `/opt/maggie/backups`: `maggie_predeploy_<ts>.sql.gz` (`DATABASE_URL`) and `maggie_agent_predeploy_<ts>.sql.gz` (`AGENT_DATABASE_URL`: memory, messages, contexts, directives, proactions, personality). Last 10 of each kept; a failed or empty dump, either one, aborts the deploy
   3. **Apply** — record the current revision of every deployment (`/opt/maggie/state/pre-deploy-revisions`) and the digest every image runs (`pre-deploy-digests`), bump the image tags in `kustomization.yaml` (only for images actually published for that SHA), then `kubectl apply -k`
   4. **Wait** — `rollout status` on php, nginx, worker, cron, agent, ciqual, mercure
   5. **Post-deploy** — migrations (no `cache:clear`: the image ships a warmed cache), Elasticsearch mapping update and reindex
   6. **Verify** — pod list plus an HTTP check on `https://maggieai.fr/api/docs`

### Smoke job (MAG-106)

**Requires:** deploy succeeded. Read-only, as the technical account `smoke@maggieai.fr`.

0. **Digest assertion (MAG-96)**, first: `infra/scripts/verify-digests.sh` checks that every pod of php, worker, cron, nginx, agent and ciqual runs the digest the build job pushed in this run — or, for a service this run did not rebuild, the digest it ran before the deploy (`pre-deploy-digests`). A service on a stale tag fails the smoke job, hence the rollback. A digest names an index, not an image: a rebuild with unchanged content pushes a new index for the same image, and the pod keeps reporting the first one. The script therefore also accepts a pod whose image carries the expected digest among its `repoDigests` (`sudo k3s crictl inspecti` on the node, MAG-146). A green rollout is not enough: eleven past fixes were one stale-tag redeploy. Expected digests come from the build jobs' `digest` output; the build jobs are therefore `needs` of the smoke job.
1. SSH to the VPS, `bin/console app:smoke:token` in the php pod prints a JWT (account created on first use; token masked in the public log).
2. `infra/scripts/smoke-prod.sh` checks: public URLs (`/`, `/admin` with and without trailing slash, no `http://` redirect), API, agent and Mercure health (+ Mercure CORS), `/_mcp` `tools/list`, `app:elasticsearch:status --check`, and one question to Maggie that must call a tool.
3. On success the smoke job deletes the rollback record, so a later deploy that never reaches the cluster cannot undo this healthy release.
4. Every check is run even after a failure, so one red run lists everything that is broken.

Its own chat history is the only thing it writes. Unit tests of the scripts: `infra/scripts/tests/*.test.sh` (CI job `infra-scripts`).

### Rollback job

**Runs when:** a build job, the deploy or the smoke job failed. A failed build never reached the cluster: nothing is rolled back.

1. `rollback-k3s.sh` runs `kubectl rollout undo --to-revision` on the deployments whose revision moved, using the record written before the apply. No record: nothing is undone. The record is kept when an undo fails, so the script can be run again by hand.
2. `report-failed-deploy.sh` moves every ticket shipped since the last green CD run (`deploy-tickets.sh`: key in the commit subject, else in the PR branch) to the Linear state « Emergency », adds `Top` and comments the cause, the production state and the run (MAG-184). « Emergency » freezes production until the fix deploys green; the `lift-freeze` job then moves the ticket to Recette, or Done for a Task. A separate incident ticket (`Bug`, Urgent, labels `incident` and `Top`) is opened only when no ticket can carry the freeze or the rollback itself failed. GitHub issues are disabled on this repository, so nothing goes there. Needs the `LINEAR_API_KEY` Actions secret; the run summary carries the same text if the calls fail.
3. The run ends red.

**Freeze (MAG-184):** while a ticket is in « Emergency » or an `incident` ticket is open, the required check `Incident gate` (`infra/scripts/incident-gate.sh`) fails every PR whose title or branch does not carry one of those keys; no Linear answer fails it too. `incident-gate-release.yml` (every 10 min and after each CD run) re-runs the red gates once the freeze lifts, so held PRs merge on their own. The dispatcher delegates the « Emergency » ticket first, over every slot and hold.

**Not reverted:** database migrations and Elasticsearch mappings. The pre-deploy dumps (API and agent) are in `/opt/maggie/backups`.

> Deployment targets k3s, not Docker Compose: manifests live in `infra/k8s/`
> (Deployments, Services, Ingress, ConfigMap, Secret, Kustomization) and
> production reads its environment from the `maggie-env` secret in the
> `maggie` namespace.

---

## Mobile CI (`.github/workflows/mobile.yml`)

**Trigger:** Push/PR with changes to `mobile/**` or `api/contract/**`, and the nightly run.

**Job:** Mobile Unit Tests (release)
- Runtime: Java 17 (Temurin), Gradle
- Command: `./gradlew :app:testProdReleaseUnitTest` — the variant that ships (three CI fixes came from testing `devDebug` while delivering `prodRelease`)

> Path-filtered, so it cannot be a required check (a skipped workflow never reports). The Maestro job on an emulator comes with MAG-98.

---

## Nightly (`.github/workflows/nightly.yml`, MAG-96)

**Trigger:** 02:43 UTC every day, and on demand. Calls `ci.yml` (every job, path filters bypassed) and `mobile.yml`. A failure opens an issue labelled `nightly-failure`, or comments on the one already open. The real-model eval has its own nightly, `eval.yml`.

---

## Branch protection on `main`

Required checks are the job names of `ci.yml`: Detect changes, API Lint (PHPStan), API Tests (PHPUnit), Agent Lint (Ruff), Agent Tests (pytest), Ciqual Lint (Ruff), Ciqual Tests (pytest), Admin Lint (ESLint + TypeScript), Admin Tests (Vitest), E2E Stack (smoke journey), Infra scripts and workflows. The Playwright journeys run inside `E2E Stack (smoke journey)`, traces and videos kept as the `playwright-report-<run>` artifact on failure.

**Rule:** a new job in `ci.yml` is added to the required checks in the same delivery (Settings → Branches → `main`), or auto-merge does not wait for it. Settings need repo admin: an agent token cannot change them.

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

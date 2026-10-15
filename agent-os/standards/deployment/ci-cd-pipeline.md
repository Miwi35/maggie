# CI/CD Pipeline — Maggie v3

## Pipeline Architecture

One line in the Actions list per event (MAG-244), each saying what it is (`run-name`):

| Event | Workflow | Line | Jobs |
|---|---|---|---|
| Push on a PR, marked ready | `ci.yml` (`Pull request`) | `PR #120 · <title>` (`· draft` on a draft) | path detection → lint, tests, mobile unit tests, e2e (ready only), `PR checks passed` (`· draft` on a draft) |
| Merge group of the merge queue | `ci.yml` (`Pull request`) | `Merge queue · <group ref>` | the same jobs on main + the PRs ahead + this PR |
| Merge on `main` | `main.yml` (`Main`) | `main · CI puis déploiement · <commit message>` | `tested` → `ci.yml` called (skipped for a tree already tested) → gate → builds → deploy → smoke (or rollback) → mobile app published |
| Every night | `nightly.yml` (`Nuit`) | `Nuit · CI complète + eval du modèle réel` | `ci.yml` called with no path filter and without the e2e journeys (`suite: no-e2e`), beside the real-model eval |
| Every night, 03:07 in Paris | `nightly-e2e.yml` (`Nightly e2e`) | `Nuit · e2e complets` | `ci.yml` called with `suite: e2e`: every Playwright and Maestro journey, quarantine included, on the three formats; a failed flow retried once; a report per red journey |

No `workflow_run` anywhere: a chained workflow that does not apply shows up as a grey, skipped line. `ci.yml` is also a `workflow_call` target for `main.yml` and `nightly.yml`; in that case `github.event_name` is the caller's event (`push` on main, `schedule` at night).

---

## CI Workflow (`.github/workflows/ci.yml`)

**Trigger:** `pull_request` on `main` (opened, synchronize, reopened, ready_for_review — drafts start a run whose jobs all skip), `merge_group` (the merge queue), and `workflow_call` from `main.yml` and `nightly.yml`. Jobs stay in `ci.yml` rather than behind a `uses:` of their own: a required check is a job name, and a job behind `uses:` is renamed `Caller / Job`. It is the whole pull-request pipeline: the checks below are its jobs, and a job with nothing to do is skipped, not a workflow. `Mobile Unit Tests (release)` (formerly `mobile.yml`) is a job of it. The agent guard and the freeze are not: they live in Cyrus (`cyrus-pilot`, MAG-469).

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

### Merge queue (owner's decision, 9 Oct.)

GitHub's native **merge queue** on `main` replaces the dispatcher's home-made merge train (MAG-243, MAG-262). A public repository on the free plan runs 20 jobs at once, so CI stays targeted (path filters, impacted journeys).

- **Draft: no CI. Ready: the targeted CI** (16 Oct.). A draft's run (title ending `· draft`, concurrency group `…-draft`) skips every job, `Detect changes` included, and its aggregators are named `… · draft`: the required checks stay « Expected » on a draft. `ready_for_review` and every push of a ready PR (group `…-ready`) play lints, unit tests, coverage (the report only when a test ran) and the e2e journeys `impacted.sh` selects; a mobile lot re-plays its failed flows once, and boots the emulator again when it died before any verdict. Cyrus's train rebases the draft it picks, marks it ready (at most 2 ready PRs in CI at once) and merges only on a ready run of its exact head. A `synchronize` on a ready PR behind main runs nothing (`Up to date with main`; aggregators skipped as `… · en retard`, required checks « Expected »): the train rebases it when it is next to merge.
- **The queue tests main + the PRs ahead.** `gh pr merge <n> --squash --auto` puts a green PR in the queue (or arms auto-merge until it is green). GitHub builds a merge group — the head of `main`, the PRs ahead of it, this PR — and runs `ci.yml` on `merge_group` (`checks_requested`). There the path filters and the e2e selection compare `merge_group.base_sha` with `merge_group.head_sha`, not the PR with its base.
- **Merges in order.** A green group fast-forwards `main` to the commit it tested, PR after PR, each one a squash commit. `main.yml` then skips the CI already run on that commit (below) and deploys.
- **A failing entry is removed alone.** Keep « only merge non-failing pull requests » on: a PR merges only on its own group's green. A red group takes its PR out of the queue; the groups behind it are rebuilt without it and re-run.
- **Red main → only the fix merges.** While a ticket is in « Emergency », Cyrus's dispatcher freezes the train: only the fix merges, and the freeze lifts once it is deployed green (MAG-469).
- **Every required check reports on `merge_group`**, or the queue stalls: `Detect changes`, the lint and test jobs (skipped by the filter = green), `E2E Stack (smoke journey)` and `E2E Mobile (phone)` (`if: always()` aggregators), `Infra scripts and workflows` and `PR checks passed`.
- **A skipped required check counts as green on GitHub** (MAG-267): a run cancelled by a newer one, a job skipped because its `needs` failed. Five PRs merged that way with no test run. `ci.yml` therefore ends with **`PR checks passed`** (`infra/scripts/pr-checks-gate.sh`): `if: always()` on every PR and merge group, red when `Detect changes` did not succeed, or when a job failed, was cancelled or was skipped *other than by `Detect changes`' path filter*. A docs-only PR is green: the filter said false. Skipped on a draft (no CI there); GitHub never merges or enqueues a draft. Its `needs` list and the script's job list are kept in step by hand.
- **Added migrations come after main's** (`infra/scripts/migrations-order.sh`, step of `API Lint (PHPStan)`). Doctrine runs a migration it has not seen even if older than the last one executed, so production applies migrations in merge order while a fresh database (e2e, restore) applies them in version order. A change may only add versions newer than the newest of its base: decisive on the merge group (base = the group's parent: the group of the PR ahead, or `main`), a hint on the PR. Behind it, a group that adds a migration plays the whole e2e (`api/migrations/**` is transversal), which migrates a fresh database to the group's head.
- The repository settings (ruleset with the merge queue on `main`, squash, required checks) are the owner's.
- No clock matrix, but a fixed clock: the e2e jobs run at one reference instant (`e2e/clock.sh ci`, a Wednesday noon in Paris), never on the week they happen to run in. Override it with the repository variable `E2E_NOW`; the rest of `E2E_NOW` / `e2e/clock.sh` is a manual tool (`global/e2e-environment.md`, *The clock*).

### Agent guard (MAG-469)

Which agent PR a human must merge, and the freeze after a failed deploy, are decided by Cyrus (`cyrus-pilot`: `guard/guard.py` and the dispatcher), not by this repository's workflows.

### Batch pull requests (MAG-262)

The dispatcher groups up to 3 ready PRs into one **batch PR**: branch `train/batch-<n>`, label `batch` **set when the PR is created** (`gh pr create --label batch`: adding it later starts no run), a description that lists each PR as a list item or table row whose first `#<number>` names it, and one `Fixes MAG-x` per ticket. One CI, one deployment. The original PRs stay **open** while the batch is judged (a listed PR that is not open refuses the batch); the dispatcher closes them with a comment pointing at the batch once it has merged.

Cyrus's guard judges a batch PR by PR, with the rules each would meet alone.

Merging: `deploy-tickets.sh` also reads the `Fixes MAG-x` lines of the squash commit's body (the PR description), since the subject and branch of a batch name no ticket: a failed deploy of a batch freezes every ticket it carries. Linear's GitHub integration moves a ticket on its own from the same `Fixes MAG-x` magic word, when the PR that carries it merges. A batch never carries the fix of an « Emergency » ticket: the dispatcher opens that fix alone.

---

## Main pipeline (`.github/workflows/main.yml`, MAG-244)

**Trigger:** push on `main`. One run per merge: `tested` → `ci` (calls `ci.yml`, whose `Detect changes` filters on a push as it did when CI ran by itself; skipped when the tree was already tested) → `gate` → `changes` → builds → `deploy` → `smoke` → `publish-mobile`, or `rollback`. A red CI ends the run before the gate: nothing deploys.

**One at a time:** the workflow's concurrency group `main-pipeline` queues runs and never cancels one that started. GitHub keeps one pending run per group and cancels the one a newer pending run replaces: a commit merged while another one is in the pipeline waits, and if a third commit arrives first the second never runs, which is right, the third contains it. The price against the old chain: a queued commit's CI starts after the running pipeline ends, not beside its deploy. The last merge is always the one deployed, and versions never deploy out of order: with the merge queue, quick successive merges deploy one after the other, an intermediate commit folded into the next one when it was still waiting. `ci.yml`'s own group (`ci-Main-<ref>`) differs from this one: a group shared by caller and callee would deadlock.

### The CI of main is not repeated on a tree already tested (MAG-262)

The merge queue moves `main` to the very commit its merge group CI passed; a PR merged outside the queue may land exactly the file tree its own CI ran on. Running the same suite again on `main` costs ~20-25 minutes per merge.

- The first job, `tested` (`infra/scripts/tested-tree.sh`), skips the `ci` job when the latest finished `Pull request` run of event `merge_group` on the commit itself is `success` and its `Detect changes` succeeded. Otherwise, when **all** hold: the commit is the merge commit of one merged PR; its tree (`git/commits/<sha>` → `tree.sha`, the same value as `git rev-parse <sha>^{tree}`) equals the tree of the PR's head commit; the latest finished `Pull request` run on that head is `success` and really ran (its `Detect changes` job succeeded).
- The CI of a PR tests the merge of its head with the base of the moment; a base that moved and came back to the same tree is not told apart, which is acceptable since that run was green on a tree identical to the one that lands.
- Anything else runs the suite in full, as before: a branch merged out of date (the trees differ), a direct push, a revert not made through a PR, a red or missing run, an API that does not answer. Doubt runs the CI.
- The reason is in the log and the summary of `tested`. A skipped `ci` counts as a pass for the `gate` (`needs.ci.result == 'skipped'` **and** `skip == 'true'`); a red or cancelled CI still stops the run. Path detection, builds, deployment and smoke tests are unchanged, and the base of the change detection (the last successful run of `main.yml`) is too.
- A `tested` job that dies does not skip the CI: `ci` runs on `always()`.
- `nightly.yml` and `nightly-e2e.yml` call `ci.yml` directly and, between them, keep the whole suite.
- **Main does not replay the e2e journeys (MAG-484):** `main.yml` calls `ci.yml` with `e2e: smoke` — lint, unit tests and `E2E Stack (smoke journey)` with one stack playing the smoke journey only (`E2E smoke`); no Playwright journey, no e2e APK, no e2e-flavor unit tests, no Maestro (`E2E Mobile (phone)` passes, nothing wanted). The PR played its journeys, the night plays them all.

### Gate (MAG-189)

The gate runs after the CI of the run, which takes minutes: a newer commit may be the head of `main` by then. Without it, such a run would build the head again (new digests, same tag), fail the digest assertion and roll back for nothing.

- The `gate` job runs `infra/scripts/should-deploy.sh`: the run deploys only if its commit is the head of `main` right now and no earlier successful run of `main.yml` exists for that SHA. Otherwise every other job is skipped: no build, no deploy, no incident, no rollback. A stopped run is never left green, because a successful run of `main.yml` is the record that a commit was handled (the "already deployed" check, the base of the change detection): a stop cancels its own run (`actions: write`). It fails closed (exit 1) when the GitHub API cannot be read.
- Everything is built, tagged, copied and verified on `env.RELEASE_SHA` (= `github.sha`, the merged commit).

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
1. `scp` `infra/k8s/`, `infra/scripts/deploy-k3s.sh`, `backup-k3s.sh`, `migrate-k3s.sh`, `rollback-k3s.sh` and `verify-digests.sh` to `/opt/maggie/`
2. Run `deploy-k3s.sh <RELEASE_SHA>`, which does:
   1. **Preflight** — kubectl reachable, shared `postgres`/`elasticsearch`/`rabbitmq` ready in the `shared` namespace
   2. **Backup** (`backup-k3s.sh`, MAG-188) — `pg_dump | gzip` of **both** databases into `/opt/maggie/backups`: `maggie_predeploy_<ts>.sql.gz` (`DATABASE_URL`) and `maggie_agent_predeploy_<ts>.sql.gz` (`AGENT_DATABASE_URL`: memory, messages, contexts, directives, proactions, personality). Last 10 of each kept; a failed or empty dump, either one, aborts the deploy
   3. **Apply** — record the current revision of every deployment (`/opt/maggie/state/pre-deploy-revisions`) and the digest every image runs (`pre-deploy-digests`), bump the image tags in `kustomization.yaml` (only for images actually published for that SHA), then **migrate** (`migrate-k3s.sh`, MAG-360: a one-shot job on the new php image runs the agenda dedupe and `doctrine:migrations:migrate` *before* any pod rolls — `Meal` extends `Event`, so new code on the old schema broke the cron and the API; a failure stops the deploy with nothing rolled, so a migration must stay compatible with the release still serving: additive, drop in a later release; the job runs before the ConfigMap is applied, so a console that needs a new env var to boot must ship it one release earlier), then `kubectl apply -k`
   4. **Wait** — `rollout status` on php, nginx, worker, cron, agent, ciqual, mercure
   5. **Post-deploy** — migrations again as a safety net, normally a no-op (no `cache:clear`: the image ships a warmed cache), Elasticsearch mapping update and reindex
   6. **Verify** — pod list plus an HTTP check on `https://maggieai.fr/api/docs`

### Smoke job (MAG-106)

**Requires:** deploy succeeded. As the technical account `smoke@maggieai.fr`, whose own data is all it writes (MAG-253).

0. **Digest assertion (MAG-96)**, first: `infra/scripts/verify-digests.sh` checks that every pod of php, worker, cron, nginx, agent and ciqual runs the digest the build job pushed in this run — or, for a service this run did not rebuild, the digest it ran before the deploy (`pre-deploy-digests`). A service on a stale tag fails the smoke job, hence the rollback. A digest names an index, not an image: a rebuild with unchanged content pushes a new index for the same image, and the pod keeps reporting the first one. The script therefore also accepts a pod whose image carries the expected digest among its `repoDigests` (`sudo k3s crictl inspecti` on the node, MAG-146). A green rollout is not enough: eleven past fixes were one stale-tag redeploy. Expected digests come from the build jobs' `digest` output; the build jobs are therefore `needs` of the smoke job.
1. SSH to the VPS, `bin/console app:smoke:token` in the php pod prints a JWT (account created on first use; token masked in the public log).
2. `infra/scripts/smoke-prod.sh` checks: public URLs (`/`, `/admin` with and without trailing slash, no `http://` redirect), API, agent and Mercure health (+ Mercure CORS), `/_mcp` `tools/list`, `app:elasticsearch:status --check`, and one question to Maggie that only a tool can answer: the suite wipes the account's chat history first (`DELETE /agent/smoke/history`, accepted only for that account's token — a history of identical questions made the model answer from memory and rolled production back twice), creates a test agenda with one event of random title, asks for that title, then deletes the agenda and the history.
3. On success the smoke job deletes the rollback record, so a later deploy that never reaches the cluster cannot undo this healthy release.
4. Every check is run even after a failure, so one red run lists everything that is broken.

Its own chat history is the only thing it writes.

### Rollback job

**Runs when:** a build job, the deploy or the smoke job failed. A failed build never reached the cluster: nothing is rolled back.

1. `rollback-k3s.sh` runs `kubectl rollout undo --to-revision` on the deployments whose revision moved, using the record written before the apply. No record: nothing is undone. The record is kept when an undo fails, so the script can be run again by hand.
2. `report-failed-deploy.sh` moves every ticket shipped since the last green run of `main.yml` (`deploy-tickets.sh`: key in the commit subject or in a `Fixes MAG-x` line of its body, else in the PR branch) to the Linear state « Emergency », adds `Top` and comments the cause, the production state and the run (MAG-184). « Emergency » makes Cyrus's dispatcher freeze the train until the fix deploys green, then lift it (MAG-469). A separate incident ticket (`Bug`, Urgent, labels `incident` and `Top`) is opened only when no ticket can carry the freeze or the rollback itself failed. GitHub issues are disabled on this repository, so nothing goes there. Needs the `LINEAR_API_KEY` Actions secret; the run summary carries the same text if the calls fail.
3. The run ends red.

**Freeze (MAG-184, MAG-469):** while a ticket is in « Emergency », Cyrus's dispatcher freezes the train (only the fix merges) and lifts the freeze once the fix is deployed green. No workflow of this repository holds PRs. The dispatcher delegates the « Emergency » ticket first, over every slot and hold.

**Not reverted:** database migrations and Elasticsearch mappings. The pre-deploy dumps (API and agent) are in `/opt/maggie/backups`.

> Deployment targets k3s, not Docker Compose: manifests live in `infra/k8s/`
> (Deployments, Services, Ingress, ConfigMap, Secret, Kustomization) and
> production reads its environment from the `maggie-env` secret in the
> `maggie` namespace.

---

## Mobile unit tests (job `Mobile Unit Tests (release)` of `ci.yml`)

**Runs:** when `Detect changes` sees `mobile/**`, `api/contract/**`, `agent/contract/**` or `ci.yml` change, and on the nightly run (every job, no filter). It was a workflow of its own (`mobile.yml`, a second line per push; a path-filtered workflow cannot be required, a skipped job can).

- Runtime: Java 17 (Temurin), Gradle
- Command: `./gradlew :app:testProdReleaseUnitTest` — the variant that ships (three CI fixes came from testing `devDebug` while delivering `prodRelease`)

The emulator journeys are the `E2E Mobile` jobs of `ci.yml` (see *Branch protection*).

---

## Mobile publication (job `Publish the mobile app` of `main.yml`, MAG-254)

Every merge on `main` that touches `mobile/` puts the signed `prodRelease` on the owner's phone, no cable: **Firebase App Distribution**, and the Firebase App Tester app notifies each new version (project `maggie-487318`, the Google sign-in one). A recette on the phone therefore runs on the version just deployed.

- **When:** after smoke, in the same run. `Detect changes` computes `mobile` with `infra/scripts/mobile-release.sh range` against **the tag of the last publication**, not the last deploy: a mobile-only merge deploys nothing yet is published (smoke is skipped, the job still runs), and a deploy that leaves `mobile/` alone publishes nothing. A red build, deploy or smoke (any ancestor) stops it.
- **Build:** `./gradlew :app:assembleProdRelease -PVERSION_CODE=<run number> -PGIT_SHA=<short SHA>`. `versionCode` is the `main.yml` run number (it only grows); `versionName` is `0.1.0-<short SHA>`, and `BuildConfig.GIT_SHA` is shown in Réglages › À propos (`Commit <SHA>`). A local build keeps `versionCode 1` and the SHA `local`. The job fails if the APK is not the signed one.
- **Release notes:** the title of every commit (= PR) that touched `mobile/` since the last publication.
- **Record, and how a recette knows which version to expect:** once Firebase accepted the build, the job creates the GitHub release **`mobile-<versionCode>`** on the deployed commit (notes = the release notes, not the latest release, no APK attached). The newest `mobile-*` tag is the published version: `gh release list` / `git tag --list 'mobile-*' --sort=-version:refname`, its title carries the short SHA. A failed upload leaves no tag: the next merge that touches `mobile/` publishes the whole range again.
- **Never turns a release red:** the job is not a dependency of `rollback`; a failure is a red job and nothing else.
- **Secrets** (public repository, public logs): `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (the release key, whose SHA-1 is registered with Google), `GOOGLE_SERVICES_JSON` (base64, rebuilt into `mobile/app/google-services.json`, git-ignored), `FIREBASE_APP_DISTRIBUTION_KEY` (service account JSON, the only role *Firebase App Distribution Admin*). Each one is read in the environment of the step that needs it, written to a file under `RUNNER_TEMP` with mode 600, deleted at the end of the job (`if: always()`), never echoed. Variables: `FIREBASE_ANDROID_APP_ID`, and **`FIREBASE_TESTERS`** (e-mails, comma-separated) and/or `FIREBASE_TESTER_GROUPS`: without one of them the build would be uploaded and nobody told, so the job fails instead.
- **Without the pipeline:** `task mobile:install` still installs on a phone plugged in.

---

## Nightly (`.github/workflows/nightly.yml`, MAG-96, MAG-244)

**Trigger:** 02:43 UTC every day, and on demand (`what`: everything, ci or eval; `only`: one eval scenario). Two parallel jobs: `ci` calls `ci.yml` (every job, path filters bypassed, Mobile Unit Tests included) and `eval` runs the prompt-lab scenarios on the real model (formerly `eval.yml` at 03:17). A failure of the CI opens a Linear `Bug` (Urgent, label `nightly-failure`), or comments the one already open (`infra/scripts/alert-ticket.sh`, MAG-151) — GitHub issues are disabled on this repository, and the alert job needs the `LINEAR_API_KEY` Actions secret (without it the job fails, so the red run is the signal); the eval is billed and judges tone, so it never alerts and is never a required check. The e2e journeys are not in this run (`suite: no-e2e`): they have their own night.

## Nightly e2e (`.github/workflows/nightly-e2e.yml`, owner's decision of 8 Oct.)

**Trigger:** 03:07 in Paris every day — two cron lines (01:07 and 02:07 UTC) and a `when` job that keeps the one matching Paris's offset that day (summer or winter time) — and on demand (`workflow_dispatch`). It calls `ci.yml` with `suite: e2e` (only `Detect changes` and the e2e jobs run, every journey, quarantine included, the three device formats) and `retry_failed_journeys: true`: a Maestro flow that fails or never ran plays once more, and one that passes then is *flaky* (Playwright retries once on its own in CI, with the same meaning). It deploys nothing. Each e2e lot uploads its verdict (`e2e-verdict-*`, `scripts/e2e/verdict.sh`); `Nightly e2e report` (`scripts/e2e/nightly-report.sh`) turns them into the summary: for each red journey its area in `e2e/impact-map.yml`, the last commit that touched that area and the lot it failed in; the flaky journeys as candidates for the quarantine; the journeys in quarantine that still fail. A red night (a non-quarantined journey failed, or the e2e jobs failed outside any journey) opens a Linear `Bug` labelled `nightly-e2e-failure`, or comments the one already open (`infra/scripts/alert-ticket.sh`, as for `nightly-failure`). A green night writes its summary and nothing else.

---

## Branch protection on `main`

Required checks are the job names of `ci.yml`, which is the pull-request workflow (MAG-244: the names did not change when the jobs moved into one workflow — a required context is a job name; `Mobile Unit Tests (release)` can now be added to the list, being a skippable job rather than a path-filtered workflow): Detect changes, API Lint (PHPStan), API Tests (PHPUnit), Agent Lint (Ruff), Agent Tests (pytest), Ciqual Lint (Ruff), Ciqual Tests (pytest), Admin Lint (ESLint + TypeScript), Admin Tests (Vitest), E2E Stack (smoke journey), E2E Mobile (phone), Infra scripts and workflows, PR checks passed. Read the live list with `gh api repos/<owner>/<repo>/branches/main --jq .protection.required_status_checks.contexts` (works without admin). `E2E Stack (smoke journey)` is an aggregator (MAG-180): it waits for the `E2E shard i/n` jobs, each with its own stack and a share of the selected Playwright journeys (`--shard=i/n`, two workers), and fails if any shard does. It passes without a stack when the PR touches no web journey, so the required name never changes. The smoke journey, the journeys' lint and typecheck and `e2e:eval:check` run in shard 1 only.

**Targeted e2e (owner's decision, 8 Oct.):** a PR plays only the journeys its files touch, plus the critical core of each platform that plays (sign-in, basic chat, smoke) — the whole suite plays once a night. The one map is `e2e/impact-map.yml` (per journey: areas and paths as globs, `critical`, `quarantine: {since, reason}`); `scripts/e2e/impacted.sh select` reads it in `Detect changes` (outputs `e2e`, `mobile`, `web_specs`, `web_lots`, `mobile_lots`) and `impacted.sh check` in `Infra scripts and workflows` fails on a journey missing from the map, a critical journey in quarantine or an undefined area. **Only code is tested (owner's decision, 14 Oct., MAG-470):** tooling, infrastructure and tool-config files are `ignored` and play no journey in a PR — `infra/**`, `.github/**`, the `Taskfile`s (root and `admin|agent|api|ciqual|mobile|wt`), `wt/**`, `scripts/**`, `.docker/*/` except `e2e`, `docker-compose.yml`, `agent-os/**`, `.claude/**`, `CLAUDE.md`; a PR that touches only those runs no e2e job and the required checks `E2E Stack (smoke journey)` and `E2E Mobile (phone)` pass without playing anything, as for a doc PR. The night still plays everything and catches what they break. **Safety rule (code):** a transversal file (`api/modules/core/**`, `api/contract/**`, `api/config/**`, `api/src/**`, API and agent auth, the e2e harness and stack (`e2e/Taskfile.yml`, `docker-compose.e2e.yml`, `.docker/e2e/**`), Composer and Python dependencies) plays everything; a client's own login and npm or Gradle dependencies play that client's suite only; a file of `e2e/` no journey claims, or a code file the map does not know, plays the suite of its platform. Web lots: one per ten spec files, four at most. Mobile lots: the five shards of `shards.txt` (balanced on measured flow times) that kept a selected flow. **Line map (spec « Sélection e2e par couverture »):** the nightly e2e runs with `coverage: true` (`E2E_COVERAGE=1`, mobile on the phone only); each lot runs `scripts/e2e/coverage/collect-lot.sh` (hooks: `task e2e:coverage:collect`, `scripts/e2e/coverage/collect.d/*.sh`) and uploads `e2e-coverage-raw-*`, and `E2E line map` builds `e2e-coverage-map.json` (`scripts/e2e/coverage/build-map.py`: per file, line ranges with a hex bitmask of the journeys that ran them) into the artifact `e2e-coverage-map` (7 days). `E2E line map` builds no map unless every lot left its completion mark (`_lots/<lot>.ok`, `build-map.py --expect`): a partial map would play too few. `Detect changes` downloads the map from the latest completed nightly run of main that holds one (red ones too: a failing journey does not make the others' coverage wrong, and the map exists only when every lot left its coverage) and passes it with the change's `git diff -U0` to `impacted.sh select`: a file in `coverage.sees` of the map whose every hunk (its old-side lines; around a pure addition, the lines on either side) was run by some journey plays those journeys (`why: coverage`) plus the core — transversal code included; a hunk no journey ran, any other file or a new file keeps the zones. No map, a map older than 3 days or any failure fetching it: the zones alone, never a red check. Coverage never changes the night's verdict. **Quarantine:** a journey in quarantine still plays, but its failure only warns in the job summary (`scripts/e2e/verdict.sh` reads Playwright's JSON report and Maestro's JUnit); on Maestro the quarantined flows play in a second run of their shard under a 10-minute limit (`scripts/e2e/mobile-journeys.sh`). A new journey goes into the map in the same PR. On failure `E2E merged report` merges the shards' blob reports into the one `playwright-report-<run>` artifact, traces and videos included. Images are built from the layers of the last image pushed under `maggie-e2e-<service>:cache`, so editing a Dockerfile rebuilds only from the edited line down. The stack starts faster than it builds: the admin bundle is cached (`e2e-admin-dist-v1-<hash of the admin sources>`, so a PR that leaves the admin alone skips `npm ci` and `vite build`; bump `v1` when the build environment changes), the agent starts before nginx (its MCP client reconnects lazily), Elasticsearch starts first with a small heap, and the teardown removes containers with `--timeout 1`. List a cache key's sources one by one: an `admin/**` glob walks `node_modules`, 5 s at restore and again at save.

`E2E Mobile (phone)` (MAG-213) is the same kind of aggregator: it waits for `E2E Mobile APK` (the `e2e` flavor, compiled once, uploaded as an artifact), `E2E Mobile unit tests (e2e flavor)` and the `E2E Mobile journeys (<device> <i>/<n>)` matrix (only when `Detect changes` selected a flow), passes at once otherwise, and fails when any of them failed or was cancelled. The matrix job itself is never required: skipped, it would report the literal name `E2E Mobile journeys (${{ matrix.device.name }} ${{ matrix.lot.index }}/…)`, never `(phone)`. The Maestro flows run as up to three shards (MAG-233, `e2e/mobile/shards.txt`, one emulator and one stack each, in parallel; `task e2e:mobile:lint` fails on a flow no shard names), from the APK the build job uploaded, on an emulator restored from an AVD snapshot cached per device profile (`avd-v1-34-google_apis-x86_64-<profile>`; bump `v1` when the emulator options change). Each shard uploads its own `maestro-report-<device>-<i>-<run>` on failure. The nightly widens the same matrix to phone, foldable and tablet.

**Rule — the jobs of `ci.yml` stay in `ci.yml` (MAG-244):** a job moved behind a reusable workflow (`uses:`) is reported as `<caller job> / <job>`, which is not the name branch protection waits for. Only `main.yml` and `nightly.yml` call `ci.yml`, never the other way round. A called workflow may not ask for more token permissions than its caller grants, even for a job it skips: the callers' `ci` jobs hold the ceiling of `Coverage report` (`pull-requests: write`).

**Rule:** a new job in `ci.yml` is added to the required checks in the same delivery (Settings → Branches → `main`), or auto-merge does not wait for it. Settings need repo admin: an agent token cannot change them.

**Rule — a required check is always reported (MAG-213):** a required name must be reported on every PR, or GitHub waits for it forever and blocks every PR that does not trigger it. Never require a job that is path-filtered at the workflow level (`on.paths`, as `mobile.yml` was) or whose name holds a `matrix.*` expression and that can be skipped by its `if`. Put the work behind `Detect changes` and require a job with a fixed name that runs `if: always()` and ends green when nothing was needed (`E2E Stack (smoke journey)`, `E2E Mobile (phone)`).

**Owner, once the PR is merged** (admin only; adds the check without dropping the others):

```
gh api repos/<owner>/<repo>/branches/main/protection/required_status_checks/contexts \
  -X POST -f 'contexts[]=E2E Mobile (phone)'
```

## GitHub Actions Secrets

| Secret | Purpose |
|--------|---------|
| `VPS_HOST` | IP or domain of production VPS |
| `VPS_USER` | Deploy user on VPS |
| `VPS_SSH_KEY` | Private SSH key for deployment |
| `GITHUB_TOKEN` | Auto-provided, used for GHCR login |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Release key of the Android app (mobile publication) |
| `GOOGLE_SERVICES_JSON` | `google-services.json` of the Firebase app, base64 |
| `FIREBASE_APP_DISTRIBUTION_KEY` | Service account key, role Firebase App Distribution Admin |

Repository variables: `FIREBASE_ANDROID_APP_ID`, `FIREBASE_TESTERS` / `FIREBASE_TESTER_GROUPS`.

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

### Third-party images: only from the project's mirror in CI (owner's decision, 10 Oct., extended 12 Oct.)

GitHub runners share their IPs: Docker Hub's anonymous pull limit (`toomanyrequests`) took every e2e job, API test and image build down for hours, twice (9–10 Oct.), then `public.ecr.aws`, which had replaced it for the official images, hit its own anonymous data limit (`Data limit exceeded`, 12 Oct.). So every Dockerfile `FROM`/`COPY --from`, every compose file CI, the e2e stack or the worktree checks start, and every workflow pull from **`ghcr.io/miwi35/mirror/<name>:<tag>`** — official images (`php`, `postgres`, `node`…) and others (wiremock, mercure, uv, BuildKit — every `docker/setup-buildx-action` sets `driver-opts: image=ghcr.io/miwi35/mirror/buildkit:buildx-stable-1`) alike. Allowed besides: the project's own images (`ghcr.io/miwi35/…`, local `maggie…`) and `mcr.microsoft.com` (Playwright, no anonymous limit). CI tools come from their GitHub releases, pinned by sha256. The packages are public: pulls are anonymous, no `docker login` needed. **Adding an image or a tag:** add a line to `.github/mirror-images.txt`, run `Mirror images` (`.github/workflows/mirror-images.yml`, also weekly), make the new package public (package settings → Change visibility), then reference the copy. `infra/scripts/docker-hub-guard.sh` (job `Infra scripts and workflows`) is red on any pull from Docker Hub, `public.ecr.aws` or another registry, and on a mirror reference missing from the list; a justified exception goes in `.github/docker-hub-allowlist.txt` with its reason. Production manifests (`infra/k8s/`) still pull from Docker Hub — a separate decision.

---

## Build Caching

All images use GitHub Actions cache (`type=gha`) with dedicated scopes:
- `php` scope for PHP image layers
- `agent` scope for Agent image layers
- Nginx reuses PHP image directly (no separate cache)

---

## Deployment Flow Summary

```
1. A PR merges into main
2. `main.yml` runs: CI (`ci.yml` called) first, skipped for a tree already tested
3. Gate (head of main, not already deployed) → change detection
4. It builds the Docker images (PHP + Agent in parallel, then Nginx)
5. Images pushed to GHCR, tagged with the full SHA
6. Deploy job (`production` environment): SSH to the VPS, `deploy-k3s.sh <sha>`:
   backup → migrate → `kubectl apply -k` → rollout status → post-deploy → verify
7. Smoke job on production; a red build, deploy or smoke triggers the rollback
8. k3s rolls the pods; Traefik (k3s Ingress) routes to them
```

# The E2E Environment

An isolated stack, deterministic data, a login that does not go through Google,
and stand-ins for every external service — the model included. One per git
worktree, so parallel agents never collide. Built by MAG-94, with the fake LLM
and the eval suite from MAG-95 and the browser harness from MAG-97; the
emulator harness that sits on top is MAG-98.

Read this before writing anything that runs against the stack. The Definition of
Done itself lives in [testing.md](testing.md).

## Commands

| Command | What it does |
|---|---|
| `task e2e:up` | Start the stack (build locally, install, clear cache, migrate), print the URL |
| `task e2e:seed` | Reset the database to the fixture set, empty the agent's own tables, rebuild and refresh the search indices |
| `task e2e:smoke` | Run the smoke journey (HTTP) against the running stack |
| `task e2e:web` | Reseed, then run the Playwright journeys for the admin |
| `task e2e:web:lint` | ESLint on the journeys — also part of `task lint:all` |
| `task e2e:web:typecheck` | Type-check the journeys without running them |
| `task e2e:web:shell` | A shell in the Playwright container |
| `task e2e:admin:build` | **After changing admin code** — see below |
| `task e2e:eval` | Replay the prompt-lab scenarios on the **real** model — see below |
| `task e2e:eval:check` | Check those scenarios parse, without calling the model |
| `task e2e:test:api` | PHPUnit **inside** this worktree's stack |
| `task e2e:down` | Remove containers, network and volumes |
| `task e2e:url` | Print the stack's URL |
| `task e2e:cache:clear` | **After changing PHP code or config** — see below |
| `task e2e:console -- <args>` | `bin/console --env=e2e` |
| `task e2e:logs` / `task e2e:logs:dump` | Follow / print the logs |
| `task e2e:psql`, `task e2e:sh -- <service>` | Debugging |

**`APP_DEBUG=0` means the compiled service container is never invalidated.** The stack
mounts your worktree, so a change to a service, a route or a config file is invisible
until `task e2e:cache:clear`. Symptom: your change appears to do nothing at all.

**The admin is a built bundle, not a dev server.** nginx serves `admin/dist`,
produced once by the `admin-build` one-shot; Compose will not re-run it just
because you edited `admin/src`. `task e2e:admin:build` is the SPA's
`cache:clear`, with the same symptom when you forget it.

Never run `docker compose -f docker-compose.e2e.yml` by hand: the project name
and the resolved host port both come from the Taskfile, and both are what keep
stacks apart.

## Start-up time in CI (MAG-135)

The job used to spend ~265 s in `task e2e:up`: four images built from scratch on
every run, `composer install` and `npm ci` from nothing, and every step waiting
for the previous one. Now:

- **Images are built once per set of build inputs.** `e2e/images.sh` tags each
  one `ghcr.io/miwi35/maggie-e2e-<service>:<hash>`, the hash covering the files
  its Dockerfile copies and the resolved `build:` stanza of the Compose file
  (target, args, UID/GID). CI pulls the
  tag, and builds and pushes it only when it does not exist. php and worker share
  one image. Editing `.docker/php/`, `.docker/nginx/`, `.docker/python/Dockerfile`,
  `.docker/ciqual/Dockerfile` or `ciqual/pyproject.toml` is what makes a run
  build; WireMock stubs, Postgres init and everything under `api/`, `agent/` and
  `admin/` do not, because they are mounted. A tagged image is never refreshed
  by itself: base images such as `php:8.4-fpm-alpine`, `composer:latest` or
  `uv:latest` move only when one of those inputs changes.
- **Locally nothing changes.** The Compose file reads `E2E_IMAGE_<SERVICE>` and
  falls back to `build:`; only CI sets it, with `E2E_PREBUILT=1` so a missing
  image fails instead of being rebuilt.
- **Dependencies are restored by lockfile** — `api/vendor`, `admin/node_modules`,
  `e2e/web/node_modules` and the agent's uv cache (`.e2e-cache/uv`, which is also
  what makes the agent's start-up install a copy). Their keys are separate from
  the other CI jobs': the stack installs in containers, the admin's in alpine,
  whose native binaries are not the runner's.
- **Start-up overlaps.** `up` starts the API, then boots admin-build, nginx, the
  agent and Elasticsearch *while* composer, `cache:clear` and the migrations run,
  and ends with one `up --wait` on the healthchecks. php only needs Elasticsearch
  *started*; the final wait needs it healthy. Do not make php wait
  for it again, and do not replace that last command with a bare `up`: Compose
  would run `admin-build` a second time.

Adding a service with its own Dockerfile: give it `image: ${E2E_IMAGE_<SVC>:-…}`
in the Compose file and a case in `e2e/images.sh`.

## In a git worktree

`task api:test` runs `docker compose exec` against the **dev** stack, which
mounts the main checkout — in a worktree it tests the wrong code. Use
`task e2e:test:api`, which runs against the stack mounting *this* worktree. It
creates the PHPUnit database first, since the e2e stack has never made one.

One difference worth knowing: PHPUnit forces `APP_ENV=test`, but Symfony's
Dotenv never overwrites a variable the environment already defines — so the
container's `ELASTICSEARCH_URL`, `MERCURE_URL`, `MERCURE_JWT_SECRET` and
`APP_SECRET` win over `api/.env.test`. Harmless today (the `test` environment
uses the in-memory Mercure hub and an `in-memory://` async transport, and the
`elasticsearch` group is excluded), but if you write a test that depends on one
of those values, check it under both runners.

The stack is safe to run beside the dev stack and beside another worktree's:

- `COMPOSE_PROJECT_NAME` is derived from the worktree's absolute path
  (`maggie-e2e-<folder>-<hash>`), so containers, network and volumes never
  collide. Two worktrees of the same branch still differ, because the hash
  covers the path.
- The only published port is Traefik's, and it is ephemeral. `task e2e:url`
  resolves it; nothing may hard-code it.
- Traefik is constrained to `Label('maggie.stack','<project>')`, and the dev
  Traefik to `maggie.stack=dev`. **Any new service with Traefik labels needs the
  matching `maggie.stack` label**, or it is invisible to its own router and
  visible to somebody else's.

## The `e2e` Symfony environment

`APP_ENV=e2e`, `APP_DEBUG=0` — close to production on purpose, because several
past regressions were prod-only (container compilation, cache pools,
serialization). Defaults come from `api/.env.e2e`; the Compose file overrides
what depends on the resolved port.

It is not the PHPUnit `test` environment, and must not become it: `test` carries
`dbname_suffix`, `in-memory://` transports and a synchronous bus, so an e2e run
on it would exercise neither RabbitMQ, nor the worker, nor Mercure.

Code that must only exist in e2e goes under `modules/<module>/src/E2e/`, which
every module's `services.yaml` excludes from its `resource:` glob and re-adds
under `when@e2e`. Routes come from registered controller services
(`resource: routing.controllers` in `config/routes.yaml`), so a controller that
is not registered has no route — that is the whole mechanism.

## The test login

```
POST /api/auth/e2e/login
X-E2E-Token: <E2E_LOGIN_TOKEN>
{"email": "e2e@maggie.local"}
```

Returns `token`, `refreshToken`, `mercureToken` and `user` — the same shape as
the Google callback, so no client takes an e2e-only branch.

Guarded three times: the service exists only under `when@e2e`, the controller
throws `NotFoundHttpException` outside `e2e`, and the token must match.

`E2eSurfaceAbsenceTest` boots the `prod`, `dev` and `test` kernels and asserts
the route is not in the router, the seed command is not in the console
application, and neither service is in the container. **Anything else you add
under `src/E2e/` belongs in that test** — the seed command is there because it
truncates every table and has no route to be absent from.

## Seeded data

`task e2e:seed` truncates every table, loads `api/fixtures/e2e/*.yaml` in file
name order, and rebuilds the Elasticsearch indices.

Dates are anchored, never relative and never literal:

```yaml
startAt: '<e2eDate("+2 days 09:00")>'
year: '<e2eYear()>'
```

`app:e2e:seed --now=<ISO8601>` moves the anchor; it defaults to today at
midnight **in Paris** (`Europe/Paris`, the test user's time zone — the dashboard,
the reminders and the daily score reason in local time, and a UTC midnight would
leave the seed on yesterday between 00:00 and 02:00 there). `--now` is snapped to
the Paris midnight of its Paris day; a value without offset is read in Paris.
That is what lets a journey assert both "this week" and an exact value.
A literal date in a fixture makes every "upcoming" query empty; a
`+2 days` computed from `date()` makes the fixture pass on a Tuesday and fail
across a month boundary.

Write times of day as wall clock (`12:00`, `+2 days 18:00`), not as `+12 hours`:
an elapsed-time offset means different wall-clock times on the two days a year
Paris changes its clocks. Mind that the API
itself runs with `date.timezone = UTC`: code that calls `new DateTimeImmutable()`
(finance period, upcoming events) changes day at 02:00 Paris, so on the first of a
month between 00:00 and 02:00 a finance journey would see the seed's month ahead
of the API's.

**Leave `--now` alone unless you know why you are moving it.** Production code
derives its period from the real clock — `FinanceDashboardController`,
`DailyScoreController`, `EnvelopeRepository::findForPeriod`,
`GetUpcomingEventsTool` all call `new DateTimeImmutable()`. Anchor the seed in
the past and every one of them returns an empty list while the data sits there,
which is the same silent failure as a literal date, applied to the whole
dataset. CI deliberately does not pin it.

**What must not be anchored.** Anything the *production code* compares to the
real clock: an OAuth token expiry, a bank consent validity, anything gating a
`isExpired()` / `isUsable()` check. Anchored, a run with `--now` in the past
makes them look expired — and the failure is indirect: Google's client library
refreshes an "expired" token by calling `oauth2.googleapis.com` directly, an URL
no base URL redirects, so the e2e stack silently reaches the real internet. Use
a fixed far-future literal instead:

```yaml
googleTokenExpiresAt: '<(new \DateTimeImmutable("2099-01-01T00:00:00+00:00"))>'
```

Still deterministic — it is a constant — and it never expires.

ULIDs carry a timestamp, so they differ between runs even with identical data.
The seed writes `api/var/e2e/seed-manifest.json` mapping every Alice reference
to its id — address rows through it rather than hard-coding ids, and rather than
opening a `setId()` on entities purely for tests.

Adding data: put it in the file for its module, give it a reference starting
with `e2e_`, and extend the counts in `E2eSeedCommandTest`. That test runs in
the normal API suite and is what catches a fixture broken by a renamed property.

## External services

Simulated by one WireMock container — see
[.docker/e2e/wiremock/README.md](../../../.docker/e2e/wiremock/README.md).

| Service | Redirected by |
|---|---|
| Enable Banking | `ENABLE_BANKING_BASE_URL` |
| Google Calendar, Google Tasks | `GOOGLE_API_BASE_URL` |
| Whisper | `OPENAI_BASE_URL` |
| Edge TTS | `TTS_PROVIDER=fake` — no URL to redirect, it opens its own WebSocket |
| Anthropic | `LLM_PROVIDER=fake` — scripted answers, see below |

All five default to today's behaviour when unset, so dev and prod are untouched.

### What can still reach the internet

One thing, and a journey author should know before writing a step that touches
it:

- **Google's OAuth endpoints.** `accounts.google.com` and
  `oauth2.googleapis.com` are hard-coded in `GoogleAuthController`,
  `GoogleCalendarConnectController` and the client libraries' token refresh —
  no base URL redirects them. Sign-in is fine because the test login replaces
  it; the refresh path is fine only because the seeded token expiry is a
  far-future literal. **A "connect Google Calendar" step would go out to the
  real internet.** Stub it first.

## The fake LLM

`LLM_PROVIDER=fake` (MAG-95), what this stack runs on. Maggie answers from the
scenario files in `agent/fixtures/fake-llm/` — their
[README](../../../agent/fixtures/fake-llm/README.md) is the format — instead of
calling Claude. So a journey can talk to her and stay fast, free and the same
every time.

**Only the model is replaced.** The tool loop, the AG-UI streaming gateway, the
MCP client and the metrics are the production ones: a scenario scripts the
model's side of the conversation, and the tools it asks for really run against
the real MCP server and the seeded database. `create_llm_client()` in
`agent/app/llm/client.py` is the single switch — every call to a model goes
through it, including the context router and the transcript cleanup, so no
caller takes an e2e-only branch.

Three things to know before writing a step that talks to her:

- **Nothing matches → she says so.** The answer is `[fake-llm] aucun scénario ne
  correspond à : '…'`, so the assertion fails on a sentence that names its own
  cause. No catch-all ships, on purpose: a bland default would turn "nobody
  scripted this" into a plausible wrong answer.
- **The scripted text is a fixture, not a truth.** The fake does not read tool
  results, so a scripted sentence cannot describe data it has not seen. Assert
  Maggie's wording against the scenario; assert *data* against the database or
  MCP.
- **The scenario has to exist before the step does.** Add it to
  `agent/fixtures/fake-llm/`, numbered so it matches before a broader one. The
  files reload on change — no agent restart.

`task e2e:seed` also empties the agent's own database (conversations, contexts,
memory, directives), so a second run of a journey does not start with the first
run's conversation behind it.

### The eval suite

The other half of the split. Judgement — right tool, right tone, keeps the
thread — is what the fake cannot check, so it is checked separately, on the real
model:

```sh
task e2e:eval              # all scenarios, needs ANTHROPIC_API_KEY
task e2e:eval -- --only agenda
task e2e:eval:check        # the scenarios parse — no key, no tokens
```

Scenarios live in `scripts/prompt-lab/scenarios/`, shared with `/prompt-lab`;
their [README](../../../scripts/prompt-lab/scenarios/README.md) is the format.
`task e2e:eval` restarts the agent on `LLM_PROVIDER=anthropic` and puts the fake
back afterwards, even on failure.

It runs nightly and on demand (`.github/workflows/eval.yml`), never in CI: a
prompt regression is a signal, not a merge blocker. **An assertion that would
hold with any plausible wording belongs in a journey with the fake instead** —
cheaper, deterministic, and it runs on every PR.

## The browser harness

`e2e/web/`, driven by `task e2e:web` — Playwright in a container on the stack's
own network, browsing `http://traefik`. Its
[README](../../../e2e/web/README.md) is the full guide; four things belong
here because they are properties of the *stack*, not of Playwright:

- **One origin, no host port.** The journeys never resolve the ephemeral port:
  from inside the network Traefik answers on `http://traefik`, and the admin's
  relative URLs (`/api`, `/.well-known/mercure`, `/agent`) all land on it, as
  they do in production.
- **Nothing leaves the network.** Every request to another origin is aborted —
  the Google font the admin pulls, react-admin's telemetry. Same rule as the
  WireMock stubs: an external call is a bug, not a dependency.
- **Three widths.** `desktop` (1440), `tablet` (834), `phone` (393). Only tests
  tagged `@responsive` run on all three.
- **The Mercure image is pinned by digest, and must stay pinned.** An
  untagged `dunglas/mercure` moved to a build that renamed the subscribe
  parameter from `topic` to `match`, and every subscription in the admin, the
  agent and the mobile app answered `400`. CI pulls fresh, so it broke there
  first while every local stack stayed green on a cached image. MAG-142 moves
  us to the new parameter.
- **`MERCURE_JWT_SECRET` must be at least 32 bytes.** lcobucci/jwt refuses to
  sign HS256 with less, `MercurePublishMiddleware` catches and logs the
  failure, and the stack then has no real-time at all while looking perfectly
  healthy. That is how it shipped until the first browser journey asserted on
  a live update.

## What a new journey owes

1. It runs against a stack started by `task e2e:up` and seeded by
   `task e2e:seed` — never against dev.
2. It reads ids from the seed manifest, not from hard-coded ULIDs.
3. Data it needs goes into `api/fixtures/e2e/`, with the counts updated in
   `E2eSeedCommandTest`.
4. An external call it triggers has a WireMock stub. `task e2e:smoke` fails on
   any unmatched request, which is how a missing stub surfaces as itself rather
   than as a timeout.
5. **It waits for indexed entities.** Writing an indexed entity dispatches through
   RabbitMQ to the worker, and the collection is served from Elasticsearch — so
   the row exists before it is findable. Poll until it appears; reading once is
   how a working feature gets reported as broken. (The seed is exempt: it
   reindexes synchronously and refreshes, so data is findable the moment
   `task e2e:seed` returns.)

# The E2E Environment

An isolated stack, deterministic data, a login that does not go through Google,
and stand-ins for every external service — the model included. One per git
worktree, so parallel agents never collide. Built by MAG-94, with the fake LLM
and the eval suite from MAG-95, the browser harness from MAG-97 and the emulator
harness from MAG-98.

Read this before writing anything that runs against the stack. The Definition of
Done itself lives in [testing.md](testing.md).

## Commands

| Command | What it does |
|---|---|
| `task e2e:up` | Start the stack (build locally, install, clear cache, migrate), print the URL |
| `task e2e:seed` | Reset the database to the fixture set, empty the agent's own tables, rebuild and refresh the search indices |
| `task e2e:smoke` | Run the smoke journey (HTTP) against the running stack |
| `task e2e:web` | Reseed, then run the Playwright journeys for the admin |
| `task e2e:mobile` | Reseed, install the `e2e` flavor, run the Maestro journeys — needs a device |
| `task e2e:mobile:lint` | Check the flows without a device — also part of `task lint:all` |
| `task e2e:web:lint` | ESLint on the journeys — also part of `task lint:all` |
| `task e2e:web:typecheck` | Type-check the journeys without running them |
| `task e2e:web:shell` | A shell in the Playwright container |
| `task e2e:admin:build` | **After changing admin code** — see below |
| `task e2e:eval` | Replay the prompt-lab scenarios on the **real** model — see below |
| `task e2e:eval:check` | Check those scenarios parse, without calling the model |
| `task e2e:test:api` | PHPUnit **inside** this worktree's stack — for debugging against a running stack; CI does not run it (the `API Tests` job does) |
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
`task wt:test:api` (see `worktree-checks.md`). `task e2e:test:api` runs the same
suite against the stack mounting *this* worktree — useful to debug a test that
needs the real services; it creates the PHPUnit database first, since the e2e
stack has never made one. CI does not run it: the `API Tests (PHPUnit)` job
already does.

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

## The clock (MAG-234)

A journey that passes at 15:00 and fails at 00:30 depends on the hour it was
started at (MAG-177, MAG-212, the mobile calendar flow around midnight). So the
clock is a parameter of the stack, not a property of the runner.

`E2E_NOW=<ISO-8601>` (e.g. `2026-10-11T23:50:00+02:00`) fixes "now" for **every**
part of the stack at once; unset, nothing changes. `e2e/clock.sh` is the only
reader of the variable:

| Part | How it follows `E2E_NOW` |
|---|---|
| API, worker, agent, seed | libfaketime (`LD_PRELOAD` in `docker-compose.e2e.yml`): each process starts at the instant and moves 10 µs per clock read. Not frozen (`uniqid()` spins forever on a stopped clock), not at real speed (a 40-minute suite would cross midnight); `sleep` is untouched. The seed anchors on `new DateTimeImmutable()`, so it follows with no `--now` |
| Agent JWT check | the clocks of two processes drift apart (the API's moves with every request it serves), so a token's `iat` can be seconds ahead of the agent's "now": the e2e stack sets `JWT_LEEWAY_SECONDS=60`, production keeps 0 |
| Browser | `context.clock.setFixedTime` (`web/fixtures/clock.ts`, wired in `isolateFromInternet`): `Date` stands still, timers run |
| Emulator | `run.sh` sets the device clock through `adb`, disables automatic time, and re-pins it if it drifts |
| WireMock | its JVM ignores libfaketime: `task e2e:seed` rewrites the all-day Google event to the anchor day (`e2e/wiremock-today.sh`) |

Rules for a journey:

- **Never read the host's clock.** `e2eNow()` and `seedDate()` in Playwright, the
  `TODAY` / `TRAIN_*` variables in Maestro, `e2e/clock.sh epoch` in shell. A
  journey that calls `Date.now()` or `date` disagrees with a stack that believes
  it is Sunday 23:50.
- **Never write a UTC offset by hand.** `parisTime(day, '00:00:00')` gives the right
  `+01:00` or `+02:00`.
- **`E2E_NOW` must be in the future.** Mercure rejects a token whose `exp` is past,
  and the pinned clock signs them. `e2e/clock.sh resolve <name>` returns the next
  occurrence of a named instant and refuses a past date.

The boundary instants (`e2e/clock.sh names`): `sunday-2350-paris`,
`monday-0050-paris`, `dst-fall-back-0230-paris` (02:30 on the last Sunday of
October, winter time), `saturday-2230-utc`. A manual tool, not a CI matrix: CI
runs the real clock, a matrix of four extra full runs per pull request clogged
the runners (MAG-243), and the bug that motivated it was the app's, not the
journeys'. To reproduce an hour-dependent failure, run
`E2E_NOW=<name or ISO-8601> task e2e:web` (or `e2e:mobile`) by hand.

`run.sh` prints `E2E_NOW`, `TODAY` and the device's date and time zone at the top of
every mobile run, and writes them to `report/clock.txt`.

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

### The Ciqual food base is ours, and has to be built (MAG-101)

Ciqual is not a third party: it is a service of this repository, and the stack
runs the `dev` image, which bakes **no** database — only `prod` does. The stack
also mounts `./ciqual` over `/app`, so a baked one would be hidden anyway.

`task e2e:up` therefore runs `task e2e:ciqual:db`, which builds
`ciqual/db/ciqual.db` from the committed XML: three seconds, 13 MB, the real
3 484 foods, identical bytes every time — so it is a `status` check and a stack
coming up again on the same worktree skips it. The file is gitignored.

Without it every `/ciqual/foods` call answers 500, and the symptom is indirect:
the recipe form's "Aliment Ciqual" autocomplete is simply always empty, and the
API's own `IngredientFromCiqualResolver` — which calls the same service — makes
every recipe written with a food code fail. `recipes-ciqual.spec.ts` asserts
the service answers before it asserts anything else, so the failure says so.

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
own network namespace, browsing `http://localhost`. Its
[README](../../../e2e/web/README.md) is the full guide; four things belong
here because they are properties of the *stack*, not of Playwright:

- **One origin, no host port.** The journeys never resolve the ephemeral port:
  they browse `http://localhost`, which is Traefik, and the admin's relative
  URLs (`/api`, `/.well-known/mercure`, `/agent`) all land on it, as they do in
  production. It works because the `playwright-journeys` service sets
  `network_mode: service:traefik` — it shares Traefik's network namespace, so
  `localhost:80` is the router, and service names (`wiremock`, `php`) still
  resolve. `playwright`, the one-off service for lint and typecheck, keeps a
  network of its own and needs no stack.
- **Nothing leaves the network.** Every request to another origin is aborted —
  the Google font the admin pulls, react-admin's telemetry. Same rule as the
  WireMock stubs: an external call is a bug, not a dependency.
- **The origin is `localhost` because it is trustworthy, and that gives the
  browser a microphone.** `navigator.mediaDevices` only exists in a secure
  context, and `http://traefik` (plain HTTP on a hostname) is not one: the
  property was absent, and nothing in Playwright worked around it — the
  microphone permission was granted and the property stayed missing, and
  `--unsafely-treat-insecure-origin-as-secure` is ignored by the bundled build
  even with a persistent profile. `localhost` is secure by definition, with no
  flag. The config then grants the permission and starts Chromium with its fake
  capture device, so "Dicter" records a synthetic beep. TLS on the entrypoint
  was the other option and was not taken: more moving parts (a certificate to
  mint and ignore) for an origin that is no more trustworthy to the media API
  than `localhost`, and Traefik terminates TLS identically in dev and prod.
  `chat.spec.ts` asserts `window.isSecureContext` first, so a regression of the
  origin says so instead of showing "Accès au microphone refusé". The smoke
  script still covers dictation over HTTP, without a browser.
- **Three widths.** `desktop` (1440), `tablet` (834), `phone` (393). Only tests
  tagged `@responsive` run on all three.
- **The Mercure image is pinned by tag and digest, never untagged** (`dunglas/mercure:v1.0.2@sha256:…`).
  An untagged image follows `latest`: the 0.x to 1.0 move renamed the subscribe
  parameter and the token format, and every subscription answered `400`. CI
  pulls fresh, so it broke there first while every local stack stayed green on a
  cached image. Rules and upgrade steps: `real-time.md`.
- **`MERCURE_JWT_SECRET` must be at least 32 bytes.** lcobucci/jwt refuses to
  sign HS256 with less, `MercurePublishMiddleware` catches and logs the
  failure, and the stack then has no real-time at all while looking perfectly
  healthy. That is how it shipped until the first browser journey asserted on
  a live update.

## The emulator harness

`e2e/mobile/`, driven by `task e2e:mobile` — Maestro on an emulator, against the
same stack. Its [README](../../../e2e/mobile/README.md) is the full guide; four
things belong here because they are properties of the *stack* and of the app's
relationship to it, not of Maestro:

- **The app has an `e2e` Gradle flavor**, and that is the whole reason the harness
  is possible: Google Credential Manager is a system dialog Maestro cannot tap,
  so `mobile/app/src/e2e/` signs in through the test login instead. A flavor
  source set and not a build flag — `src/main/` knows neither door, and the APK
  that ships does not contain the test login at all. **Anything else that must
  only exist on the emulator goes in that source set**, which is the mobile
  counterpart of `modules/<module>/src/E2e/` on the API side.
- **The host port is bridged, not baked in.** The APK is built against a fixed
  port on the device's own loopback and `adb reverse` maps it onto whatever
  Docker chose. So the APK is independent of the stack and nothing hard-codes an
  ephemeral port, same rule as everywhere else here.
- **The device's time zone is part of the fixture.** The seed anchors on midnight
  in Paris; an emulator boots on UTC, and between 22:00 and midnight UTC the two
  are a day apart — every "today" assertion then fails for an hour a day. CI
  boots the emulator with `-timezone Europe/Paris`.
- **No microphone, for a different reason than the browser.** The browser has a
  fake one (`localhost` is a secure context); the emulator has none because
  a CI runner has no sound card and runs with `-noaudio`. The `e2e` flavor
  therefore records placeholder bytes (`AudioRecorderProvider.kt`, MAG-221) and
  the stubbed Whisper answers one fixed sentence, so a flow can hold the mic and
  follow the dictation to the chat. What the overlay must *not* do (MAG-93's four
  voice regressions) is still asserted on the server, not on screen.

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
6. **It addresses rows by a label of its own, and asserts no counts.** The suite
   runs `fullyParallel`, so another file is writing to the same user's data at
   the same time. CI retries once *without* reseeding, so the label carries the
   attempt number (`perAttempt()` in several files).

### A journey asserting a *publication* needs data it owns (MAG-101)

The two rules above are not enough for "*this* write reached the hub". A
`GroceryList`, a `GroceryItem` collection, a conversation — anything published
whole on one topic — makes "a payload arrived showing the new state" true as
soon as *anyone* writes to it. A middleware that published nothing for the
command under test would still pass, which is exactly the regression such a test
exists to catch (`afc1a70`).

So a journey of that kind needs three things, and `grocery-errand.spec.ts` is
the worked example:

- **data nobody else writes.** It signs in as the second seeded account, whose
  grocery list the seed gives it and which no other file touches. Giving that
  account a second job is cheaper than a third one; say why in
  `api/fixtures/e2e/10-core.yaml` when you do it.
- **the *next* message, not any later one.** Snapshot how many the probe has
  received, act, then assert on the first message after the snapshot.
- **`mode: 'serial', retries: 0`.** A serial group replays whole with nothing
  reseeded, so a test starting from a seeded row fails on the retry for a reason
  that has nothing to do with the code. A flake has to read as a flake.

The same reasoning covers the chat: `GET /agent/messages` is scoped to the user
and returns the last twenty, and `chat.spec.ts` depends on that window. A journey
adding exchanges as the signed-in user eats into it — use the second account.

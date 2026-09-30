# Deterministic E2E Environment — Plan

Ticket: [MAG-94](https://linear.app/meven/issue/MAG-94/environnement-e2e-deterministe-stack-de-test-donnees-de-test-connexion)
Project: Suite e2e et tests systématiques
Shaping notes and decisions: [shape.md](shape.md) · Standards: [standards.md](standards.md)

## Acceptance criteria

1. `task e2e:up` starts php, nginx, traefik, postgres, mercure, rabbitmq, worker,
   elasticsearch, agent, ciqual and wiremock, in a Compose project named after the current
   worktree, publishing exactly one ephemeral host port.
2. Two worktrees run `task e2e:up` at the same time without a port, volume, network,
   container name or Traefik router collision, and neither disturbs the dev stack.
3. `task e2e:seed` leaves the database in a byte-identical state whatever it held before,
   with Elasticsearch indices rebuilt from it.
4. `POST /api/auth/e2e/login` returns a usable JWT in the `e2e` environment, and **does not
   exist** in `dev`, `test` or `prod` — asserted by a test that boots the prod router.
5. Enable Banking, Google Calendar and Google Tasks calls made from the e2e stack reach
   WireMock, never the internet. The agent synthesises speech without reaching Microsoft.
6. `task e2e:down` removes containers *and* volumes; nothing survives into the next run.
7. CI starts the stack, seeds it, and runs the smoke journey on every PR.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`, `standards.md`.

## Task 2: A Symfony `e2e` environment

- `api/config/packages/*.yaml` — add `when@e2e` where `e2e` must differ from the
  environment-agnostic defaults: `monolog` (JSON to stderr, `debug`, like prod but without
  `fingers_crossed`, so a passing run still shows why a journey failed), `nelmio_alice` and
  `hautelook_alice` (reuse the `&dev` anchor — the seed command loads Alice fixtures).
- `api/config/bundles.php` — `DoctrineFixturesBundle`, `NelmioAliceBundle`,
  `FidryAliceDataFixturesBundle`, `HautelookAliceBundle` gain `'e2e' => true`.
- `api/.env.e2e` — committed, no secrets: `APP_ENV=e2e`, `APP_DEBUG=0`, service URLs inside
  the stack, `E2E_LOGIN_TOKEN` with a placeholder the compose file overrides.
- Doctrine deliberately gets **no** `when@e2e`: e2e runs the migrated schema on its own
  database, in its own volume.

**Verify:** `bin/console --env=e2e cache:warmup` compiles, and `debug:container` in `e2e`
lists the seed command and the login controller.

## Task 3: The test login

`api/modules/core/src/E2e/Controller/E2eLoginController.php`

- `POST /api/auth/e2e/login`, body `{"email": "..."}`, header
  `X-E2E-Token: <E2E_LOGIN_TOKEN>`.
- Constructor takes `string $environment` (`%kernel.environment%`) and
  `string $e2eLoginToken`; the first line of `__invoke` throws `NotFoundHttpException`
  unless the environment is `e2e` — so even a mounted route 404s elsewhere.
- Returns the same shape as `GoogleAuthController::token()`: `token`, `refreshToken`,
  `mercureToken`, `user`. Journeys need all four, and a different shape would mean the
  admin's `handleAuthCallback` takes an e2e-only branch. The Mercure subscriber token is
  extracted out of `GoogleAuthController` into `MercureSubscriberTokenFactory`, so the two
  sign-in paths cannot drift.
- **No `config/routes/e2e.yaml`.** `config/routes.yaml` already loads routes from
  registered controller services (`resource: routing.controllers`), so excluding
  `../src/E2e/` from the module's `resource:` glob and re-adding it under `when@e2e` makes
  the route structurally absent everywhere else. One mechanism instead of two, and it is
  the one the project already uses.
- `security.yaml`: `^/api/auth` is already a `security: false` firewall with
  `PUBLIC_ACCESS` — no change needed, which is itself worth asserting in a test.

**Tests** — `api/modules/core/tests/Controller/E2eLoginControllerTest.php`:

| Case | Assertion |
|---|---|
| Route absent in `prod` | boot `App\Kernel('prod', false)`, `router.getRouteCollection()->get('auth_e2e_login')` is `null` |
| Route absent in `dev` and `test` | same, both environments |
| Service absent in `prod` | the container has no `E2eLoginController` |
| Controller refuses outside `e2e` | instantiate with `$environment='prod'`, expect `NotFoundHttpException` |
| Missing/wrong token | 401 |
| Unknown email | 404 |
| Bad input (no email) | 400 |
| Happy path | 200, JWT decodes to the seeded user, refresh token persisted in DB |

The first four are the ticket's "explicit test of that guarantee". They run in the `test`
environment and boot other kernels, so they need `KERNEL_CLASS` and a debug-off boot.

## Task 4: `app:e2e:seed`

`api/modules/core/src/Command/E2eSeedCommand.php`, registered only under `when@e2e`.

- `--now=<ISO8601>` (default: today 00:00 UTC) — every fixture date is an offset from it.
- `--skip-search` for a stack started without Elasticsearch. **No `--no-purge`**: the plan
  called for one, but with a single fixture set, loading it on top of itself violates the
  first unique index it meets — the test written for it is what surfaced that. An option
  whose only use is broken is worse than no option.
- Steps: truncate every table except `doctrine_migration_versions` (`TRUNCATE ... RESTART
  IDENTITY CASCADE`, one statement built from the metadata) → load Alice fixtures →
  delete and recreate Elasticsearch indices → reindex synchronously.
- Fixtures in `api/fixtures/e2e/`, one file per module. Ids are **not** hard-coded — see
  the manifest decision in `shape.md`; the seed writes `api/var/e2e/seed-manifest.json`
  mapping each reference to the id it produced:
  - `10-core.yaml` — `e2e@maggie.local` and their preferences.
  - `20-calendar.yaml` — two agendas, an event today, a weekly recurring event with one
    exception instance, an event with a reminder, an all-day event, an open task and a
    done one (so MAG-112's "reopen" has a target).
  - `30-grocery.yaml` — two stores, four ingredients, two products, a list with checked,
    unchecked, free-text and deferred items, two recurring items.
  - `40-cookbook.yaml` — two recipes sharing an ingredient (so MAG-116's merge has a
    case), one meal planned tomorrow.
  - `50-finance.yaml` — a bank connection wired to WireMock, two accounts carrying its
    external ids, a category tree, twelve transactions across two months and the next,
    a monthly and an annual envelope, an active and an inactive rule, a cushion, a loan.
  - `60-notification.yaml` — one read, one unread.
- `--now` is threaded into the fixtures through an Alice parameter, not through `date()`
  (which `nelmio_alice.yaml` blacklists).

**Tests** — `api/modules/core/tests/Command/E2eSeedCommandTest.php`:

| Case | Assertion |
|---|---|
| Happy path | every seeded entity type has the expected row count |
| Determinism | two runs produce identical ULIDs, identical dates, identical counts |
| Purge | rows created between two runs are gone after the second |
| `--now` | an event's start is the anchor + its offset, not the wall clock |
| Bad `--now` | non-parsable date exits non-zero with a message |
| Command absent outside `e2e` | `bin/console list` in `test` does not contain `app:e2e:seed` |

Elasticsearch steps carry the `elasticsearch` group, excluded from the default suite.

## Task 5: Redirectable external services

| Service | Injection point | Env var |
|---|---|---|
| Enable Banking | already a constructor argument | `ENABLE_BANKING_BASE_URL` (exists) |
| Google Calendar | `new GoogleCalendarService($client, $rootUrl)` | `GOOGLE_API_BASE_URL` |
| Google Tasks | `new GoogleTasksService($client, $rootUrl)` | `GOOGLE_API_BASE_URL` |
| Whisper | `openai.AsyncOpenAI(base_url=...)` | `OPENAI_BASE_URL` |
| Edge TTS | provider switch in `app/tts/synthesis.py` | `TTS_PROVIDER=fake` |

Defaults keep today's behaviour: empty `GOOGLE_API_BASE_URL` means the library's own root,
`TTS_PROVIDER=edge` is the default. Nothing changes for dev or prod.

WireMock mappings in `.docker/e2e/wiremock/mappings/`, one file per external: the Enable
Banking bank list, auth start, session exchange and two accounts with transactions; the
Google calendar list, events list and events watch; the Whisper transcription response.

**Tests:**

- `api/modules/calendar/tests/Service/GoogleCalendarApiClientTest.php` — the configured
  root URL reaches the built service; empty config leaves the library default.
- `agent/tests/tts/test_synthesis.py` — `TTS_PROVIDER=fake` returns MP3 bytes without
  touching `edge_tts`; the default still selects the Edge path.
- `agent/tests/llm/test_transcription.py` — `OPENAI_BASE_URL` is passed to the client
  (`respx`-mocked), absent config leaves the default.

## Task 6: `docker-compose.e2e.yml`

Services: traefik, nginx, php, worker, database, mercure, rabbitmq, elasticsearch, agent,
ciqual, wiremock. Differences from dev, each deliberate:

- No `ports:` anywhere except `traefik: ["80"]`.
- No named volumes for data — `tmpfs` for Postgres, RabbitMQ and Elasticsearch data
  directories. A stack that never persists cannot leak state between runs, and `e2e:down`
  cannot forget to remove a volume.
- Every service carries `maggie.stack: ${COMPOSE_PROJECT_NAME}`; Traefik runs with
  `--providers.docker.constraints=Label(\`maggie.stack\`,\`${COMPOSE_PROJECT_NAME}\`)` and a
  single `web` entrypoint, no TLS, no HTTPS redirect.
- `php`/`worker` build the same `dev` target (the code is bind-mounted from the worktree,
  which is the point) but run `APP_ENV=e2e`.
- `nginx` serves `admin/dist`, so journeys test the built admin, as production does.
- The agent's `MERCURE_PUBLIC_URL` and `AGENT_BASE_URL` are relative to the resolved host
  port, injected by the Taskfile.

`docker-compose.yml` gains `maggie.stack: dev` on every service and the matching Traefik
constraint — without it the dev Traefik claims e2e containers.

## Task 7: `task e2e:*`

`e2e/Taskfile.yml`, included from the root Taskfile.

| Task | Does |
|---|---|
| `e2e:up` | build if needed, start, wait for health, apply migrations |
| `e2e:seed` | `app:e2e:seed` in the e2e php container |
| `e2e:down` | `down -v --remove-orphans` |
| `e2e:url` | prints `http://127.0.0.1:<resolved port>` |
| `e2e:logs`, `e2e:ps`, `e2e:sh`, `e2e:psql` | debugging |
| `e2e:console` | `bin/console --env=e2e` passthrough |
| `e2e:test:api` | PHPUnit inside the e2e stack — the worktree-safe way to run API tests |
| `e2e:smoke` | the smoke journey of Task 8 |

`COMPOSE_PROJECT_NAME` defaults to `maggie-e2e-<basename>-<sha1 of absolute path, 8 chars>`,
lowercased and stripped to `[a-z0-9-]`.

## Task 8: Smoke journey and CI

`e2e/smoke/smoke.sh` — no Playwright yet (MAG-97), so a shell journey that is exactly the
contract the harness will rely on:

1. `GET /api/docs` → 200 through Traefik.
2. `POST /api/auth/e2e/login` without the token → 401.
3. With it → 200 and a JWT.
4. `GET /api/events` with the JWT → the seeded events, dates matching the anchor.
5. `POST /_mcp` `tools/list` with the JWT → the tool list is non-empty and includes one
   tool per module (the `discovery.scan_dirs` gotcha).
6. `GET /api/search?q=...` → a hit, proving the index was rebuilt.
7. A finance sync against WireMock → the stubbed accounts land in the database.
8. `GET /agent/health` → 200.

`.github/workflows/ci.yml` gains an `e2e-stack` job: start, seed, smoke, and upload
`docker compose logs` on failure. Not yet blocking-by-branch-protection — MAG-96 owns that
— but it fails the workflow, which is what a PR check needs.

## Task 9: Documentation

- `agent-os/standards/global/testing.md` — replace "the harness isn't built yet" for the
  environment half: the stack exists, `task e2e:*` is how you use it, and in a worktree
  API tests go through `e2e:test:api`. The Playwright/Maestro half stays pending.
- `CLAUDE.md` — the worktree paragraph currently says "push and let CI verify until the
  isolated e2e stack exists". It now exists; say how to use it.
- New `agent-os/standards/global/e2e-environment.md` and its `index.yml` entry, so
  `/inject-standards` can surface it.
- Linear: the module functional spec has no e2e section to update, so this lands in the
  team documentation index as a new "Environnement e2e" page (ADR-006).

## Tests

| Unit touched | Tests |
|---|---|
| `E2eLoginController` | 401 no/bad token, 400 bad input, 404 unknown email, happy path asserting the JWT and the persisted refresh token, plus four absence assertions across `prod`/`dev`/`test` |
| `E2eSeedCommand` | happy path asserting row counts, determinism across two runs, purge, `--now` anchoring, bad `--now`, absence outside `e2e` |
| `GoogleCalendarApiClient` / `GoogleTasksApiClient` | configured root URL used, default preserved |
| `app/tts/synthesis.py` | fake provider returns bytes without `edge_tts`, default unchanged |
| `app/llm/transcription.py` | `OPENAI_BASE_URL` passed through, default unchanged |
| Compose, Taskfile, WireMock mappings | no unit test applies; covered by the smoke journey, which CI runs |

No Mercure or Elasticsearch assertions on the login controller: it publishes nothing and
indexes nothing. The seed command's Elasticsearch step is asserted through the smoke
journey's search step rather than a unit test, because it needs a real cluster.

### What was actually run

On the stack this ticket builds, in this worktree — which is itself the proof that
`task e2e:test:api` does what it claims:

| Command | Result |
|---|---|
| `task e2e:up` | stack up, one ephemeral port (32781), no named volume, own network |
| `task e2e:seed` | 24 tables truncated, 61 objects, manifest written, indices rebuilt |
| `task e2e:smoke` | **31 passed, 0 failed** |
| `task e2e:test:api` | **535 tests, 2243 assertions, 0 failures** |
| `vendor/bin/phpstan analyse` | no errors |
| agent `pytest` | 135 passed (7 of them new) |
| agent `ruff check app/` + `ruff format --check app/` | clean |

Isolation checked by hand: a second stack started beside the first took port 32782, got
its own network, and its Traefik answered 404 on `/api/docs` — proof the label constraint
keeps each stack to its own containers.

### Three bugs this ticket had to fix to exist

Found by running the stack, not by reading:

1. **The migration chain was not replayable.** `Version20260227214604` dropped
   `product.ciqual_food_id`, the three ciqual tables and `agent_message` — none of which
   any migration ever created; they reached the dev database through `schema:update`. It
   also re-added `ciqual_alim_code`, which the previous day's migration already adds. On
   an empty database the chain died twice. Now guarded with `IF EXISTS` / `IF NOT EXISTS`,
   which changes nothing where it already ran. **29 migrations now replay from empty**,
   and the CI e2e job is what keeps it that way.
2. **`listTableNames()` returns reserved words already quoted**, so truncating produced
   `""user""` and Postgres reported a table that does not exist.
3. **A reindex is not a refresh.** Elasticsearch refreshes about once a second, so the
   first read after a seed came back empty — indistinguishable from a stale index. The
   seed now refreshes before returning.

No bug fix was *in scope*, so none of these ships a red-first reproduction test in the
usual sense; the smoke journey and the CI e2e job are their regression tests, and both
fail if any of the three comes back.

## E2E journey

This ticket *is* the harness, so its journey is the one that proves the harness works —
`e2e/smoke/smoke.sh`, run by CI, transcribed into Playwright by MAG-97.

**Given** a worktree with no stack running
**When** `task e2e:up && task e2e:seed`
**Then** the dev stack is untouched (`docker compose ps` in the main checkout unchanged),
and `task e2e:url` prints a reachable URL

**Given** the seeded stack
**When** `POST /api/auth/e2e/login {"email": "e2e@maggie.local"}` with no `X-E2E-Token`
**Then** 401, and no session is created

**When** the same request carries the token
**Then** 200 with `token`, `refreshToken`, `mercureToken` and `user`, the JWT's `sub` is
the seeded user's ULID, and a `refresh_token` row exists for them

**Given** that JWT
**When** `GET /api/events?after=<anchor>`
**Then** the seeded events come back, the recurring one expanded, its exception instance
carrying the overridden time — the same dates on every run

**When** `POST /_mcp` with `tools/list`
**Then** the list contains at least one tool from each of calendar, cookbook, grocery,
finance and notification

**When** `GET /api/search?q=<seeded recipe name>`
**Then** at least one hit, proving the seed rebuilt the Elasticsearch index

**Given** WireMock's Enable Banking mappings
**When** the bank sync runs for the seeded connection
**Then** the stubbed accounts and transactions are in the database, and no request left the
Docker network

**When** `task e2e:down`
**Then** no container, volume or network of this project remains, and the dev stack is
still up

The module journeys (MAG-99 → MAG-103) are unchanged by this ticket; they gain the ability
to run.

## Definition of Done

- [x] Unit/integration tests — Task 3, 4, 5; 535 API tests and 135 agent tests green
- [x] E2E journey — the smoke journey above, executable, 30/30, run by CI
- [x] Bug fix reproduction test — the three bugs above were found *by* the smoke journey
      and the stack start, which are their regression tests
- [ ] CI green on a PR linking MAG-94
- [ ] Linear updated: "Environnement e2e" page under the documentation index (ADR-006)

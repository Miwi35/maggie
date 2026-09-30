# Deterministic E2E Environment — Shaping Notes

Ticket: [MAG-94](https://linear.app/meven/issue/MAG-94/environnement-e2e-deterministe-stack-de-test-donnees-de-test-connexion)
Project: Suite e2e et tests systématiques

## Scope

The foundation every e2e journey stands on: a stack that runs beside the dev stack without
touching it, data that is the same on every run, a login that does not go through Google,
and stand-ins for the four external services the app calls.

What this ticket does **not** carry, and why:

| Out of scope | Ticket that carries it |
|---|---|
| Fake LLM (`LLM_PROVIDER=fake`, scripted tool calls) | MAG-95 |
| Playwright harness, page objects, fixtures | MAG-97 |
| Maestro flows, `e2e` product flavor | MAG-98 |
| Making e2e blocking on PRs, branch protection | MAG-96 |
| The journeys themselves | MAG-99 → MAG-103 |

So this ticket ships infrastructure with one smoke journey proving the infrastructure
works — not a feature journey.

## Decisions

Taken alone, per the autonomy rule in `CLAUDE.md`. Each is a decision that the ticket, the
standards or the existing code did not already settle.

### A standalone `docker-compose.e2e.yml`, not a profile or an override

**Options:** a `e2e` profile inside `docker-compose.yml` / `-f docker-compose.yml -f
docker-compose.e2e.yml` overrides / a standalone file.
**Choice:** standalone `docker-compose.e2e.yml`.
**Why:** the ticket asks for isolation from dev, and a profile shares the dev file's named
volumes, fixed host ports and project name — the three things that must differ. An override
chain would inherit `ports: 5432:5432` from `database` and there is no way to *remove* a
published port in an override, only to replace the whole list, which is the same amount of
writing with a subtler failure mode. The file duplicates ~150 lines of service definitions;
the duplication is the price of the isolation the ticket demands.

### Every host port ephemeral, a single published entry point

**Options:** fixed ports on an offset (`18080`…) / a port range per worktree / no host port
at all (`docker compose exec` only) / ephemeral ports, resolved on demand.
**Choice:** one `ports: - "80"` on Traefik — Docker picks a free host port — and nothing
else published. `task e2e:url` resolves it via `docker compose port traefik 80`.
**Why:** the ticket says "no fixed host ports (or automatically assigned ones)". An offset
still collides once two worktrees run at the same time, which is precisely the case the
ticket was reopened for. Playwright needs a host-reachable URL, so "no host port at all"
does not work. Postgres, RabbitMQ and Elasticsearch stay unpublished: nothing outside the
stack needs them, and `task e2e:psql` covers debugging.

### Traefik stays, filtered by a label constraint

**Options:** drop Traefik and let nginx route everything / keep Traefik with a
`--providers.docker.constraints` filter.
**Choice:** keep Traefik, constrained to `Label('maggie.stack', '<project>')`.
**Why:** routing is a proven regression surface (MAG-93), and an e2e stack that routes
differently from dev and prod cannot catch routing bugs. Traefik reads the Docker socket,
so without a constraint the dev Traefik would pick up e2e containers and two e2e stacks
would fight over the same router names. This also requires adding the same constraint and
a `maggie.stack=dev` label to `docker-compose.yml` — a two-line change to the dev stack,
in service of leaving it alone.

**Consequence:** e2e serves plain HTTP on the `web` entrypoint. No TLS, no certificate to
trust in Playwright and no `/etc/hosts` entry — the dev stack's HTTPS redirect is dropped
in e2e. Journeys reach the app at `http://127.0.0.1:<ephemeral>`.

### A real Symfony `e2e` environment, not `prod` or `test`

**Options:** run the stack with `APP_ENV=prod` / with `APP_ENV=test` / with a new
`APP_ENV=e2e`.
**Choice:** `APP_ENV=e2e`.
**Why:** the ticket asks for an endpoint "never mounted in prod", which needs an
environment that is *not* prod to mount it in. `test` is taken by PHPUnit and carries
`dbname_suffix: _test`, `in-memory://` transports and a synchronous message bus — an e2e
stack running those would not exercise RabbitMQ, the worker, or Mercure, which is most of
what the journeys are there to check. A separate env inherits the environment-agnostic
`config/packages/*.yaml` and adds only `when@e2e` blocks.

### `APP_DEBUG=0` in e2e

**Options:** debug on, for legible failures / debug off, for fidelity to prod.
**Choice:** off.
**Why:** several past regressions were prod-only (container compilation, cache pools,
serialization). Running e2e in debug would hide exactly that class of bug. Diagnosis is
covered instead by monolog writing JSON to stderr at `debug` level, which `task e2e:logs`
tails.

### The test login is guarded three times

**Options:** a shared secret on a route mounted everywhere / an `APP_ENV` check inside the
controller / the route only declared in `when@e2e`.
**Choice:** all three of the last two, plus a token: the route lives in
`config/routes/e2e.yaml` under `when@e2e`; the controller throws unless
`kernel.environment === 'e2e'`; the request must carry `E2E_LOGIN_TOKEN`.
**Why:** the ticket asks for the guarantee to be *tested explicitly*, which means the
guarantee has to be structural rather than a convention. Route-level absence is what the
test asserts (the route does not exist in the `prod` router); the environment check
survives someone importing the file by mistake; the token means that even a stack
mistakenly booted in `e2e` on a reachable host is not an open door to any account.

### WireMock for the HTTP externals, a fake provider for Edge TTS

**Options:** hand-written stub services per external / one WireMock container / recorded
cassettes replayed in-process.
**Choice:** one WireMock container with per-service mapping files, plus `TTS_PROVIDER=fake`
in the agent.
**Why:** Enable Banking, Google Calendar and Google Tasks, and Whisper are all plain HTTPS
APIs whose base URL can be pointed elsewhere, so one container with JSON mappings covers
three of the four and stays editable by a journey that needs a different answer. Edge TTS
is not an HTTP client we own — `edge_tts` opens its own WebSocket to Microsoft — so the
only injection point is a provider switch in the agent, which returns a fixed silent MP3.

### Only Calendar and Tasks get a redirectable base URL, not Google OAuth

**Options:** point every Google call at WireMock, sign-in included / leave sign-in alone.
**Choice:** leave `GoogleAuthController` untouched.
**Why:** the test login replaces sign-in entirely in e2e, so a stubbed `tokeninfo` endpoint
would have no caller. Calendar and Tasks *do* have callers — MAG-100's sync journey — and
`Google\Service\*` takes a `$rootUrl` constructor argument, so redirecting them costs one
bound parameter each.

### A seed manifest, not hard-coded ULIDs

**Options:** fixed ULIDs in the fixtures / a manifest mapping references to the ids the
seed produced.
**Choice:** the manifest, `api/var/e2e/seed-manifest.json`.
**Why:** this reverses the plan's first draft. No entity exposes `setId()` — the ULID is
built in the constructor — so fixed ids would mean opening an identity setter on twenty
entities purely for the tests, a far worse trade than one JSON file. ULIDs also embed a
timestamp, so "fixed" would have been a lie the first time two runs were compared. The
manifest gives journeys a stable handle (`e2e_recipe_pasta`) without touching the domain.

### Seeded data is fixed, including its dates

**Options:** fixtures with relative dates (`+2 days`) / fixtures anchored to a date passed
to the command.
**Choice:** anchored. `app:e2e:seed --now=<ISO8601>`, defaulting to today at 00:00 UTC.
**Why:** "the next 7 days" and "this month's envelopes" are assertions journeys will make.
Relative dates make them pass on a Tuesday and fail across a month boundary; a hard-coded
date makes every "upcoming" query empty. Anchoring gives both: the seed computes offsets
from an anchor the journey controls, and CI pins the anchor.

### Reset truncates, it does not recreate the schema

**Options:** drop and recreate the schema between suites / truncate every table and reload.
**Choice:** truncate.
**Why:** a schema rebuild is seconds per suite and the journeys will run many suites.
Truncation also keeps the migration-applied schema, so a missing migration fails the e2e
run rather than being papered over by `schema:create` — a failure mode ADR-005 names.
Elasticsearch indices are deleted and rebuilt in the same command, because a stale index
silently returns an empty list (`CLAUDE.md` gotcha).

### Worktree isolation comes from the project name

**Options:** a `.env` per worktree / `COMPOSE_PROJECT_NAME` derived from the directory.
**Choice:** derived, `maggie-e2e-<slug>-<hash>`, computed in the Taskfile from the worktree
path; overridable by exporting `COMPOSE_PROJECT_NAME` (CI passes the run id).
**Why:** the ticket asks for a clean project name derived from the folder. Deriving it
means no file to create, nothing to gitignore, and two worktrees of the same branch name
still differ because the hash covers the absolute path. Volumes, network and container
names all inherit the prefix from Compose, so the project name is the only knob.

### API tests in a worktree run against the e2e stack

**Options:** change `task api:test` to detect worktrees / add a separate task.
**Choice:** add `task e2e:test:api`, leave `task api:test` as the dev-stack command.
**Why:** the ticket asks that API tests target the current worktree's stack. Silently
redirecting `task api:test` would make the same command mean different things depending on
the directory, and the dev stack is still the fast path when working in the main checkout.
The standard and `CLAUDE.md` are updated to say which to use where.

### Two decisions the implementation forced

**A dedicated `e2e` Docker stage, not `dev`.** The plan reused the `dev` target; building
it fails today because `pecl install xdebug` no longer resolves, and that failure is
unrelated to this ticket. Rather than work around it, the stack gets a `FROM base AS e2e`
stage: production `php.ini` for the same fidelity argument as `APP_DEBUG=0`, and no
Xdebug, which would slow every request in a suite designed to run often and which nobody
attaches a debugger to in CI. Source is still bind-mounted, not copied.

**UID and GID computed by the Taskfile.** `UID` is a shell variable, not an environment
one, so `${UID:-1000}` in a Compose file silently falls back to 1000 — and on a host whose
user is not 1000, every bind mount becomes unwritable. The dev stack hides this behind a
gitignored `.env`; a fresh worktree has none. `task e2e:*` therefore exports `id -u` and
`id -g` itself. A stack that only works on machines where the first user is 1000 is not a
stack that works unattended.

## What the ticket leaves for later

- The agent runs against the real Anthropic API until MAG-95 lands the fake LLM. The stack
  wires `ANTHROPIC_API_KEY` through from the environment; journeys that talk to Maggie stay
  on hold, which is why the smoke journey here does not.
- No `e2e` product flavor for the mobile app (MAG-98). The stack is reachable over plain
  HTTP from an emulator, which is what that ticket will need.

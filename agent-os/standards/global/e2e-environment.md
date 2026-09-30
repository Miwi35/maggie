# The E2E Environment

An isolated stack, deterministic data, a login that does not go through Google,
and stand-ins for every external service. One per git worktree, so parallel
agents never collide. Built by MAG-94; the browser and emulator harnesses that
sit on top are MAG-97 and MAG-98.

Read this before writing anything that runs against the stack. The Definition of
Done itself lives in [testing.md](testing.md).

## Commands

| Command | What it does |
|---|---|
| `task e2e:up` | Build, install, clear cache, migrate, print the URL |
| `task e2e:seed` | Reset the database to the fixture set, rebuild and refresh the search indices |
| `task e2e:smoke` | Run the smoke journey against the running stack |
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

Never run `docker compose -f docker-compose.e2e.yml` by hand: the project name
and the resolved host port both come from the Taskfile, and both are what keep
stacks apart.

## In a git worktree

`task api:test` runs `docker compose exec` against the **dev** stack, which
mounts the main checkout — in a worktree it tests the wrong code. Use
`task e2e:test:api`, which runs against the stack mounting *this* worktree.

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
`E2eLoginRouteAbsenceTest` asserts the route is absent from the `prod`, `dev`
and `test` routers — **if you add anything else e2e-only to the HTTP surface,
extend that test**.

## Seeded data

`task e2e:seed` truncates every table, loads `api/fixtures/e2e/*.yaml` in file
name order, and rebuilds the Elasticsearch indices.

Dates are anchored, never relative and never literal:

```yaml
startAt: '<e2eDate("+2 days 09:00")>'
year: '<e2eYear()>'
```

`app:e2e:seed --now=<ISO8601>` moves the anchor; it defaults to today at
midnight UTC. That is what lets a journey assert both "this week" and an exact
value. A literal date in a fixture makes every "upcoming" query empty; a
`+2 days` computed from `date()` makes the fixture pass on a Tuesday and fail
across a month boundary.

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

All four default to today's behaviour when unset, so dev and prod are untouched.
Google sign-in is deliberately not stubbed: the test login replaces it.

The conversation model is **not** simulated yet — MAG-95 brings
`LLM_PROVIDER=fake`. Until then, a journey that talks to Maggie is not
deterministic, and the smoke journey does not.

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

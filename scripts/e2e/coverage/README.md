# Coverage per e2e journey — servers (agent, API)

Part A of the spec « Sélection e2e par couverture ». The nightly runs every journey with
`E2E_COVERAGE=1`, records which server lines each one executes, and part C builds the
line → journey map from it. **Off everywhere else**: without `E2E_COVERAGE=1` pcov is not loaded,
the agent runs as before (`uvicorn --reload`, no middleware), and nothing is written.

## Run

```sh
E2E_COVERAGE=1 task e2e:up          # the variable must be set when the stack starts
task e2e:web                        # journeys sending X-E2E-Journey (part B)
task e2e:coverage:collect           # → e2e/coverage/raw/{api,agent}/<slug>.json
task e2e:down
```

`collect` can run again later on the same stack: it reads without consuming and rewrites the raw
files with everything recorded so far. It **restarts the agent** (coverage.py writes its data when
the process ends) and returns once it is healthy again. It fails when no journey at all was
recorded (no request carried the header).

Locally, the recorded data lives in bind mounts that `task e2e:down` does not empty
(`agent/.e2e-coverage/`, `api/var/e2e/coverage/requests/`): remove them before a fresh run, or the
lines of the previous one are added in. The nightly starts from a fresh checkout.

`E2E_COVERAGE` is `1` or unset: any other non-empty value loads pcov without anything recording.

## Input: the journey of a request

Every request a journey makes carries `X-E2E-Journey: <repo path of the journey>`
(`e2e/web/tests/chat.spec.ts`, `e2e/mobile/flows/01-login-chat.yaml`). Anything that is not a
repo path (`[A-Za-z0-9_./-]`, no `..`, 200 characters at most) is ignored.

| Server | How a line gets its journey |
|---|---|
| Agent (`agent/app/e2e_coverage/`) | Runs under `coverage run` with dynamic contexts. A pure ASGI middleware puts the header into a context variable; `asyncio` copies it into every task started from the request — the chat turn's background task included (MAG-344). The coverage plugin returns that variable as the context each time a task resumes, so two journeys running side by side (Playwright's two workers) never share a label. The agent's MCP calls send the header on, so the API attributes them to the same journey. |
| API (`api/modules/core/src/E2e/Coverage/`) | pcov, loaded only under `E2E_COVERAGE=1` (`.docker/php/coverage.d/pcov.ini`). `JourneyCoverageListener` (e2e environment only) starts it on `kernel.request` of a request with the header and, on `kernel.terminate`, adds the executed lines of the API's own files (not `vendor/`, `var/`, tests) to `api/var/e2e/coverage/requests/<slug>/<php-fpm pid>.json` — one file per journey and worker, since a worker serves one request at a time. `app:e2e:coverage:merge` folds them per journey. |

## Output (contract 2)

`e2e/coverage/raw/<component>/<slug>.json`, slug = journey id with `/` and `.` → `_`:

```json
{"journey": "e2e/web/tests/chat.spec.ts", "files": {"agent/app/api/routes.py": [143, 144, 146]}}
```

Paths from the repository root, lines sorted and unique. The directory is emptied by each
`collect`, and gitignored.

## Not covered

- **Messenger workers**: what a request dispatches to RabbitMQ runs in the `worker` container,
  without the header — Elasticsearch indexing, projections, async handlers. Not attributed; a change
  there falls back to `e2e/impact-map.yml`.
- **Agent work outside a request**: the proaction scheduler and consumer, the turn sweeper after a
  restart, start-up. No journey, dropped.
- **Code run before the listener or the middleware**: PHP front controller and kernel boot, Python
  module imports. Shared by every journey anyway.
- **Mercure** and **ciqual** are not instrumented.

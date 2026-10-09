# Coverage per e2e journey

« Sélection e2e par couverture » (Linear spec of the same name): each night the
whole e2e suite runs with `E2E_COVERAGE=1`, every journey records the lines it
ran, and a PR then plays only the journeys that ran a line it changes. Off on
every other run: nothing here costs a PR anything.

The contracts, binding for every part:

- **Journey id** — the journey's path in the repository
  (`e2e/web/tests/chat.spec.ts`, `e2e/mobile/flows/01-login-chat.yaml`), sent on
  every request as `X-E2E-Journey`.
- **Raw coverage** — `e2e/coverage/raw/<component>/<slug>.json`, component in
  `agent`, `api`, `admin`, `mobile`, slug = the id with `/` and `.` turned into
  `_`:

  ```json
  {"journey": "e2e/web/tests/chat.spec.ts", "files": {"admin/src/App.tsx": [23, 24, 25]}}
  ```

`e2e/coverage/` is git-ignored.

## Servers — agent, API

Part A. Without `E2E_COVERAGE=1` pcov is not loaded, the agent runs as before (`uvicorn --reload`, no middleware), and nothing is written.

### Run

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

### Input: the journey of a request

Every request a journey makes carries `X-E2E-Journey: <repo path of the journey>`
(`e2e/web/tests/chat.spec.ts`, `e2e/mobile/flows/01-login-chat.yaml`). Anything that is not a
repo path (`[A-Za-z0-9_./-]`, no `..`, 200 characters at most) is ignored.

| Server | How a line gets its journey |
|---|---|
| Agent (`agent/app/e2e_coverage/`) | Runs under `coverage run` with dynamic contexts. A pure ASGI middleware puts the header into a context variable; `asyncio` copies it into every task started from the request — the chat turn's background task included (MAG-344). The coverage plugin returns that variable as the context each time a task resumes, so two journeys running side by side (Playwright's two workers) never share a label. The agent's MCP calls send the header on, so the API attributes them to the same journey. |
| API (`api/modules/core/src/E2e/Coverage/`) | pcov, loaded only under `E2E_COVERAGE=1` (`.docker/php/coverage.d/pcov.ini`). `JourneyCoverageListener` (e2e environment only) starts it on `kernel.request` of a request with the header and, on `kernel.terminate`, adds the executed lines of the API's own files (not `vendor/`, `var/`, tests) to `api/var/e2e/coverage/requests/<slug>/<php-fpm pid>.json` — one file per journey and worker, since a worker serves one request at a time. `app:e2e:coverage:merge` folds them per journey. |

### Output (contract 2)

`e2e/coverage/raw/<component>/<slug>.json`, slug = journey id with `/` and `.` → `_`:

```json
{"journey": "e2e/web/tests/chat.spec.ts", "files": {"agent/app/api/routes.py": [143, 144, 146]}}
```

Paths from the repository root, lines sorted and unique. The directory is emptied by each
`collect`, and gitignored.

### Not covered

- **Messenger workers**: what a request dispatches to RabbitMQ runs in the `worker` container,
  without the header — Elasticsearch indexing, projections, async handlers. Not attributed; a change
  there falls back to `e2e/impact-map.yml`.
- **Agent work outside a request**: the proaction scheduler and consumer, the turn sweeper after a
  restart, start-up. No journey, dropped.
- **Code run before the listener or the middleware**: PHP front controller and kernel boot, Python
  module imports. Shared by every journey anyway.
- **Mercure** and **ciqual** are not instrumented.

## Clients

The admin (Playwright) and the Android app (Maestro): both send the journey id,
both record what their own code ran.

### Admin — `e2e/web/`

**Journey id.** Always on, nightly or not. `fixtures/journey.ts` builds the id
from the spec's path under `testDir`; `fixtures/index.ts` puts it on every
browser context the fixtures open (`context.setExtraHTTPHeaders`, so the
admin's `fetch` calls to the API and the agent, and its Mercure `EventSource`,
all carry it without a line of admin code) and on every API client (`api`, the
other users' `api`). A test that builds its own client adds
`[JOURNEY_HEADER]: journey` (see `tests/auth.spec.ts`). `global-setup.ts` and
the sign-in are no journey and send none.

**Coverage.** With `E2E_COVERAGE=1`:

1. the admin bundle is built with source maps (`admin/vite.config.ts`;
   `docker-compose.e2e.yml` passes the flag to `admin-build`, and refuses a
   restored `E2E_ADMIN_PREBUILT` bundle that has none). Locally:
   `E2E_COVERAGE=1 task e2e:admin:build`;
2. every page a fixture opens records Chromium's precise JS coverage from before
   its first navigation (`fixtures/coverage.ts`, `AdminCoverage`) and gives it
   up before its context closes;
3. each script under `/admin/` is mapped back to `admin/src/**` through its
   `.js.map`, fetched from the stack once per worker (`fixtures/coverage-lines.ts`:
   V8's innermost range decides whether an offset ran; a source line ran when
   one of its mappings landed on code that ran). `node_modules` is dropped;
4. each test writes a part, `e2e/coverage/parts/admin/<slug>/…json`;
   `global-teardown.ts` folds the parts of a spec into
   `e2e/coverage/raw/admin/<slug>.json` — a line any test of the spec ran
   belongs to the spec — merging with a file already there.

The container mounts `e2e/coverage` at `/coverage` (`E2E_COVERAGE_DIR`).
Measured on the built admin: mapping a page costs ~0.4 s the first time a
worker meets the 8 MB map, much less after.

### Android — `mobile/`, `e2e/mobile/`

**Journey id.** Every `launchApp` of a flow passes
`arguments: { e2e_journey: <the flow's own path> }` — the one after a `stopApp`
too, since the new process knows nothing of the last — and `task
e2e:mobile:lint` (check 8) fails on a missing or copied id. Maestro turns it
into an intent extra; `E2eHooks`, a content provider of the e2e flavor only
(created before the Application, so no shared code calls it), reads it off every
activity before its `onCreate`, and `JourneyHeaderPlugin` adds `X-E2E-Journey` to
every request of the app's Ktor client (`installJourneyHeader()` in
`MaggieApp.kt`, a no-op in dev and prod). The flows name themselves rather than
`run.sh` passing `-e JOURNEY=…`: a single Maestro run of the whole workspace
cannot vary a variable per flow, and an id written in the flow is the same in
CI, locally and in the nightly.

**Coverage.** With `E2E_COVERAGE=1`:

1. `e2e/mobile/build-apk.sh` builds with `-Pe2eCoverage=true`: the debug build
   type's `enableAndroidTestCoverage`, so AGP instruments the app's classes with
   JaCoCo 0.8.12 (pinned, `testCoverage.jacocoVersion`) and packs its runtime in
   the APK. It copies the uninstrumented classes it compiled to
   `e2e/mobile/apk/classes` — whatever carries the APK must carry them;
2. `e2e/mobile/run.sh` runs the flows one Maestro at a time — the next flow's
   `clearState` would kill the process and its counts. After each flow it sends
   `am broadcast -n com.maggie.app.e2e/com.maggie.app.e2e.CoverageDumpReceiver`:
   the app writes `RT.getAgent().getExecutionData(reset = true)` to
   `files/e2e-coverage.ec`, and `adb exec-out run-as … cat` pulls it to
   `e2e/coverage/exec/mobile/<slug>.ec` (+ `<slug>.journey`; `<slug>.missing`
   when it could not). A failed flow keeps its coverage; the JUnit reports of the
   flows are merged into the one `scripts/e2e/verdict.sh` reads. The directory is
   not emptied between two runs: a CI lot runs `run.sh` up to four times
   (journeys, quarantine, a retry of each), and each flow replaces its own files;
3. once the lot is over, `collect-lot.sh`'s hook `collect.d/50-mobile.sh` runs
   `scripts/e2e/coverage/mobile.sh <exec dir> <classes dir>` (the classes in
   `e2e/mobile/apk/classes`, or `E2E_MOBILE_CLASSES`), which runs JaCoCo's
   CLI (downloaded once, checksum verified, into `.e2e-cache/jacoco/`) for an XML
   report per flow, and `jacoco_lines.py` turns it into
   `e2e/coverage/raw/mobile/<slug>.json`: a line ran when one of its
   instructions did, Kotlin files located under `mobile/app/src/{main,e2e}/java`.
   Needs `java` and `python3`. The hook fails — the lot is not complete, no map
   that night — on a mobile lot without data, a `.missing` flow, or a flow that
   matched no class of the app.

What the counts miss: a flow that stops the app mid-way (`stopApp`) loses what
ran before; the dump after it only sees the relaunch onwards.

### What only the first nightly proves

Proven locally, on the e2e stack: `E2E_COVERAGE=1 task e2e:web` on
`auth.spec.ts` and `mercure.spec.ts` (12 tests green) wrote one raw file per
spec, ~160 `admin/src` files each, different lines for each; every request of
the page — document, `fetch` to `/api` and `/agent`, the Mercure `EventSource`
— carried the spec's `X-E2E-Journey`. Also: the e2e flavor's unit tests
(`JourneyHeaderTest`, `CoverageDumpTest`, `WT_MOBILE_VARIANT=e2e task
wt:test:mobile`); `mobile.sh` + `jacoco_lines.py` on JaCoCo data from a JVM; the
merged JUnit read by `verdict.sh`; `task e2e:mobile:lint`.

No emulator ran here, so the nightly proves the Android half:

- that `-Pe2eCoverage=true` instruments the APK and `CoverageDumpReceiver` answers
  `dumped <n>` — `run.sh` warns per flow otherwise;
- that `run-as` reads the file on the CI emulator image;
- that the classes copied beside the APK match its instrumented ones (else
  `mobile.sh` reports no files).

## The nightly, end to end

`nightly-e2e.yml` calls `ci.yml` with `coverage: true`:

| Job | Coverage |
|---|---|
| `E2E Mobile APK` | `E2E_COVERAGE=1 build-apk.sh`: the instrumented APK (artifact `maggie-e2e-apk`, every format installs it) and its classes (artifact `maggie-e2e-apk-classes`, this run only) |
| `E2E shard` *n* | `E2E_COVERAGE=1` for the stack and Playwright; `e2e/coverage/` emptied after checkout; after the journeys `collect-lot.sh web-<n>` (`task e2e:coverage:collect`, `40-admin.sh`); artifact `e2e-coverage-raw-web-<n>` = `e2e/coverage/raw` (`api/`, `agent/`, `admin/`, `_lots/web-<n>.ok`) |
| `E2E Mobile journeys` phone lot *n* | the same for the stack, `run.sh` one flow at a time, the classes downloaded beside the APK; `collect-lot.sh mobile-phone-<n>` (`task e2e:coverage:collect`, `50-mobile.sh`); artifact `e2e-coverage-raw-mobile-phone-<n>` (`api/`, `agent/`, `mobile/`, `_lots/…`). Foldable and tablet: no coverage |
| `E2E line map` | every `e2e-coverage-raw-*` in a directory of its own under `e2e/coverage/lots/`, then `build-map.py --expect web-1… mobile-phone-1…` (the lots `impacted.sh select --all` plays): raw files are unioned by their `journey`, so a spec two shards split counts once with the lines of both |

PR runs set none of this: no `E2E_COVERAGE`, the plain APK, no coverage artifact.

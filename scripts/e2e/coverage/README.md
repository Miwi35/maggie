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
   `e2e/coverage/exec/mobile/<slug>.ec` (+ `<slug>.journey`). A failed flow keeps
   its coverage; the JUnit reports of the flows are merged into the one
   `scripts/e2e/verdict.sh` reads;
3. `scripts/e2e/coverage/mobile.sh <exec dir> <classes dir>` (run by `run.sh`
   when the classes are beside the APK, or `E2E_MOBILE_CLASSES`) runs JaCoCo's
   CLI (downloaded once, checksum verified, into `.e2e-cache/jacoco/`) for an XML
   report per flow, and `jacoco_lines.py` turns it into
   `e2e/coverage/raw/mobile/<slug>.json`: a line ran when one of its
   instructions did, Kotlin files located under `mobile/app/src/{main,e2e}/java`.
   Needs `java` and `python3`.

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

Part C wires it: `E2E_COVERAGE=1` for the e2e stack, `build-apk.sh` and
`run.sh`; an empty `e2e/coverage/` at the start of the nightly (the admin fold
merges into a raw file already there); `e2e/mobile/apk/classes` uploaded with
the APK; `scripts/e2e/coverage/*.sh` added to CI's shellcheck; the union of
`raw/admin/*.json` across web shards (a spec split over two shards writes one
file per shard, same name).

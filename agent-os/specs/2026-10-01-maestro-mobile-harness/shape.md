# Maestro Harness — Decisions

Four decisions were taken without asking, each because the ticket, the existing
code or a standard settled it. They are repeated in the pull request description.

## 1. How the app signs in on an emulator

- **Dilemma** Google Credential Manager is a system dialog Maestro cannot tap, so
  the journeys need another door — without putting one in the APK that ships.
- **Options** (a) a `BuildConfig` flag read by shared code; (b) a text field on
  the login screen where a flow types a seeded address; (c) a `SignInStrategy`
  seam with the implementation in a flavor source set.
- **Choice** (c). `src/main/` declares the seam, `src/google/` holds the Google
  door for `dev` and `prod`, `src/e2e/` the test login.
- **Why** The guard rails treat `mobile/**/data/auth/` as a permissions change for
  a reason: a flag (a) leaves the test login in the production binary, one
  `true` away from being live. (b) adds an e2e-only widget, so the journey would
  no longer be driving the screen the owner uses. With (c) the shipped APK does
  not contain the code at all, which is the same guarantee
  `api/modules/*/src/E2e/` gives on the API side
  (`agent-os/standards/global/e2e-environment.md`).

## 2. How the APK reaches the stack

- **Dilemma** The stack publishes one ephemeral host port, and the standard says
  nothing may hard-code it — but an APK's base URL is fixed at build time.
- **Options** (a) resolve the port and pass it as a Gradle property, rebuilding
  per run; (b) build against a fixed port on the device's loopback and bridge it
  with `adb reverse`; (c) read the URL at runtime from a launch argument.
- **Choice** (b), with (a) still available through `-PE2E_API_BASE_URL`.
- **Why** (b) makes the APK independent of the stack: the same binary works
  against any of them, CI can compile it while eleven containers come up, and a
  Gradle cache hit is not thrown away because Docker picked a different port.
  (c) would mean runtime plumbing in shared code for a build-time problem.

## 3. Its own CI job rather than a step of `E2E Stack`

- **Dilemma** `e2e-stack` already pays for a stack, and its own comment says the
  browser journeys stay in it rather than becoming a job.
- **Options** (a) add the emulator to `e2e-stack`; (b) a new `e2e-mobile` job
  with its own stack.
- **Choice** (b).
- **Why** An emulator is the flakiest thing in this pipeline, and in (a) a flake
  would block a pull request that only touched the API — `e2e-stack` is a
  required check. (b) also gives the nightly matrix the three formats in
  parallel, which a single job cannot. The cost is a second stack on a second
  runner, isolated by `COMPOSE_PROJECT_NAME` like every other one.
  **Consequence for the owner:** `E2E Mobile (phone)` is a new check and is not
  required by branch protection until it is added there.

## 4. The calendar family of MAG-93 is a follow-up

- **Dilemma** The ticket names seven regressions to aim for; three of them are
  calendar ones and need work the socle does not.
- **Options** (a) all seven in this pull request; (b) the socle, the first flow
  and the four voice ones, with the calendar family split out.
- **Choice** (b).
- **Why** The calendar three need `testTag`s through the week and day views,
  seeded multi-day data addressed per view, and a stubbed agenda import — a
  deliverable of its own, and `CLAUDE.md` says one ticket is what a single pull
  request can close. The voice four are in, because the ticket says one flow
  covers them and that turned out to be true: `VoiceManager.speak()` sets
  `SPEAKING` on its first line, so « Maggie parle... » is assertable text even on
  an emulator with no audio.

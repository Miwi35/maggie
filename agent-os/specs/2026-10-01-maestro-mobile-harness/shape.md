# Maestro Harness — Decisions

Five decisions were taken without asking, each because the ticket, the existing
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

## 4. Three of MAG-93's seven targets are follow-ups

- **Dilemma** The ticket names seven regressions to aim for. Three are calendar
  ones needing work the socle does not contain; and the voice one the ticket
  assumed "one flow covers" turns out not to be assertable from a flow at all.
- **Options** (a) all seven in this pull request; (b) the socle, the first flow
  and what of the voice family is genuinely provable, splitting out the rest;
  (c) ship the voice assertion anyway, since it would be green.
- **Choice** (b) — [MAG-204](https://linear.app/meven/issue/MAG-204) for the
  calendar family, [MAG-205](https://linear.app/meven/issue/MAG-205) for the TTS
  proof.
- **Why** (c) is the one to reject hardest: « Maggie parle... » must not appear is
  green on a build with all three fixes reverted, because the fake TTS answers in
  milliseconds, `assertNotVisible` waits for disappearance rather than catching a
  blip, and `disable-animations: true` collapses the only timer available. A
  green assertion that proves nothing is worse than a missing one — it stops
  anybody from writing the real one. So the flow ships the sound half (the
  overlay really opens on a history that arrived over HTTP, with the old answer
  asserted on screen) and says in its header exactly what it does not prove. The
  calendar three are a separate deliverable, and `CLAUDE.md` says one ticket is
  what a single pull request can close.

## 5. The tag root goes on every window, not on the activity

- **Dilemma** The first attempt set `testTagsAsResourceId` once, at the root of
  `MainActivity`'s content. CI then failed on `assertVisible: id: chat_input`
  after resolving `chat_open` one line earlier.
- **Options** (a) address the sheets by text instead of by id; (b) a wrapping
  composable per window; (c) a `Modifier.uiTagRoot()` applied to a node each
  window already has.
- **Choice** (c), plus a fourth check in `lint.sh`.
- **Why** `testTagsAsResourceId` is resolved within one semantics owner, and a
  `Dialog` or a `ModalBottomSheet` is a separate platform window with its own —
  so the activity's flag cannot reach them. (a) gives up the robustness the tags
  exist for; (b) adds a layout node per sheet. (c) adds nothing. The lint check
  matters more than the fix: nothing else can see this failure — the ids are
  declared *and* used, simply unreachable, so checks 1–3 passed cleanly on the
  very ids the emulator could not find.

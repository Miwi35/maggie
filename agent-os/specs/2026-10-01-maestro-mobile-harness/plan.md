# Maestro Harness for the Android App — Plan

Ticket: [MAG-98](https://linear.app/meven/issue/MAG-98/socle-maestro-pour-lapp-android) · type `Task`
Project: Suite e2e et tests systématiques
Decisions: [shape.md](shape.md)

E2E: `N/A — task`. This *is* the e2e harness for the mobile app; its own journeys
(`e2e/mobile/flows/`) are the deliverable, and there is no user-facing behaviour
to cover besides them.

## Acceptance criteria

1. `task e2e:mobile` installs the app on one connected device or emulator, points
   it at this worktree's e2e stack, and runs every flow in `e2e/mobile/flows/`.
2. The app signs in without Google: an `e2e` Gradle flavor goes through
   `POST /api/auth/e2e/login`, and **neither `dev` nor `prod` compiles that
   code**.
3. The first flow covers login → dashboard → open the chat → send a message →
   Maggie's scripted answer.
4. `task e2e:mobile:lint` checks the flows with no device and no stack, and is
   part of `task lint:all`. It fails when a flow uses an `id:` that is not a
   declared `testTag`.
5. CI runs the journeys on a headless emulator with KVM, on every pull request
   touching the app, the flows or the stack.
6. The nightly run widens the emulator matrix to a phone, a foldable and a tablet
   (MAG-35, MAG-91).
7. A flow opens the voice overlay on a conversation that already has an answer in
   it, after a restart, and asserts the old answer is on screen — the setup the
   voice family of MAG-93 needs (`24b64ba`, `9e24327`, `54e35bd`). It also asserts
   the chat input survives the keyboard (`fc168b5`), by *using* the send button
   rather than looking at it.

   **Not met, and split out:** proving « no TTS started » (MAG-205). See
   *Out of scope*.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`.

## Task 2: The `e2e` Gradle flavor and the sign-in seam

`mobile/app/build.gradle.kts`, `mobile/app/src/{main,google,e2e}/`

- `SignInStrategy` in `src/main/data/auth/`: one method, `authenticate(context) →
  AuthResponse`. `AuthManager` keeps everything after the credential — which
  tokens are persisted, under which keys — so the two doors cannot drift.
- `GoogleSignIn` moves to `src/google/`, a source set `dev` and `prod` share
  through `sourceSets { getByName("dev") { java.srcDir(…) } }`. `E2eSignIn` lives
  in `src/e2e/` and is compiled into that flavor alone.
- Flavor `e2e`: `applicationIdSuffix = ".e2e"`, empty `GOOGLE_CLIENT_ID`,
  `API_BASE_URL = http://localhost:8099` (overridable with
  `-PE2E_API_BASE_URL`), plus `E2E_LOGIN_TOKEN` and `E2E_LOGIN_EMAIL` — two
  fields that exist in no other flavor's `BuildConfig`, so referencing them from
  shared code is a compile error.

**Verify:** `:app:compileE2eDebugKotlin` and `:app:compileProdReleaseKotlin`,
`:app:testE2eDebugUnitTest`.

## Task 3: testTags Maestro can see

`mobile/app/src/main/java/com/maggie/app/{MainActivity.kt,ui/UiTags.kt}` and the
five composables the flows drive.

- `Modifier.uiTagRoot()` on the root of **every window's** content, not once on
  the activity. `testTagsAsResourceId` resolves within one semantics owner, and a
  `Dialog` or a `ModalBottomSheet` is a separate one — the first CI run of this
  harness failed on exactly that, with `chat_open` resolving and `chat_input`,
  inside the chat sheet's `Dialog`, having no resource id.
- `UiTags` holds every id, which is what makes a cross-check possible between the
  flows and the code.

## Task 4: `e2e/mobile/`

- `maestro.sh` — the CLI pinned by version **and** by the sha256 the release
  publishes, installed under `.e2e-cache/`.
- `run.sh` — one device, `adb reverse` onto the stack's ephemeral port, the device
  on `Europe/Paris`, `installE2eDebug`, then the flows with a JUnit report.
- `lint.sh` — `maestro check-syntax`, every `id:` against `UiTags.kt`, every
  `appId:` against the flavor's applicationId, and every window that carries a tag
  against `uiTagRoot()`. The last check exists because the first three passed
  cleanly on the ids the emulator could not find.
- `config.yaml`, `flows/01-login-chat.yaml`, `flows/02-voice-overlay.yaml`,
  `subflows/sign-in.yaml`, `README.md`.

## Task 5: Taskfile and CI

- `task e2e:mobile`, `task e2e:mobile:lint` (added to `task lint:all`),
  `task e2e:mobile:maestro`.
- `ci.yml`: a `mobile` paths filter, a `mobile_devices` matrix output, and a job
  `E2E Mobile (<device>)` — stack, KVM, `reactivecircus/android-emulator-runner`
  on API 34 `google_apis`, `task e2e:mobile` as its script, `report/` uploaded on
  failure. Added to `incident-gate`'s `needs`, and `e2e/mobile/*.sh` to the
  shellcheck list.

## Task 6: Documentation

`agent-os/standards/global/e2e-environment.md` (the harness, and the four
stack-level properties), `global/testing.md` (the harness exists; what belongs on
an emulator and what does not), `mobile/android-app.md` (the flavor),
`CLAUDE.md`, and the now-false comment in `e2e/smoke/smoke.sh`.

## Out of scope

Two of MAG-93's targets are **not** delivered here, each as its own ticket,
`blockedBy` this one:

- **[MAG-204](https://linear.app/meven/issue/MAG-204)** — the calendar family:
  multi-day events missing from the days they cross in the week and day views
  (`76017dd`, `dfad086`), and the screen not refreshing after an agenda import
  (`2dfdbb3`). It needs `testTag`s through the calendar views, seeded multi-day
  data addressed per view, and a stubbed import.
- **[MAG-205](https://linear.app/meven/issue/MAG-205)** — proving « no TTS
  started ». The ticket assumed one flow could assert it; it cannot, for three
  independent reasons the flow's own header spells out (the fake TTS answers
  instantly, Maestro's `assertNotVisible` waits for disappearance, and
  `disable-animations` collapses the only available timer). Shipping a green
  assertion that survives a revert of all three fixes would be worse than
  shipping none, so what is here is the sound setup plus an `assertNotVisible`
  labelled as catching only a stuck `SPEAKING`. The second half of `fc168b5` —
  the context sheet's height — goes with it: the first row is visible at `0.5f`
  as at `0.85f`, so no assertion discriminates.

The two DTO-contract regressions (`0a281a7`, `b576cc6`) are MAG-104's, as the
ticket says.

# The Mobile Journeys

Maestro driving the Android app on an emulator, against the e2e stack (MAG-98).

```sh
task e2e:up                               # the stack, once
task e2e:mobile                           # reseed, install the e2e flavor, run every flow
task e2e:mobile -- --include-tags voice    # one family
task e2e:mobile -- flows/01-login-chat.yaml
task e2e:mobile:lint                      # no device, no stack; also in `task lint:all`
task e2e:mobile:maestro                   # just download the pinned CLI
```

`task e2e:mobile` needs **one** connected device or emulator — start one from
Android Studio, or `$ANDROID_HOME/emulator/emulator -avd <name>`. With more than
one, name it: `ANDROID_SERIAL=emulator-5554 task e2e:mobile`.

## What makes this work at all

Three problems, three answers; everything else follows from them.

**Google Credential Manager cannot be automated.** It is a system dialog outside
the app's view hierarchy, so Maestro sees the login screen and then nothing. The
app therefore has an **`e2e` Gradle flavor** whose only difference is the door
behind the sign-in button: `mobile/app/src/e2e/.../E2eSignIn.kt` posts to
`/api/auth/e2e/login` with `X-E2E-Token` (MAG-94) instead of opening Google.

It is a flavor source set and not a build flag, which matters: `src/main/` knows
neither door, `dev` and `prod` link the Google one from `src/google/`, and the
APK that ships does not contain the test login at all. The screen itself is
untouched — same button, same logo — so the journey drives the production login
screen.

**The stack publishes an ephemeral host port, and nothing may hard-code it.** So
the APK is built against a fixed port on the *device's* loopback
(`http://localhost:8099`) and `run.sh` bridges it with `adb reverse`. The same
APK works against any stack, and CI compiles it while eleven containers come up.
`localhost` and not `127.0.0.1`, because the app's `network_security_config.xml`
permits cleartext for that host name.

**Compose nodes have no resource id.** Maestro reads the hierarchy through
UiAutomator, which only sees a `testTag` when the root opts in — `MainActivity`
does, with `testTagsAsResourceId`. The tags themselves are declared in
`mobile/app/src/main/java/com/maggie/app/ui/UiTags.kt`, and `task
e2e:mobile:lint` fails when a flow uses an `id:` that is not one of them.

## Layout

| | |
|---|---|
| `config.yaml` | the workspace: which files are flows, and in which order |
| `flows/` | the journeys, numbered — what `maestro test` runs |
| `subflows/` | shared steps (`sign-in.yaml`), kept out of the `flows` glob on purpose |
| `run.sh` | the whole run: device, bridge, time zone, install, flows |
| `maestro.sh` | downloads the pinned CLI into `.e2e-cache/` |
| `lint.sh` | syntax, testTags, applicationId — seconds, no device |
| `report/` | JUnit report and the screenshots of a failed run (gitignored) |

A file put in `flows/` is run as a journey. A shared sequence goes in
`subflows/`, outside the glob — left in `flows/` it would also be run on its own,
against an app nobody launched.

## Writing a flow

1. **Sign in through the subflow**, not by retyping its steps:
   `- runFlow: ../subflows/sign-in.yaml`. It ends on the dashboard, so a flow
   starts from a signed-in app.
2. **`launchApp: clearState: true` and `permissions: all: allow`.** Without the
   first, a second run starts signed in and never sees the login screen; without
   the second, a runtime permission dialog belongs to the system UI and the step
   that meets one fails as "the button is not there".
3. **Address the app by `id:`** wherever a tag exists, and add one to
   `UiTags.kt` when it does not. A text anchor breaks on a reworded string and
   the failure reads as a broken feature.
4. **Every `text:` selector is a regular expression Maestro matches against the
   node's whole text.** Wrap them in `.*`: a bare « …aujourd'hui ? » reads the
   `?` as "the space before me is optional" and matches nothing at all. Wrapped,
   a selector behaves the same whichever way Maestro is matching.
5. **Wait with `extendedWaitUntil`**, never with a bare `assertVisible` after an
   action. The first HTTP call of a cold app pays for Ktor and OkHttp being
   class-loaded; 20–60 s timeouts here are not generosity.
6. **Assert on seeded data, never on counts.** `api/fixtures/e2e/` is shared with
   the browser suite, which writes to the same user at the same time.
7. **A scenario before the step that needs it.** Maggie answers from
   `agent/fixtures/fake-llm/`; nothing matching means « [fake-llm] aucun scénario
   ne correspond à : … », which is a plausible-looking bubble. Assert
   `assertNotVisible: ".*aucun scénario.*"` after an exchange.

## What belongs here, and what does not

The emulator is the only place a few things are visible, and MAG-93 counted them:
a sheet laid out under the keyboard, an overlay that speaks on open, a view that
does not refresh after an import. All of them are about a real app on a real
screen.

Everything else is cheaper elsewhere. A ViewModel state transition is a JUnit
test (`mobile/app/src/test/`), seconds instead of an emulator boot. A DTO field
that stopped deserialising is `DtoContractTest` against `api/contract/`
(MAG-104). Maggie's wording is the eval suite, on the real model.

## Traps

- **The device's time zone is part of the fixture.** The seed anchors on midnight
  in **Paris**; an emulator boots on UTC. Between 22:00 and midnight UTC the two
  are on different days and « Déjeuner avec Alex » sits on the device's tomorrow —
  the dashboard assertion then fails for an hour a day and for no other reason.
  CI passes `-timezone Europe/Paris`; `run.sh` tries to set it on an emulator
  somebody else started and warns when it cannot (a physical phone will not give
  it root, and is on Paris time anyway).
- **No sound card in CI.** The emulator runs with `-noaudio`, so
  `VoiceManager.startListening()` fails and the overlay lands in its `ERROR`
  state. `02-voice-overlay.yaml` is written around that: it asserts on what the
  overlay must *not* say, never on listening succeeding.
- **An absence needs a window.** The TTS regression fired a second or two after
  the history arrived, so asserting « Maggie parle... » absent once would pass on
  the broken build. The flow asserts, waits with `waitForAnimationToEnd`, and
  asserts again — the Maestro idiom for a bounded wait, since it returns on the
  timeout and never fails.
- **An absence also needs something to have been there.** "It did not re-speak"
  proves nothing if the history never loaded. Assert the old answer is on screen
  first; a vacuous pass is worse than a failure.
- **`maestro check-syntax` never looks at `config.yaml`.** Maestro checks for a
  device before reading the workspace config, so a typo there only surfaces on
  the emulator. Keep that file small.
- **The flavor installs as `com.maggie.app.e2e`.** It sits beside the dev and
  prod builds on the owner's phone rather than replacing either. `task
  e2e:mobile` on a real phone works and is the fastest way to debug a flow — the
  phone just has to reach the host over USB, which `adb reverse` already gives.

## In CI

`.github/workflows/ci.yml`, job `E2E Mobile`, on every pull request touching the
app, the flows or the stack: a stack, KVM enabled, `reactivecircus/android-
emulator-runner` on API 34 `google_apis`, and `task e2e:mobile` as its script. A
failed run uploads `report/` — the screenshots are the only way to see what a
headless emulator had on screen.

The nightly run (`nightly.yml` calls `ci.yml`) widens the matrix to a phone, a
**foldable** and a **tablet**, which is what MAG-35 and MAG-91 ask for. The
matrix comes from the `mobile_devices` output of the `changes` job, keyed on
`github.event_name` — in a called workflow that is the caller's event, so
`schedule` means nightly.

The Maestro CLI is pinned by version **and** by the sha256 the release publishes
(`maestro.sh`), for the same reason the Mercure image is pinned by digest: a CLI
that changed how it matches a Compose node would turn every flow red on a branch
that touched nothing. Bumping it is a one-line commit with the new checksum.
Analytics are off (`MAESTRO_CLI_NO_ANALYTICS`): nothing about a run leaves the
runner.

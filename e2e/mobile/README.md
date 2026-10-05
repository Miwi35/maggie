# The Mobile Journeys

Maestro driving the Android app on an emulator, against the e2e stack (MAG-98).

```sh
task e2e:up                                 # the stack, once
task e2e:mobile                             # reseed, install the e2e flavor, run every flow
task e2e:mobile -- --include-tags voice     # one family
task e2e:mobile -- flows/01-login-chat.yaml # one flow — paths are relative to e2e/mobile/
task e2e:mobile:lint                        # no device, no stack; also in `task lint:all`
task e2e:mobile:maestro                     # just download the pinned CLI
```

A `.yaml` argument *replaces* the workspace rather than adding to it: Maestro
takes flow files as a repeatable positional, so passing both would run everything
and then that file again. `run.sh` sorts the arguments for you.

`task e2e:mobile:lint` is the only one of these in `task lint:all`, and it needs a
host JDK plus a ~300 MB first download of the pinned CLI (cached afterwards under
`.e2e-cache/`). `task fix:all` does not call it.

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
UiAutomator, which only sees a `testTag` when the semantics root opts in. The
tags are declared in `mobile/app/src/main/java/com/maggie/app/ui/UiTags.kt` and
the opt-in is `Modifier.uiTagRoot()` — **once per window, not once per app**. A
`Dialog` or a `ModalBottomSheet` is a separate platform window with its own
semantics root, so a flag set on the activity does not reach it: that is how this
harness's first CI run failed, with `chat_open` resolving and `chat_input`, one
line later and inside the chat sheet's `Dialog`, having no resource id at all.

`task e2e:mobile:lint` checks both halves — every `id:` a flow uses is a declared
tag, and every file that opens a window *and* spells `UiTags.` calls `uiTagRoot()`
**once per window**. The second check exists because the first passed cleanly on
exactly the ids the emulator could not find; it is counted per window because
`ChatSheet.kt` opens two, and a file-wide "is it in here somewhere" passed with
one of them missing.

## The clock (MAG-234)

`E2E_NOW=<ISO-8601> task e2e:mobile` runs the whole stack — and the emulator — at
that instant (see `agent-os/standards/global/e2e-environment.md`, *The clock*).
`run.sh` sets the device's date, turns automatic time off for the run, and prints
`E2E_NOW`, `TODAY`, `TRAIN_START`, `TRAIN_END`, the device's date and time zone in
its log (and `report/clock.txt`). Without the variable the device follows the host,
and `TODAY` is the seed's anchor day, not the runner's calendar day.

A flow takes its dates from `TODAY` / `TRAIN_START` / `TRAIN_END`, never from a
literal.

## Layout

| | |
|---|---|
| `config.yaml` | the workspace: which files are flows, and in which order |
| `flows/` | the journeys, numbered — what `maestro test` runs |
| `subflows/` | shared steps (`sign-in.yaml`, `open-calendar.yaml`), kept out of the `flows` glob on purpose |
| `scripts/` | `runScript` helpers: `grocery-api.js` plays the browser and reads the database back |
| `run.sh` | the whole run: device, bridge, time zone, install, flows |
| `maestro.sh` | downloads the pinned CLI into `.e2e-cache/` |
| `lint.sh` | syntax, testTags, tag roots, applicationId, unawaited assertions — seconds, no device |
| `report/` | JUnit report, and for a failed run the screenshots, view hierarchy and `maestro.log` of each flow, plus `device-last-frame.png`, `logcat.txt` and `device-size.txt` (gitignored) |

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
   the failure reads as a broken feature. If the tag is inside a sheet or a
   dialog, that composable needs `Modifier.uiTagRoot()` on the root of its
   content.
4. **Every `text:` selector is a regular expression Maestro matches against the
   node's whole text.** Wrap them in `.*`: a bare « …aujourd'hui ? » reads the
   `?` as "the space before me is optional" and matches nothing at all. Wrapped,
   a selector behaves the same whichever way Maestro is matching.
5. **Wait with `extendedWaitUntil`**, never with a bare `assertVisible` after an
   action — `task e2e:mobile:lint` fails on that. `assertVisible` *does* wait, on
   Maestro's own default, and that is the problem: the timeout is invisible in the
   flow and a cold emulator is exactly where it runs out. The first frame after
   `launchApp` comes after all of `MaggieApp.onCreate`, and the first HTTP call
   pays for Ktor and OkHttp being class-loaded; the 20–60 s here are not
   generosity, they are a CI failure that already happened.
6. **Dates come from `run.sh`, as `-e` variables** — `TODAY`, `TRAIN_START`,
   `TRAIN_END`, computed in the seed's time zone. A flow cannot compute a date and
   one typed into it is wrong by tomorrow; a seed offset (`+5 days`) belongs in
   `run.sh`, next to the others. Some ids are a prefix plus an ISO date
   (`calendar_day_${TODAY}`, `calendar_span_<first>_<last>`, `calendar_event_<day>`):
   declare the prefix as a `*_PREFIX` const in `UiTags.kt` and the lint accepts any
   id that starts with it. Both ends of a calendar bar are read with a regex
   (`calendar_span_${TRAIN_START}_.*`) — Maestro matches `id:` as one.
7. **Assert on seeded data, never on counts.** `api/fixtures/e2e/` is shared with
   the browser suite, which writes to the same user at the same time. A flow that
   writes labels its lines with its ticket (`… MAG-178`) and removes them.
8. **A scenario before the step that needs it.** Maggie answers from
   `agent/fixtures/fake-llm/`; nothing matching means « [fake-llm] aucun scénario
   ne correspond à : … », which is a plausible-looking bubble. Assert
   `assertNotVisible: ".*aucun scénario.*"` after an exchange.

## The grocery journeys (MAG-178)

| | |
|---|---|
| `07-grocery-errand` | the errand: the list by shop in visit order, a tick, « Terminé » on a shop, what is left offered back, a removal |
| `08-grocery-realtime` | web → phone and phone → web, with the app never relaunched or refreshed |
| `09-grocery-deferred` | a line with a `buyAfter` in the future is in the database and not on the screen |

Three things they rely on, none of them obvious:

- **Another account.** `03` and `04` sign in as the seed's second user
  (`e2e-other@maggie.local`: two shops with visit orders, a leek with a fallback
  shop). `launchApp: arguments: { e2e_email: … }` becomes an intent extra that
  `E2eSignIn.kt` reads; without it the flavor signs in as the build's default
  account. They leave the list as they found it, so each run starts from the seed.
- **The « browser » is a script.** Maestro drives one device and cannot open a
  tab. `scripts/grocery-api.js` sends the requests the admin sends, as the same
  user, so the server publishes to Mercure exactly as it does for a tab. It proves
  the phone applies what the web publishes; the admin's own rendering of a change
  made on the phone stays with `e2e/web/tests/grocery-errand.spec.ts`. The script
  gets the stack's URL and login token from `run.sh` (`-e E2E_BASE_URL=…`).
- **A stack of its own.** `03` reads the neighbour's seeded order (the leek in
  Halles), which `task e2e:web` moves on the same stack: run `task e2e:seed`
  (done by `task e2e:mobile`) between the two. CI gives each its own stack.
- **Row selectors are wrapped in `.*`.** A list row is one merged node (text plus
  quantity), so its label alone is not the node's whole text. Headers, buttons and
  dialog texts are plain nodes and stay bare.
- **The database is asserted, not only the screen.** `expect` in the same script
  polls `GET /api/grocery_lists` — never reads it once: the collection is served
  from Elasticsearch, which trails a write.

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
- **No sound card in CI.** The emulator runs with `-noaudio`, so a real recording
  fails. The `e2e` flavor therefore records placeholder bytes
  (`src/e2e/.../AudioRecorderProvider.kt`, MAG-221) and WireMock's Whisper answers
  one fixed sentence: `02-voice-overlay.yaml` ends by holding `voice_mic`
  (`longPressOn`) and expects that sentence, cleaned, and the scripted answer.

- **Proving an absence is harder than it looks — ask the server, not the screen**
  (MAG-205). « No TTS started when the overlay opens » cannot be asserted on screen:
  `VoiceManager.speak()` sets `SPEAKING` then posts to the TTS endpoint, which under
  `TTS_PROVIDER=fake` answers two silent 26 ms frames instantly, so on a regressed
  build the label is up for a few hundred milliseconds; Maestro's `assertNotVisible`
  *waits for* a node to disappear, so it passes as soon as the state flips; and
  `waitForAnimationToEnd` is no timer, since CI sets `disable-animations: true`.
  So `02-voice-overlay.yaml` reads a durable record instead: the agent's fake
  provider counts the syntheses it is asked for, and `GET /agent/e2e/tts/syntheses`
  (`agent/app/e2e.py`, `X-E2E-Token`, `DELETE` to zero it) reports the number. The
  flow zeroes it, opens the overlay, waits for the old answer and a 5 s quiet
  window (`extendedWaitUntil` on a text that never shows, `optional`), then fails if
  the count is not 0. The route is mounted only under `TTS_PROVIDER=fake`
  (`agent/tests/test_e2e_surface.py` says so). `run.sh` hands the flow
  `E2E_BASE_URL` and `E2E_LOGIN_TOKEN` with `-e`; a flow that needs another
  server-side record follows the same pattern. Re-introduce the regression locally
  (drop the `sawLoadingSinceOpen` guard in `ChatSheet.kt`) to see the flow go red.
- **An absence also needs something to have been there.** "It did not re-speak"
  proves nothing if the history never loaded. Assert the old answer is on screen
  first; a vacuous pass is worse than a failure.
- **The keyboard does not clip node bounds.** The IME is a separate window, so a
  control sitting underneath it still reports on-screen bounds and `assertVisible`
  passes. Assert by *using* the control — tap it and check the effect — and check
  an effect the broken build cannot produce: the text field still holds what was
  typed when a send does not happen, so « the sentence is on screen » proves
  nothing.
- **The calendar journeys depend on the weekday.** « Train de nuit pour Vienne »
  is seeded five days out, so its two days share a week from Monday to Wednesday
  and straddle a Sunday the rest of the time. `03-calendar-multi-day.yaml` follows
  the week with `calendar_next` only when a day is missing, and both cases are
  the point: the straddling one is the `dfad086` regression. A flow that only ever
  ran on a Tuesday would never see it.
- **`04-calendar-import.yaml` consumes the seeded Google calendar.** The import
  dialog lists only calendars not yet connected, so the flow needs a freshly
  seeded stack — `task e2e:mobile` reseeds first. Running `maestro` by hand on a
  used stack opens the dialog empty.
- **A tagged node carries its text only if it is one semantics node.** The bar and
  the block are `clickable`, which merges the `Text` into them, so
  `id:` and `text:` together select one node. A tag on a non-clickable wrapper
  would leave the text on a child and the pair would match nothing.
- **`maestro check-syntax` never looks at `config.yaml`.** Maestro checks for a
  device before reading the workspace config, so a typo there only surfaces on
  the emulator. Keep that file small.
- **The flavor installs as `com.maggie.app.e2e`.** It sits beside the dev and
  prod builds on the owner's phone rather than replacing either. `task
  e2e:mobile` on a real phone works and is the fastest way to debug a flow — the
  phone just has to reach the host over USB, which `adb reverse` already gives.

## In CI

`.github/workflows/ci.yml`, jobs `E2E Mobile journeys (<device>)` behind the
required `E2E Mobile (phone)` (always reported, green when skipped), on every
pull request touching the app, the flows or the stack: a stack, KVM enabled, `reactivecircus/android-
emulator-runner` on API 34 `google_apis`, and `task e2e:mobile` as its script. A
failed run uploads `report/` — the screenshots are the only way to see what a
headless emulator had on screen. `device-last-frame.png` and `logcat.txt` are taken
after the failure, so a step that fails on a dialog or another app is readable.

`run.sh` sets `hide_error_dialogs` for the run and restores it on exit: a slow
emulator makes the Pixel Launcher (also the taskbar on tablets and foldables) hit an
ANR, and its dialog covers the app, so a flow fails on a screen it never reached.

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

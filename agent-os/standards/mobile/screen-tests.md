# Mobile Screen Tests — Compose on the JVM, or Maestro on a device

MAG-242. The rule in one line: **a verification goes on the JVM unless it needs a
real Android.** The emulator is the slowest and flakiest thing in the pipeline —
one reconnection per flow, software rendering, minutes per pull request — and most
of what the mobile journeys asserted was never about Android at all. It was about
what a screen makes of what the server sent.

Two homes, one question to tell them apart.

## The question

> Would this fail on a device for a reason a JVM cannot reproduce?

If not, it is a **screen test** (`mobile/app/src/test/…/…ScreenTest.kt`, Robolectric
plus `createComposeRule`, seconds, in `Mobile Unit Tests`).

If yes, it is a **journey** (`e2e/mobile/flows/`, Maestro on an emulator against the
e2e stack, minutes, in `E2E Mobile (phone)`).

## Screen test — the JVM

Everything a screen does with state it was handed:

- what a screen draws from a given server answer, and **in what order** (grouping,
  sort, a header above its rows);
- what it does **not** draw: a filtered line, an option a ticket removed;
- the loading, empty and error states;
- navigation inside the app: a tap asks for a route, a back arrow leaves, a day
  header opens the day;
- a selection mode, a confirmation dialog, a sheet opening and closing;
- an optimistic update, and the request it made — fake the repository so the test
  reads both halves.

## Journey — a real device

Only what a JVM cannot be:

| | |
|---|---|
| **The socle** | the flavor, `adb reverse`, the test login, the JWT in the DataStore, the Ktor bearer, Traefik, the AG-UI stream, the real MCP tool loop |
| **Permissions and system windows** | a runtime dialog, Credential Manager, the assistant role dialog |
| **Microphone and voice** | a real recorder, Whisper, a synthesis that must *not* happen |
| **Deep links** | a `VIEW` intent at a `singleTop` activity, `onNewIntent` |
| **Real time end to end** | a change published elsewhere reaching the screen, with nothing relaunched or refreshed |
| **Notifications** | FCM, a channel, a tap on a notification |
| **Layout against the platform** | a control under the keyboard, an app restart, a foldable or a tablet |

A rule of thumb for the last one: the IME, a system dialog and another app are
separate windows, and a JVM screen test has no window manager. « Is this control
reachable by a finger » is always a device question.

## Writing a screen test

```kotlin
@RunWith(AndroidJUnit4::class)
class SomeScreenTest {
    @get:Rule val compose = ScreenRule()

    @Test
    fun `the list is grouped by shop in visiting order`() {
        val fake = FakeGrocery()
        compose.setContent { GroceryScreen(viewModel = fake.viewModel) }
        // assert
    }
}
```

- **`ScreenRule()`, not `createComposeRule()`.** The rule hosts the content in a
  `ComponentActivity`, which only `ui-test-manifest` declares — for instrumentation.
  A unit test reads the *app's* packaged manifest, so `ScreenRule` declares the
  activity to Robolectric's package manager first. `mobile/app/src/test/…/screentest/ScreenRule.kt`.
- **`@RunWith(AndroidJUnit4::class)`** is what puts Robolectric under the test.
- **The API level, the screen size and the `Application` are in
  `src/test/resources/robolectric.properties`,** not in `@Config` per class: the
  whole suite should see one Android. A bare `android.app.Application` on purpose —
  `MaggieApp.onCreate` starts Koin, which then throws on the second test of a class.
- **Hand the screen its ViewModel.** A screen reaching for `koinViewModel()` cannot
  be tested here, and should not be: that test would assert the DI graph.
- **The real ViewModel over faked repositories,** as `screentest/Fakes.kt` builds
  them. What the journeys proved was « the list the server sent comes out grouped,
  in this order, with that line hidden » — the state machine as much as the drawing,
  and mocking the state out leaves a test asserting its own fixture.
- **Fixture names come from `api/fixtures/e2e/`.** A test that replaced a flow should
  fail for the same reason the flow did.
- **Fix the dates.** A flow read a server seeded « today + 5 days » and could only
  see one of its cases per run; a screen test writes every case down and runs them
  all, every time.

### Two traps, both measured

- **Touch injection does not reach a `ModalBottomSheet` or a `ModalDrawerSheet`.**
  `performClick()` is swallowed and the test reads as « the button did nothing » —
  a regression's own signature on a build where nothing is wrong. Inside those two,
  use `tap()` (`screentest/ScreenInteractions.kt`), which invokes the node's click
  action. Everywhere else, `performClick()`: it is the better test.
- **An indefinite progress indicator animates forever**, and the test clock
  auto-advances. Assert the state around a spinner — the empty text, the error
  message, the content once it arrived — not the spinner itself.

### Asserting an order

Compose has no `below:` selector. `assertTopToBottom(…)` reads the nodes'
`positionInRoot` and names the order it found, which is what Maestro's `below:` was
for: a screen listing the right lines under the wrong header passes a bare « is it
visible ».

## Moving a verification out of Maestro

1. Write the screen test first and watch it fail for the right reason.
2. Then delete the steps from the flow — and the flow itself when nothing is left.
3. Say in the flow, or in `e2e/mobile/README.md`, where the assertion went. A flow
   deleted without a forwarding address is a flow nobody can tell was replaced.
4. Rebalance `e2e/mobile/shards.txt` from the `junit.xml` durations of the run.

The reverse also holds: a bug the owner finds in production that a screen test
could have caught gets a screen test, not a journey.

## What is still nobody's test

A screen test and a journey together do not cover a system dialog the harness
cannot accept — making Maggie the assistant, granting a permission by hand. Those
stay in the Recette comment, for the owner on his own phone.

---

See also: [mobile/testing](testing.md) for ViewModel tests,
[global/testing](../global/testing.md) for the definition of done,
[global/e2e-environment](../global/e2e-environment.md) for the stack,
`e2e/mobile/README.md` for the journeys themselves.

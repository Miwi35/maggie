---
name: mobile-testing
description: "JUnit + MockK testing patterns for the Android app. Use when writing or reviewing tests in mobile/ including ViewModel tests, coroutine dispatchers, and MockK mocking."
user-invocable: false
---

# Mobile Testing (JUnit + MockK)

## Required Coverage — nothing ships without it

**Every ViewModel touched** gets one test per state transition, asserting `uiState`:

- loading → success: the data lands in the state
- loading → error: the message is exposed, `isLoading` back to false
- each user action the screen offers (send, check, refresh, select)

Repository and API service changes get a deserialization test on a real recorded
response — a renamed API field must fail here, not in production.

**Bug fix → red first.** Write the failing test, run it, then fix. Both in the same PR.

**The feature also needs an e2e journey** (Maestro flow, `e2e/mobile/`) — see
`agent-os/standards/global/testing.md` (Definition of Done).

## File Location

Mirror main source package structure. Class: `{Subject}Test`. Methods: backtick descriptive names.

## Pattern

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `action produces expected state`() = runTest {
        advanceUntilIdle()
        assertEquals(expected, viewModel.uiState.value.field)
    }
}
```

## Mocking

- `mockk()` for interfaces/classes
- `coEvery` for suspend functions, `every` for regular
- Verify state over verifying mock calls

## Run Command

```
cd mobile && JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew testProdReleaseUnitTest
```

Lint runs in CI only — local `lintProdRelease` crashes on a known AGP/K2 bug.

## Reference

For full details, read `agent-os/standards/mobile/android-app.md` (testing section)

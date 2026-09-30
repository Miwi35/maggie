---
name: mobile-testing
description: "JUnit + MockK testing patterns for the Android app. Use when writing or reviewing tests in mobile/ including ViewModel tests, coroutine dispatchers, and MockK mocking."
user-invocable: false
---

# Mobile Testing (JUnit + MockK)

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
cd mobile && JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew lintProdRelease testProdReleaseUnitTest
```

## Reference

For full details, read `agent-os/standards/mobile/android-app.md` (testing section)

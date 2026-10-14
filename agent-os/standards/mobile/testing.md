# Mobile Testing (JUnit + MockK)

Every ViewModel touched owes one test per state transition, asserting `uiState`:

- loading → success: the data lands in the state
- loading → error: the message is exposed, `isLoading` back to false
- each user action the screen offers (send, check, refresh, select)

A repository or API service change gets a deserialization test on a real recorded response (`DtoContractTest` checks every declared DTO field against the recorded responses of `api/contract/`) — a renamed API field must fail here, not in production.

See the Definition of Done in [global/testing](../global/testing.md) for the rest (red-first bug fixes, the mandatory Maestro flow in `e2e/mobile/`).

## File Location & Naming

```
mobile/app/src/test/java/com/maggie/app/
  ui/screens/chat/ChatViewModelTest.kt
  ui/screens/agenda/AgendaViewModelTest.kt
  data/api/MaggieApiServiceTest.kt
```

- Mirror main source package structure
- Class: `{Subject}Test`
- Methods: backtick descriptive names

## Coroutine Testing

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `sendMessage success updates state`() = runTest {
        // arrange, act
        advanceUntilIdle()
        // assert viewModel.uiState.value
    }
}
```

## Mocking

```kotlin
private val repository = mockk<ChatRepository>()

// Suspend functions
coEvery { repository.sendMessage(any()) } returns Result.success(response)

// Regular functions
every { service.getData() } returns listOf(...)
```

- `mockk()` for interfaces and classes
- `coEvery` for suspend functions, `every` for regular
- Verify state over verifying mock calls

## Assertions

```kotlin
assertEquals(expected, viewModel.uiState.value.messages.size)
assertTrue(viewModel.uiState.value.isLoading)
assertNull(viewModel.uiState.value.error)
```

## Run command

```
task wt:test:mobile -- --tests 'com.maggie.app.ui.screens.chat.*'
```

The JVM unit tests (`testProdDebugUnitTest`) of the worktree's code, one Gradle build at a time on the machine. Locally, mobile means this command only: never `./gradlew` by hand, never `assemble*` or `lint*`, never Maestro or an emulator (except `task e2e:mobile` to write or debug a journey). CI builds the APK, lints and runs the journeys; its `Mobile Unit Tests (release)` job runs the shipped variant (`prodRelease`). See [android-app](android-app.md) and [global/worktree-checks](../global/worktree-checks.md).

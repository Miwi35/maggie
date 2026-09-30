# Mobile Testing (JUnit + MockK)

Every ViewModel touched owes one test per state transition — loading, success, error. See
the Definition of Done in [global/testing](../global/testing.md) for the rest, including
the mandatory Maestro flow.

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

# Standards pour Agent Hub : skills, sous-agents, validations

Les standards suivants s'appliquent à ce travail.

---

## agent/architecture

# Agent Hub Architecture

Python FastAPI service. Separate from Symfony — consumes the API via MCP like any external client.

## MCP Client
- HTTP transport (not stdio) — connects to `http://nginx/_mcp`
- Stateful: captures `Mcp-Session-Id` from server
- Singleton `mcp_client` initialized at startup
- Tools cached after first `tools/list` call

## LLM Gateway
- Anthropic Claude via `anthropic` SDK
- Agentic tool loop: max 5 iterations per request
- MCP tool schemas converted to Anthropic format
- Conversation history kept in memory (per user_id)

## Personality
- Default loaded from YAML (`app/personality/default.yaml`)
- Template placeholders: `{name}`, `{language}`, `{tone}`
- Will move to database for user-customizable personalities

## Dependencies
- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config

---

## agent/testing

# Agent Testing (pytest)

## File Location & Naming

```
agent/tests/
  conftest.py          # shared fixtures
  test_conversation_memory.py
  test_mcp_client.py
  test_routes.py
```

- File: `test_{module}.py`
- Class: `Test{Feature}` (no inheritance)
- Method: `test_{action}_{expected_result}`

## Async

`asyncio_mode = "auto"` in pyproject.toml — no `@pytest.mark.asyncio` needed.

## Mocking

```python
# HTTP calls — use respx
with respx.mock:
    respx.post(url).mock(return_value=httpx.Response(200, json={...}))
    result = await client.call()

# Internal deps — use unittest.mock
with patch('app.module.ClassName') as mock:
    mock.return_value.method = AsyncMock(return_value=data)
```

- `respx` for httpx HTTP mocking
- `AsyncMock` for coroutine mocks
- `tmp_path` fixture for temp file tests (YAML configs)

## Fixtures

Shared fixtures in `conftest.py`:

```python
@pytest.fixture
def client():
    from app.main import app
    return TestClient(app)
```

## Assertions

Plain `assert` — no unittest-style methods.

```python
assert response.status_code == 200
assert len(messages) == 3
assert result["key"] == "value"
```

---

## admin/react-admin

# React Admin

Uses `@api-platform/admin` (HydraAdmin) with Vite.

## Patterns
- `ResourceGuesser` for standard CRUD screens
- Custom components for complex views (agenda, chat)
- Mercure SSE via native `EventSource` API, not a library
- Vite `base: '/admin/'` for Traefik path routing
- `allowedHosts: ['maggie.local']` in Vite config

## Mercure subscription
```tsx
const url = new URL(MERCURE_URL)
url.searchParams.append('topic', '/api/events/{id}')
const eventSource = new EventSource(url.toString())
```

- Use `VITE_MERCURE_PUBLIC_URL` env var, fallback to `http://maggie.local/.well-known/mercure`

---

## admin/testing

# Admin Testing (Vitest)

## File Location & Naming

Co-located with source:

```
admin/src/
  App.test.tsx
  components/chat/ChatWidget.test.tsx
  modules/calendar/CalendarView.test.tsx
```

- File: `{Component}.test.tsx` next to `{Component}.tsx`
- Setup: `src/test/setup.ts` (jest-dom matchers, jsdom polyfills)

## Structure

```tsx
describe('ComponentName', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  test('renders initial state', () => { ... })
  test('handles user interaction', async () => { ... })
})
```

## Mocking

```tsx
// External modules
vi.mock('@api-platform/admin', () => ({
  HydraAdmin: ({ children }: Props) => <div>{children}</div>,
}))

// Browser APIs missing in jsdom
vi.stubGlobal('EventSource', MockEventSource)
vi.stubGlobal('fetch', vi.fn().mockResolvedValue(...))

// jsdom polyfills (in setup.ts)
Element.prototype.scrollIntoView = () => {}
```

## Testing Library

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const user = userEvent.setup()
await user.click(screen.getByRole('button'))
await waitFor(() => expect(screen.getByText('Done')).toBeInTheDocument())
```

- Query by role/text first, testid as last resort
- `userEvent` over `fireEvent` for realistic interactions

---

## mobile/android-app

# Android Mobile App

Kotlin + Jetpack Compose + Material 3.

## Stack choices
- **Koin** for DI (lightweight, less boilerplate than Hilt)
- **Ktor** for HTTP client (built-in SSE support for Mercure)
- **Navigation Compose** for routing
- BuildConfig fields for API_BASE_URL and MERCURE_URL

## Pattern
- Repository pattern: `ApiService` → `Repository` → `ViewModel`
- Hydra JSON-LD parsed via `HydraCollection<T>` wrapper

## Build Environment
- **JAVA_HOME:** `/opt/android-studio-for-platform/jbr` (Java 21) — host Java is 11, always use Android Studio JBR
- `gradle.properties` has `org.gradle.java.home` set to Android Studio JBR
- **adb:** `~/Android/Sdk/platform-tools/adb`

## Signing & Google Credentials
- **Release keystore:** `mobile/release.keystore` (alias `maggie`, config in `keystore.properties`)
- **Release SHA1:** `C5:AD:BF:94:9F:08:68:09:44:78:F1:F0:8B:A2:41:CA:B1:78:4C:DA` — registered in Google Cloud Console
- Google OAuth credentials only work with the release signing key
- **Always build `prodRelease`** for device testing: `JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew installProdRelease`
- Do NOT use `devDebug` or `prodDebug` — the debug keystore SHA1 is not registered in GCP

---

## mobile/testing

# Mobile Testing (JUnit + MockK)

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

---

## global/real-time

# Real-Time: Mercure

All real-time updates use **Mercure** (SSE), not WebSockets.

## Why
- Native API Platform integration: `#[ApiResource(mercure: true)]` auto-publishes
- SSE simpler than WebSocket lifecycle (no heartbeat, reconnect built-in)

## URLs
- Internal (server-to-server): `http://mercure/.well-known/mercure`
- Public (client-facing): `http://maggie.local/.well-known/mercure` (via Traefik)

## Topic conventions
- Entity updates: `/api/{resource}/{id}` (auto from API Platform)
- Agent chat: `/agent/chat/{user_id}`

## Client pattern
Use native `EventSource` API. No library needed.

---

## global/testing

# Testing Strategy

One behavior per test. Mock all external services. Only integration tests use real DB.

## Test Pyramid

| Layer | What | Runs in CI |
|---|---|---|
| Unit | Pure logic, mocked deps | Always |
| Integration | Real DB, real DI container | With service containers |
| API/HTTP | Full request cycle | With service containers |
| E2E | Browser (Playwright) | Deferred to Phase 5 |

## Per-Component Quick Reference

| Component | Framework | Run command | Config |
|---|---|---|---|
| API | PHPUnit 12 | `task api:test` | `api/phpunit.dist.xml` |
| Agent | pytest 9 | `task agent:test` | `agent/pyproject.toml` |
| Admin | Vitest 3 | `task admin:test` | `admin/vite.config.ts` |
| Mobile | JUnit 4 + MockK | `./gradlew :app:testDebugUnitTest` | `build.gradle.kts` |

## Rules

- Never run test commands on host — always via `task` or Docker
- CI runs lint before tests (lint gates test jobs)
- `MESSENGER_TRANSPORT_DSN=sync://` in test env — no RabbitMQ needed
- No test interdependencies — each test sets up and cleans its own state

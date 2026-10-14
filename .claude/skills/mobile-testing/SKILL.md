---
name: mobile-testing
description: "JUnit + MockK testing patterns for the Android app. Use when writing or reviewing tests in mobile/ including ViewModel tests, coroutine dispatchers, and MockK mocking."
user-invocable: false
---

# Mobile Testing (JUnit + MockK)

**Use when** writing or reviewing a test in `mobile/`.

**Read** `agent-os/standards/mobile/testing.md`; screens and Maestro: `agent-os/standards/mobile/screen-tests.md`.

## Gotchas

- Every ViewModel touched owes one test per state transition (loading → success, loading → error, each user action), asserting `uiState`.
- A repository or API service change owes a deserialization test on a recorded response: a renamed API field fails there, not in production.
- Run with `task wt:test:mobile -- --tests '<class or package>'` and nothing else: no `./gradlew`, no emulator, no Maestro.
- `mockk()` with `coEvery` for suspend functions; verify state, not mock calls.
- A bug fix starts with a red test.

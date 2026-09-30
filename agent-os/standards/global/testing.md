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

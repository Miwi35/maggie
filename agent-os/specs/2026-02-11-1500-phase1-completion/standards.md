# Phase 1 Completion — Standards

## Taskfile Commands
All infrastructure commands run via Taskfile. Never use host PHP/composer/node directly.
- `task build` — build Docker images
- `task up:dev` — start dev environment
- `task install` — install API + admin dependencies
- `task api:console -- <command>` — run Symfony console commands in container
- `task test:integration` — run integration tests
- `task test:all` — run all unit tests

## Real-Time (Mercure SSE)
- Hub URL: `http://maggie.local/.well-known/mercure`
- Topics follow API resource IRIs: `/api/events/{id}`, `/agent/chat/{user_id}`
- Admin uses browser `EventSource` API
- Mobile uses `ktor-client-sse` with `callbackFlow` pattern
- HTTP POST remains primary delivery for request/response; Mercure for push/proactive

## Entity Identifiers
- All entities use UUID v7 (`Symfony\Component\Uid\Uuid`)
- IRIs are UUID-based: `/api/calendars/01952f3a-...`, not `/api/calendars/1`
- Integration tests must dynamically discover IRIs from list endpoints

## Mobile Architecture
- MVVM with Koin DI and Ktor HTTP client
- ViewModels accept repository + service dependencies via constructor injection
- SSE flows use `callbackFlow` with structured cancellation
- Tests mock services with MockK and use Turbine for Flow testing

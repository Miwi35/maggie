# Phase 1 Completion — Plan

## Task 1: Save spec documentation
Create `agent-os/specs/2026-02-11-1500-phase1-completion/` with shape.md, plan.md, standards.md.

## Task 2: Infrastructure bootstrap
1. Verify hosts entry: `task hosts:check` / `task hosts:add`
2. Build + start: `task build && task up:dev`
3. Install deps: `task install`
4. Generate migrations: `task api:console -- make:migration`
5. Run migrations + fixtures: `task api:console -- doctrine:migrations:migrate --no-interaction`
6. Smoke test API, Agent, Mercure endpoints

## Task 3: Add Mercure SSE to Android app
1. Add `ktor-client-sse` to version catalog + build.gradle.kts
2. Create `MercureService` using Ktor SSE plugin with `callbackFlow`
3. Wire into Koin module
4. Update `AgendaViewModel` — subscribe to event topic, reload on change
5. Update `ChatViewModel` — subscribe to chat topic, add proactive messages only when not loading
6. Update existing tests with mocked MercureService
7. Add `MercureServiceTest`

## Task 4: Fix admin ChatWidget duplicate messages
Remove assistant message addition from Mercure `onmessage` handler (line 28). Keep HTTP POST as primary delivery. Mercure stays connected for Phase 2 proactive messages.

## Task 5: Fix integration test calendar IRI
Replace hardcoded `/api/calendars/1` with dynamic lookup: fetch `/api/calendars`, extract first `@id`, use in POST payload.

## Task 6: End-to-end verification
1. `task test:integration` — all 9 categories pass
2. `task test:all` — unit tests pass
3. Manual verification: all containers running, endpoints responding

## Ordering
- Tasks 1, 3, 4, 5: code-only, can run in parallel
- Task 2: infrastructure bootstrap
- Task 6: requires all above complete

## Files

| Action | File |
|--------|------|
| Create | `agent-os/specs/2026-02-11-1500-phase1-completion/shape.md` |
| Create | `agent-os/specs/2026-02-11-1500-phase1-completion/plan.md` |
| Create | `agent-os/specs/2026-02-11-1500-phase1-completion/standards.md` |
| Modify | `mobile/gradle/libs.versions.toml` |
| Modify | `mobile/app/build.gradle.kts` |
| Create | `mobile/app/src/main/java/com/maggie/app/data/mercure/MercureService.kt` |
| Modify | `mobile/app/src/main/java/com/maggie/app/MaggieApp.kt` |
| Modify | `mobile/app/src/main/java/com/maggie/app/ui/screens/agenda/AgendaViewModel.kt` |
| Modify | `mobile/app/src/main/java/com/maggie/app/ui/screens/chat/ChatViewModel.kt` |
| Modify | `mobile/app/src/test/.../AgendaViewModelTest.kt` |
| Modify | `mobile/app/src/test/.../ChatViewModelTest.kt` |
| Create | `mobile/app/src/test/.../data/mercure/MercureServiceTest.kt` |
| Modify | `admin/src/components/chat/ChatWidget.tsx` |
| Modify | `scripts/test-integration.sh` |
| Generate | `api/migrations/VersionXXXX.php` (Doctrine auto-generated) |

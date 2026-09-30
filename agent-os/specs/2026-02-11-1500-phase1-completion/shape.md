# Phase 1 Completion — Shaping Notes

## Problem

Phase 1 scaffolding and feature code is nearly complete across all components (API, Agent, Admin, Mobile, Docker). What remains is infrastructure bootstrap, a few targeted bug fixes, and the mobile Mercure/SSE integration.

## Scope

**In scope:**
- Infrastructure bootstrap: build containers, install deps, generate + run migrations, load fixtures
- Mobile Mercure/SSE: Android app is the only component missing real-time updates
- Fix admin ChatWidget duplicate assistant messages (HTTP response + Mercure SSE both add)
- Fix integration test calendar IRI (`/api/calendars/1` vs UUID v7)
- End-to-end verification of the full stack

**Out of scope:**
- New features beyond Phase 1 MVP
- Auth system
- Additional modules (Meals, Budget, Fitness)
- Recurrence UI

## Key Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Mobile SSE library | `ktor-client-sse` 3.0.2 | Stays in Ktor ecosystem, no extra OkHttp artifact, version catalog already comments "HTTP + SSE" |
| Chat duplicate fix | Remove Mercure message add, keep HTTP POST as primary | HTTP is reliable + synchronous; Mercure stays connected for Phase 2 proactive messages |
| Calendar IRI fix | Dynamic lookup via `/api/calendars` before event creation | UUIDs are random; can't hardcode |
| Migration generation | Doctrine `make:migration` in container | Entities already exist, just need DDL |

## Rabbit Holes to Avoid

- Don't optimize the SSE reconnection strategy yet — basic Flow cancellation is sufficient
- Don't add auth to Mercure subscriptions in Phase 1 — single-user system
- Don't refactor ChatWidget beyond the duplicate fix

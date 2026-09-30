# Phase 1 MVP — Shaping Notes

## Scope

Phase 1 establishes the foundation for Maggie v3: Docker infrastructure, Symfony API with Agenda module, MCP Server for agent tool access, Python Agent Hub with LLM gateway, web admin (API Platform Admin), Android app, and Mercure real-time integration.

**In scope:**
- Full Docker orchestration (7 services)
- Symfony 7 API with API Platform, single business module (Agenda)
- MCP Server 1 via `symfony/mcp-bundle` with agenda tools
- Python FastAPI Agent Hub with Claude LLM integration
- API Platform Admin web app with chat widget
- Android app (Kotlin/Compose) with agenda + chat screens
- Mercure real-time wiring across all components
- End-to-end integration test script

**Out of scope (Phase 2+):**
- Meals, Budget, Fitness modules
- MCP Server 2 (agent self-management)
- Mem0 memory system
- Confidence/autonomy system
- Behavioral rules learning
- Mode Plan / Mode Silent
- Specialist agents

## Key Decisions

| Decision | Choice | Rationale |
|---|---|---|
| MCP Server 1 | `symfony/mcp-bundle` v0.3 inside `api/` | Official bundle, `#[McpTool]` attributes, HTTP transport at `/_mcp`, direct service injection |
| Agent Hub | FastAPI (Python 3.12) | Async-native for concurrent LLM + SSE, Pydantic validation, lightweight |
| MCP transport | Streamable HTTP over Docker network | Agent Hub calls `http://nginx/_mcp` using Python `mcp` SDK |
| Android UI | Kotlin + Jetpack Compose + Material 3 | Modern, officially recommended, declarative |
| Web admin | API Platform Admin (HydraAdmin) | Auto-discovers API resources, minimal config |
| Routing | Single domain, path-based via nginx | `/api`, `/admin`, `/_mcp`, `/agent` |
| Event model | Google Calendar-inspired | Proven model: RRULE for recurrence, JSON for reminders, exception instances via self-reference |
| Recurrence | `simshaun/recurr` PHP lib + RRULE string | No separate entity — occurrences expanded at query time |

## Rabbit Holes to Avoid

- Don't build full recurrence UI — just the RRULE storage and expansion service
- Don't implement auth in Phase 1 — single-user system, add later
- Don't over-engineer the Agent Hub — stub the personality/memory, focus on LLM ↔ MCP round-trip
- Don't build a full calendar UI — placeholder component, FullCalendar integration is Phase 2
- Don't optimize Docker builds — get it working first

## Reference Project Patterns

The PsychedCMS project (`~/perso/psyched-cms/`) provides proven patterns for:
- Multi-stage PHP Dockerfile (base → dev → prod)
- Nginx reverse proxy with path-based routing
- Docker Compose service orchestration
- Taskfile.yml task automation
- API Platform + Doctrine + Mercure configuration
- React admin with Vite

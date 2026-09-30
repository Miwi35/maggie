# Phase 1 MVP — Project Scaffolding & Component Stubs

## Context

Maggie v3 is a greenfield personal AI agent platform. Phase 1 establishes the foundation: Docker infrastructure, Symfony API with Agenda module, MCP Server for agent tool access, Python Agent Hub with LLM gateway, web admin (API Platform Admin), Android app, and Mercure real-time integration. The goal is to get all components scaffolded and communicating — stubs, not full implementations.

**Spec folder:** `agent-os/specs/2026-02-11-phase1-mvp-scaffolding/`

---

## Key Architectural Decisions

| Decision | Choice | Rationale |
|---|---|---|
| MCP Server 1 | `symfony/mcp-bundle` v0.3 inside `api/` | Official bundle, `#[McpTool]` attributes, HTTP transport at `/_mcp`, direct service injection |
| Agent Hub | FastAPI (Python 3.12) | Async-native for concurrent LLM + SSE, Pydantic validation, lightweight |
| MCP transport | Streamable HTTP over Docker network | Agent Hub calls `http://nginx/_mcp` using Python `mcp` SDK |
| Android UI | Kotlin + Jetpack Compose + Material 3 | Modern, officially recommended, declarative |
| Web admin | API Platform Admin (HydraAdmin) | Auto-discovers API resources, minimal config |
| Routing | Single domain, path-based via nginx | Follows PsychedCMS pattern: `/api`, `/admin`, `/_mcp`, `/agent` |

## Project Structure

```
maggie-v3/
├── api/                    # Symfony 7 + API Platform + MCP Server 1
├── admin/                  # API Platform Admin (React)
├── agent/                  # Python FastAPI Agent Hub
├── mobile/                 # Android app (Kotlin/Compose)
├── .docker/
│   ├── php/               # PHP-FPM multi-stage Dockerfile
│   ├── nginx/             # Nginx reverse proxy config
│   ├── node/              # Node dev server Dockerfile
│   └── python/            # Python FastAPI Dockerfile
├── docker-compose.yml
├── Taskfile.yml
├── .env
├── agent-os/              # (existing) specs, standards, product docs
└── requirements.md        # (existing) functional spec
```

---

## Tasks

### Task 1: Save spec documentation ✅

### Task 2: Docker infrastructure & project scaffolding

**Creates:** Root-level project structure, Docker configs, environment, task automation.

### Task 3: Symfony API stub

**Creates:** Symfony 7 project with API Platform, Doctrine, Mercure, Messenger, MCP Bundle.

### Task 4: Agenda module

**Creates:** Business entities, repositories, services, migrations, fixtures.

### Task 5: MCP Server 1 — Agenda tools

**Creates:** MCP tool classes using `symfony/mcp-bundle` `#[McpTool]` attributes.

### Task 6: Python Agent Hub stub

**Creates:** FastAPI project with LLM gateway, personality engine, MCP client, Mercure pub/sub.

### Task 7: Web admin stub

**Creates:** API Platform Admin React app with agenda view and chat widget.

### Task 8: Android app stub

**Creates:** Basic Kotlin/Compose Android project with agenda screen and chat screen.

### Task 9: Mercure integration wiring

**Wires** real-time events across all components.

### Task 10: End-to-end integration verification

**Creates** `scripts/test-integration.sh` that verifies the full round-trip.

---

## Verification

After all tasks complete:
1. `task setup` starts all containers without errors
2. `curl http://maggie.local/api` returns JSON-LD API entrypoint
3. `curl http://maggie.local/admin` loads React Admin
4. `curl http://maggie.local/agent/health` returns `{"status":"ok"}`
5. Creating an event via API publishes a Mercure event
6. Sending a chat message returns an LLM-generated response that can use agenda tools
7. `scripts/test-integration.sh` passes all checks

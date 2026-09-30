# Tech Stack

## Frontend

- **Web App:** API Platform Admin (leverages API Platform's built-in admin capabilities)
- **Mobile App:** Native Android app (primary mobile target)
- **Agent SDK:** TypeScript library for chat widget, UI command handling, and Mercure integration

## Backend

- **API:** Symfony 7 + API Platform (business logic, data persistence, Mercure event publishing)
- **MCP Server 1 (Business):** PHP (Symfony bundle or sidecar) — exposes business API as MCP tools
- **Async Processing:** Symfony Messenger for async jobs

## Agent Hub

- **Language:** Python
- **Role:** Orchestrator, memory management, scheduler, LLM calls, channel routing
- **MCP Server 2 (Self-Management):** Python — exposes scheduling, memory, and behavioral rules as MCP tools
- **Async Processing:** Python task queue

## Database

- **Primary:** PostgreSQL (business data + behavioral rules)
- **Memory:** Mem0 (vector + graph for factual/episodic memory) + PostgreSQL (behavioral rules)

## AI / LLM

- **Primary LLM:** Claude API (Anthropic) — reasoning, tool use, response generation
- **Filter Model:** Claude Haiku or Ollama (local) — cost-effective event filtering
- **Protocol:** MCP (Model Context Protocol) — universal agent-to-API interface

## Real-Time

- **Mercure** (Caddy-based) — SSE-based real-time event distribution between all components

## Infrastructure

- **Containerization:** Docker Compose (each component in its own container)
- **Structure:** Monorepo for Symfony API + MCP Server 1; separate service for Agent Hub + MCP Server 2
- **Auth:** OAuth2 / JWT for API, MCP, and Mercure authentication

---
name: agent-architecture
description: "Python Agent Hub architecture. Use when creating or modifying Python code in agent/ including FastAPI routes, MCP client, LLM gateway, personality config, and pydantic-settings."
user-invocable: false
---

# Agent Hub Architecture

**Use when** creating or changing Python code in `agent/` (routes, MCP client, LLM gateway, tool loop, skills, sub-agents, policy, memory).

**Read** `agent-os/standards/agent/architecture.md`; memory folder: `agent-os/standards/agent/memory.md`.

## Gotchas

- Every tool call goes through `ToolRouter.call_tool`: it is the single place where the policy applies. Never bypass it, never rely on the prompt to stop a destructive call.
- Skills are loaded on demand with `get_skill`; do not inject their bodies into the prompt.
- Per-user data is filtered by `user_id` and ships a two-user isolation test.
- No Alembic: a new table goes through `create_all`, a new column is idempotent SQL in `run_migrations()`.
- Packages with `uv`, never pip or poetry; nothing runs on the host (`task agent:*`).

---
name: agent-architecture
description: "Python Agent Hub architecture. Use when creating or modifying Python code in agent/ including FastAPI routes, MCP client, LLM gateway, personality config, and pydantic-settings."
user-invocable: false
---

# Agent Hub Architecture (Python FastAPI)

Separate from Symfony — consumes the API via MCP like any external client.

## Directory Structure

```
agent/app/
├── main.py           # FastAPI entry (root_path: /agent)
├── config.py         # pydantic-settings configuration
├── api/              # Maggie API client
├── llm/              # Claude LLM orchestration
├── mcp/              # MCP client integration
├── memory/           # Agent memory/context
├── mercure/          # Real-time event publishing
└── personality/      # Agent personality config (YAML)
```

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

- Default from YAML (`app/personality/default.yaml`)
- Template placeholders: `{name}`, `{language}`, `{tone}`
- Will move to database for user-customizable personalities

## Dependencies

- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config
- `ruff` for linting and formatting

## Reference

For full details, read `agent-os/standards/agent/architecture.md`

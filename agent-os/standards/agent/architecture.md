# Agent Hub Architecture

Python FastAPI service. Separate from Symfony — consumes the API via MCP like any external client.

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
- Default loaded from YAML (`app/personality/default.yaml`)
- Template placeholders: `{name}`, `{language}`, `{tone}`
- Will move to database for user-customizable personalities

## Dependencies
- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config

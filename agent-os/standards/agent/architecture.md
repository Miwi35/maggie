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

## Data ownership
- Long-term memory has its own standard: `agent/memory` (ADR-010). Once it lands, an
  S3-compatible bucket is a **mandatory** dependency of the agent (`aioboto3`, one secret): the
  memory's `.md` files are the source of truth and Postgres is a derived index.
- Memories and instructions: every repository read or write is filtered by `user_id`; an id belonging to another user behaves like an unknown id (same "not found" error, no leak). New per-user data follows the same rule and ships a two-user isolation test.
- Skills live in the `skill` table of `maggie_agent` (MAG-187), saved with the rest of the agent's data; the in-memory index (`skill_index.rebuild()`) is reloaded from it at startup. They are **global**, shared by all users: accepted while Maggie has a single user. Multi-user means adding a `user_id` to that table first (MAG-108).

## Dependencies
- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config

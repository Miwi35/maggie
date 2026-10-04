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
- Memories and instructions: every repository read or write is filtered by `user_id`; an id belonging to another user behaves like an unknown id (same "not found" error, no leak). New per-user data follows the same rule and ships a two-user isolation test.
- Skills live in the `skill` table of `maggie_agent` (MAG-187), saved with the rest of the agent's data; the in-memory index (`skill_index.rebuild()`) is reloaded from it at startup. They are **global**, shared by all users: accepted while Maggie has a single user. Multi-user means adding a `user_id` to that table first (MAG-108).

## Schema evolution (MAG-195)
- The agent has no migration tool. New tables come from `create_all` (`proaction_repo.ensure_table()`); new columns on existing tables are added by idempotent SQL in `context_repo.run_migrations()` (`ADD COLUMN IF NOT EXISTS`), replayed at every start.
- Additive only: never `DROP`, `ALTER TYPE` or rename a column without its own ticket. Alembic is for the day a table holds data nothing else can rebuild.
- The note index (`memory_note`, `memory_event`, `memory_outbox`) is derived from the bucket (`agent-os/standards/agent/memory.md`): an incompatible change is "drop + rebuild" (`task agent:memory:rebuild -- --offline` with the agent stopped, or `POST /agent/memory/rebuild`). Only `last_used_at` and `use_count` exist nowhere else, and the rebuild keeps them.
- The memory bucket is optional: `MEMORY_BUCKET` empty means no sync, no index, the old `memory` table only. `MEMORY_BUCKET=fake` is the in-memory bucket of the e2e stack.

## Dependencies
- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config

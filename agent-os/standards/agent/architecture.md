# Agent Hub Architecture

Python FastAPI service. Separate from Symfony — consumes the API via MCP like any external client.

## Layout
`agent/app/`: `main.py` (FastAPI entry, `root_path="/agent"`), `container.py` (shared instances), `config.py` (pydantic-settings), `api/` (routes), `domain/` (domain services, no FastAPI), `llm/` (gateway, tool loop, history, prompt cache), `mcp/` (MCP client), `memory/`, `mercure/` (publisher, topics), `personality/` (YAML), `policy/`, `skills/`, `agents/` (sub-agents), `a2a/`, `queue/` (proactions), `db/` (models and repositories), `tts/`.

## Assembly
- `app/container.py` is the only place of the shared instances: the Anthropic client, the `ToolRouter`, the `SystemPrompt`, `LLMGateway` and `StreamingGateway` (`container.llm_client`, `.tool_router`, `.system_prompt`, `.llm_gateway`, `.streaming_gateway`). Nothing else calls `create_llm_client()`, `LLMGateway(`, `StreamingGateway(`, `ToolRouter(`, `SystemPrompt(` or `MaggiePromptSources(` (`tests/test_container.py` checks it)
- One client for everyone, timeout `LLM_TIMEOUT_SECONDS` (600 s, connection 5 s)
- In a test: `with container.override(llm_client=…, tool_router=…, llm_gateway=…, streaming_gateway=…)`, or `Container().llm_gateway` for a fresh one; never `patch` a module attribute for a gateway or a client

## MCP Client
- HTTP transport (not stdio) — connects to `http://nginx/_mcp`
- Stateful: captures `Mcp-Session-Id` from server
- Singleton `mcp_client` initialized at startup
- Tools cached after first `tools/list` call

## LLM Gateway
- Anthropic Claude via `anthropic` SDK
- One tool loop, `app/runtime/loop.py` (`run_loop`): the only place that calls the model for a turn. It emits the turn's events (`StepStarted`, `ToolCallEnded`, `TurnEnded`…); `run_tool_loop` (`app/llm/runner.py`: chat, proactions, sub-agents, the announcement of an approved action) aggregates them into a dict, `streaming.py` translates them into AG-UI. Chat budget: `settings.chat_max_iterations` (5); `tools=None` gives a plain answer.
- Both loops stop a tool at its 3rd failure in a row (`app/llm/tool_failures.py`): the model gets a note and one last call with `tool_choice: none`, so the answer is its own. A call with invalid arguments returns `invalid_input` (the faulty fields and the tool's schema: MCP `-32602`, missing required fields for native tools). A streamed turn keeps its calls on the user's message (`turn_blocks`); a turn taken up again closes a call that never answered with `INTERRUPTED_RESULT` and does not run it again. The model reads results through `for_model` / `model_turns` (data envelope, capped); the code reads the raw result
- MCP tool schemas converted to Anthropic format
- Every tool call goes through `ToolRouter.call_tool(name, arguments, user_id, source)` — the single place where the policy applies (see Policy and approvals). `source` is one of `chat`, `chat_stream`, `proaction`, `subagent:<name>`, `a2a`, `approval`
- The system prompt is built only by `app/runtime/system_prompt.py` (`SystemPrompt.build`), the aggregate of a static base and the owner's customizations, each at its place; what it reads comes from `app/prompt_sources.py`, the one module that imports the personality, skills, memory, directives, threads, timezone, work hours and day context for it. No gateway builds a prompt of its own (`tests/test_system_prompt.py`)
- System prompt = stable prefix (personality + skills index) then a volatile block (memory, directives, open threads, date) — `app/llm/prompt_cache.py`. Cache breakpoints, four at most and 1 h entries before 5 min ones: tools (1 h), stable prefix (1 h), skills of the moment (5 min), memory profile (5 min). `web_search` goes after the shared tools, so chat, proaction and planning share the tools cache. The effort of each kind of run lives in `app/llm/models.py` (`EFFORT_BY_RUN`), next to its model
- Conversation history is persisted (`agent_message`) and rebuilt per request by `app/llm/history.py`: the current thread's messages in full, a short global window, and the other threads as summaries in the system prompt
- A streamed turn picks its thread beside what does not depend on it (tools, personality, directives, date…); history, memory recall and the open-threads section wait for it. The router's call is bounded (`CONTEXT_ROUTER_TIMEOUT_SECONDS`); past it: the thread in progress, a new thread on a change of subject, otherwise the most recent open thread. The open threads are read once per turn

## Personality
- Default loaded from YAML (`app/personality/default.yaml`), overridden per user by the `personality` row in `maggie_agent`
- Template placeholders: `{name}`, `{language}`, `{tone}`

## Skills
- A skill is a Markdown procedure the user taught Maggie: name, description, tags, body. Stored as Markdown files in `Compétences/` of the bucket, outside every user's folder (MAG-218, `memory.md`); the `skill` table is the index derived from them and `skill_index` keeps the entries in memory
- **On demand, never injected.** The system prompt carries only the index — `get_skills_index()` returns `- name: description` for every skill, sorted by name so the prefix is stable and cacheable. It does not depend on the message, so proactions and sub-agents with `get_skill` get it too
- The body is loaded by the model itself with `get_skill(name)` **before** acting on a task the skill covers. The `COMPÉTENCES` line of `default.yaml` says so; do not reintroduce a keyword search that injects bodies
- Create, update and delete go through the native tools `create_skill`, `update_skill`, `delete_skill` (the last one is held for approval by the policy)
- **A skill the owner wrote is locked** (MAG-892): created or edited from the admin, its file carries `verrouillee: true` (the owner may also write the key by hand in the bucket). `create_skill` / `update_skill` of Maggie on it are refused with `{"error": "locked"}`, nothing written: she proposes the change, the owner applies it from the admin

## Sub-agents
- A sub-agent is a Markdown file `agent/data/agents/<name>.md` with a YAML frontmatter, loaded once at startup into `subagent_registry` (`app/agents/registry.py`):
  ```
  ---
  name: researcher
  description: one line, shown to Maggie in the `delegate` tool
  model: haiku              # haiku | sonnet | opus — aliases in config.py (`model_aliases`)
  tools: ["search*", "get_*"]   # fnmatch patterns on tool names
  max_iterations: 8         # optional, default 8
  ---
  <system prompt>
  ```
- An invalid file (no frontmatter, unknown model alias, empty prompt, duplicate name) is skipped with a warning, never fatal
- Maggie hands a task over with the native tool `delegate(agent, task)`, built at request time from the registry (agents in the `enum`, descriptions in the tool text) and **absent when the registry is empty or the call comes from A2A**
- `app/agents/delegate.py` runs the agent in its own conversation: its prompt + memory + skills index (when it has `get_skill`), the task as the only message, only the tools matching its patterns, its own model. It returns `{agent, result, tool_calls}` — the summary, not the exchange — which isolates the context and lets an agent be read-only by construction
- **No recursion**: `delegate` is always removed from a sub-agent's tools, and `ScopedToolRouter` refuses any call outside the selected tools whatever name the model invents
- Its calls carry `source="subagent:<name>"` and metrics `call_type="subagent"`, and **the policy still applies to them**
- The sub-agent does not see the conversation: the task Maggie writes must be complete. Shipped agent: `researcher` (read-only cross-module search and synthesis, on Haiku)

## Policy and approvals
- `agent/data/policy.yaml` gives each tool call a mode: `allow` (Act), `ask` (Propose) or `deny`. The first matching rule wins, otherwise `default`. A rule has `tools` (fnmatch), optionally `when: {argument: [values]}` and `sources: [...]` (exact names: `chat`, `chat_stream`, `proaction`, `subagent:<name>`, `a2a`; no glob). Exceptions go before broad patterns. A malformed file stops the agent from starting — it must never degrade to « allow everything »
- Evaluated by `app/policy/engine.py` (`evaluate(tool, arguments, source)`): `source == "approval"` is always `allow` (it *is* the user's answer); `ask` from `a2a` becomes `deny` (nobody is there to answer)
- Shipped policy: `delete_*` and `manage_*` with `action: delete` are `ask`; `delete_instruction` and the memory tools (`memory_write_fiche`: she has no tool to erase, what stops being true is marked replaced) stay `allow` (Maggie's own notes). A new destructive tool is covered by the `delete_*` pattern, or needs its own rule
- Talking to the owner is never held (owner, 23 Nov.): `manage_notifications` create, `send_voice_message`, `ask_owner` and a proaction's own message pass the policy and the checker in every source; a card is only for what acts elsewhere. A card held by the checker says the action and why (`summary.py`, `NOT_FOUND_REASON`); the thread's decision line drops the reason (`without_reason`)
- The guard sits in `ToolRouter.call_tool`, before native or MCP routing. `deny` returns `{"error": "Action interdite par la politique"}`. `ask` stores a **pending action** with its arguments frozen (table `agent_pending_action`: tool, arguments, source, `context_id`, status, result, `expires_at` = creation + 24 h; an identical pending call is reused, not duplicated) and returns `{"status": "pending_approval", "approval_id": …}` — Maggie ends her turn by saying what she is waiting for. The streamed `tool_result` carries the same status for the Mind panel
- Statuses: `pending` → `approved` / `denied` / `expired` / `failed`. The scheduler loop expires overdue actions every minute
- Endpoints (`app/api/routes.py`, user-scoped, another user's id is a 404): `GET /approvals?status=pending`, `POST /approvals/{id}/approve` (409 if already decided, 410 if expired), `POST /approvals/{id}/deny`. Approving replays the **frozen arguments** with `source="approval"`, stores the result, then — in the background, with `tools=None` — Maggie announces the outcome in the original thread. Denying runs nothing and calls no model
- The logic lives in `app/domain/approvals/service.py` (`ApprovalService`: `approve`, `deny`, `announce`, built by `container.approvals`); the routes only translate its errors (`app/domain/approvals/errors.py`) into 404, 409, 410, 400. A domain service raises its own errors, never `HTTPException`, and `app/llm` never imports `app.api`
- Every change is published on Mercure `/approvals/{userId}` (`private=on`, like all agent topics) for the web and mobile cards
- Proactions run under the same policy: a deletion scheduled by Maggie shows up as a pending card instead of being executed
- The policy is enforced in code, not asked of the model: never rely on the prompt to stop a destructive call

## Data ownership
- Memory (a folder per user, `MemoryService`) and instructions: every read or write is filtered by `user_id`; an id belonging to another user behaves like an unknown id (same "not found" error, no leak). New per-user data follows the same rule and ships a two-user isolation test. Pending actions follow it too
- Skills are files in `Compétences/` (MAG-218), the `skill` table of `maggie_agent` (MAG-187) being the index derived from them; the in-memory index (`skill_index.rebuild()`) is reloaded from the table at startup, after `skill_files.start_up()` has brought it in step with the files. They are **global**, shared by all users: accepted while Maggie has a single user. Multi-user means adding a `user_id` to that table first (MAG-108).
- Secrets (MAG-871): the owner's message is held by `app.llm.held_secret` at the chat routes (a code, PIN, password or card number becomes `[masqué]` before any storage, log or model); every tool result leaves `ToolRouter.call_tool` through `app.redaction.redact`; logs and GlitchTip events are redacted too. `[masqué]` is the only mask
- Sub-agent definitions and the policy are files in the repository, not user data: global, versioned, reviewed like code

## Memory folder
- Maggie's memory is a folder of Markdown files per user in a bucket, with a disposable Postgres index: see `memory.md` (ADR-011). The old `memory` table and its tools stay until the content tickets replace them

## Database schema
- No Alembic. A new table goes through `create_all` (import its model in `app/main.py`); a new column or index is idempotent SQL (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) in `context_repo.run_migrations()`, run at startup

## Dependencies
- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config
- `ruff` for linting and formatting

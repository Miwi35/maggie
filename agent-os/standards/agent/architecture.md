# Agent Hub Architecture

Python FastAPI service. Separate from Symfony — consumes the API via MCP like any external client.

## MCP Client
- HTTP transport (not stdio) — connects to `http://nginx/_mcp`
- Stateful: captures `Mcp-Session-Id` from server
- Singleton `mcp_client` initialized at startup
- Tools cached after first `tools/list` call

## LLM Gateway
- Anthropic Claude via `anthropic` SDK
- Agentic tool loop in `app/llm/runner.py` (`run_tool_loop`): max 5 iterations per request by default, `tools=None` gives a plain answer. Shared by chat, proactions, sub-agents and the announcement of an approved action. `streaming.py` keeps its own loop because it emits SSE events
- MCP tool schemas converted to Anthropic format
- Every tool call goes through `ToolRouter.call_tool(name, arguments, user_id, source)` — the single place where the policy applies (see Policy and approvals). `source` is one of `chat`, `chat_stream`, `proaction`, `subagent:<name>`, `a2a`, `approval`
- System prompt = cached stable prefix (personality + skills index) then a volatile block (memory, directives, open threads, date) — `app/llm/prompt_cache.py`
- Conversation history is persisted (`agent_message`) and rebuilt per request by `app/llm/history.py`: the current thread's messages in full, a short global window, and the other threads as summaries in the system prompt

## Personality
- Default loaded from YAML (`app/personality/default.yaml`), overridden per user by the `personality` row in `maggie_agent`
- Template placeholders: `{name}`, `{language}`, `{tone}`

## Skills
- A skill is a Markdown procedure the user taught Maggie: name, description, tags, body. Stored in the `skill` table; `skill_index` keeps the entries in memory
- **On demand, never injected.** The system prompt carries only the index — `get_skills_index()` returns `- name: description` for every skill, sorted by name so the prefix is stable and cacheable. It does not depend on the message, so proactions and sub-agents with `get_skill` get it too
- The body is loaded by the model itself with `get_skill(name)` **before** acting on a task the skill covers. The `COMPÉTENCES` line of `default.yaml` says so; do not reintroduce a keyword search that injects bodies
- Create, update and delete go through the native tools `create_skill`, `update_skill`, `delete_skill` (the last one is held for approval by the policy)

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
- `agent/data/policy.yaml` gives each tool call a mode: `allow` (Act), `ask` (Propose) or `deny`. The first matching rule wins, otherwise `default`. A rule has `tools` (fnmatch), optionally `when: {argument: [values]}` and `sources: [...]`. Exceptions go before broad patterns. A malformed file stops the agent from starting — it must never degrade to « allow everything »
- Evaluated by `app/policy/engine.py` (`evaluate(tool, arguments, source)`): `source == "approval"` is always `allow` (it *is* the user's answer); `ask` from `a2a` becomes `deny` (nobody is there to answer)
- Shipped policy: `delete_*` and `manage_*` with `action: delete` are `ask`; `delete_memory`, `delete_instruction` and memory updates stay `allow` (Maggie's own notes). A new destructive tool is covered by the `delete_*` pattern, or needs its own rule
- The guard sits in `ToolRouter.call_tool`, before native or MCP routing. `deny` returns `{"error": "Action interdite par la politique"}`. `ask` stores a **pending action** with its arguments frozen (table `agent_pending_action`: tool, arguments, source, `context_id`, status, result, `expires_at` = creation + 24 h; an identical pending call is reused, not duplicated) and returns `{"status": "pending_approval", "approval_id": …}` — Maggie ends her turn by saying what she is waiting for. The streamed `tool_result` carries the same status for the Mind panel
- Statuses: `pending` → `approved` / `denied` / `expired` / `failed`. The scheduler loop expires overdue actions every minute
- Endpoints (`app/api/routes.py`, user-scoped, another user's id is a 404): `GET /approvals?status=pending`, `POST /approvals/{id}/approve` (409 if already decided, 410 if expired), `POST /approvals/{id}/deny`. Approving replays the **frozen arguments** with `source="approval"`, stores the result, then — in the background, with `tools=None` — Maggie announces the outcome in the original thread. Denying runs nothing and calls no model
- Every change is published on Mercure `/approvals/{userId}` (`private=on`, like all agent topics) for the web and mobile cards
- Proactions run under the same policy: a deletion scheduled by Maggie shows up as a pending card instead of being executed
- The policy is enforced in code, not asked of the model: never rely on the prompt to stop a destructive call

## Data ownership
- Memories and instructions: every repository read or write is filtered by `user_id`; an id belonging to another user behaves like an unknown id (same "not found" error, no leak). New per-user data follows the same rule and ships a two-user isolation test. Pending actions follow it too
- Skills live in the `skill` table of `maggie_agent` (MAG-187), saved with the rest of the agent's data; the in-memory index (`skill_index.rebuild()`) is reloaded from it at startup. They are **global**, shared by all users: accepted while Maggie has a single user. Multi-user means adding a `user_id` to that table first (MAG-108).
- Sub-agent definitions and the policy are files in the repository, not user data: global, versioned, reviewed like code

## Dependencies
- `uv` (astral-sh) for package management, not pip/poetry
- `pydantic-settings` for env-based config

# Standards for Conversation Context Summaries

Read the files; this page records only what each one binds in this spec.

| Standard | What it binds here |
|---|---|
| [global/testing](../../standards/global/testing.md) | Definition of Done — every unit touched tested, one e2e journey, docs in Linear |
| [agent/architecture](../../standards/agent/architecture.md) | Every model call goes through `create_llm_client()`, so the summarizer is scripted under `LLM_PROVIDER=fake` like everything else; per-user filtering on every read |
| [agent/testing](../../standards/agent/testing.md) | pytest, `asyncio_mode = "auto"`, `AsyncMock` for internal deps, plain asserts |
| [admin/react-admin](../../standards/admin/react-admin.md) | The Mind panel is a plain MUI component fed by `ChatWidget`'s state |
| [admin/testing](../../standards/admin/testing.md) | Vitest + Testing Library, render / interaction / empty state |
| [global/real-time](../../standards/global/real-time.md) | A context that changes publishes on its user's `contexts` topic — a summary written in the background has no other way to reach an open panel |
| [global/worktree-checks](../../standards/global/worktree-checks.md) | `task fix:all`, `task wt:test:agent`, `task wt:test:admin` — never the dev stack from a worktree |

## Decisions from Linear that bind this spec

- **ADR-006** — the functional spec and the user guide live in Linear, not in the repo.
- **MAG-95** — only the model is faked. The summarizer therefore gets a scenario file
  rather than an e2e-only branch in the code.

## Gotchas this spec has to respect

From `CLAUDE.md` and the agent's own history:

- **The agent database has no migration tool.** New tables appear through
  `AgentBase.metadata.create_all`, which never alters an existing one — a new column on
  `conversation_context` has to be an `ALTER TABLE … ADD COLUMN IF NOT EXISTS` in
  `ContextRepository.run_migrations`, called from the lifespan. A column added only to the
  model is a column production never gets.
- **Serialised names.** The payload published on the `contexts` topic and the one returned
  by `GET /agent/contexts` are the same `to_dict()`, and the admin reads
  `summary` from both. Adding the key to only one of the two paths is the class of bug
  `DtoContractTest` exists for on the mobile side.
- **A context update must not erase what the panel already shows.** `ChatWidget` replaces
  the whole context object on every `context_update` event and on every Mercure message,
  so the summary has to travel on both or the next message wipes the line off the screen.

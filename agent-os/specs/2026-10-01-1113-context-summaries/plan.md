# Conversation Context Summaries — Plan

Ticket: [MAG-11](https://linear.app/meven/issue/MAG-11/resumer-les-contextes-de-conversation)
Project: Conversation continue
Shaping notes and decisions: [shape.md](shape.md) · Standards: [standards.md](standards.md)

## Acceptance criteria

1. `conversation_context` carries `summary` and `summary_updated_at`; both appear in
   `to_dict()`, so in `GET /agent/contexts` and in what is published on the `contexts`
   Mercure topic.
2. An existing database gets the two columns at startup, without losing a row.
3. A context whose messages have grown by `CONTEXT_SUMMARY_EVERY_MESSAGES` (default 10)
   since its last summary is re-summarized by Haiku, from the previous summary and the new
   messages only.
4. The summary is written in the background: the chat stream's `RUN_FINISHED` does not
   wait for it, and a summarizer that fails leaves the conversation untouched.
5. The system prompt of every streamed message lists each open context's summary under its
   label, in the volatile block — never in the cached prefix.
6. The Mind panel shows a thread's summary under its label, live, without a reload.
7. `ContextSummarizer.summarize(context_id)` is public and standalone, so MAG-12's
   scheduler can call it on the dormant and closed transitions.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`, `standards.md`.

## Task 2: The two columns and the writes behind them

- `agent/app/db/context_model.py` — `summary` (`Text`, nullable) and `summary_updated_at`
  (`DateTime(timezone=True)`, nullable); `to_dict()` gains `summary` and
  `summaryUpdatedAt`.
- `agent/app/db/context_repository.py` — `run_migrations()` adds both columns with
  `ADD COLUMN IF NOT EXISTS`; `set_summary(context_id, summary)` stores them, stamps
  `updated_at` and publishes the context on the user's `contexts` topic, with the same
  best-effort `try/except` as the other writes.
- `agent/app/db/message_repository.py` — `find_by_context(context_id, since=None,
  limit=…)` in chronological order and `count_by_context(context_id, since=None)`.
  `since` is what makes the summary incremental.

## Task 3: The summarizer

`agent/app/llm/context_summary.py` — `ContextSummarizer`, singleton `context_summarizer`.

- `summarize(context_id)` — loads the context, reads the messages since
  `summary_updated_at` (all of them when there is no summary yet), asks the fast model for
  5 lines at most in French, stores the result, returns it. Returns `None` and logs a
  warning on anything that goes wrong: no context, no new message, an empty answer, an API
  failure. `record_llm_usage(call_type="context_summary")` on both outcomes, like
  `chat_stream`.
- `maybe_summarize(context_id)` — counts the messages since `summary_updated_at` and calls
  `summarize` only once that count reaches the threshold.
- `agent/app/config.py` — `context_summary_every_messages: int = 10` and
  `anthropic_fast_model: str = "claude-haiku-4-5-20251001"`, the latter also replacing the
  three literals in `_resolve_context`.

## Task 4: Trigger and injection in the streaming gateway

`agent/app/llm/streaming.py`

- After the assistant message is persisted, spawn `maybe_summarize(current_context_id)` as
  a background task held in a set on the gateway — a task nothing references can be
  garbage-collected mid-await.
- `_build_system_prompt` — under each open context's line, `  Résumé : <summary>` when it
  has one. Capped at `SUMMARY_PROMPT_LIMIT` contexts, most recently updated first, since
  `find_active` is already ordered by `updated_at`.
- `_resolve_context` returns `summary` alongside `id`, `label` and `status`, so the
  `context_update` event carries it and the panel does not blank the line it is showing.

## Task 5: The summary on screen

- `admin/src/components/mind/types.ts` — `summary?: string` on `ContextState`.
- `admin/src/components/mind/ContextList.tsx` — a second line under the label when there is
  a summary, two lines at most, `data-testid="mind-context-summary"`.
- `admin/src/components/chat/ChatWidget.tsx` — carry `summary` through the three places a
  context is built: the mount fetch of `/agent/contexts`, the `context_update` stream event
  and the Mercure message.

## Task 6: The fake LLM's side

- `agent/fixtures/fake-llm/12-context-summary.yaml` — matched on the summarizer's own
  system prompt, so the call does not fall through to "no scenario" and poison every later
  system prompt with the `[fake-llm]` sentence.
- `agent/fixtures/fake-llm/71-context-summary-recall.yaml` — matched on the summary's text
  *in the system prompt* plus the journey's question. It can only answer if the injection
  really happened.
- `docker-compose.e2e.yml` — `CONTEXT_SUMMARY_EVERY_MESSAGES: 4` on the agent, so two
  exchanges cross the threshold inside a journey.
- `agent/fixtures/fake-llm/README.md` — the summarizer added to the "Beyond chat" table.

## Tests

| Unit touched | Tests |
|---|---|
| `ConversationContext` | `to_dict()` carries `summary` and `summaryUpdatedAt`, and `None` when unset |
| `ContextRepository.set_summary` | happy path on a real SQLite session asserting the row, the stamp and the Mercure publication; unknown id returns `None`; a Mercure failure still commits |
| `ContextRepository.run_migrations` | both `ALTER TABLE` statements are issued |
| `MessageRepository.find_by_context` / `count_by_context` | chronological order, `since` filter, another context's messages excluded |
| `ContextSummarizer.summarize` | happy path (stored, returned, usage recorded); no context; no message; empty answer; API error — each returns `None` and writes nothing |
| `ContextSummarizer.maybe_summarize` | below the threshold does nothing, at the threshold summarizes, counts from `summary_updated_at` |
| `StreamingGateway._build_system_prompt` | a context with a summary puts it in the volatile block, one without does not, the cached prefix is untouched, the cap holds |
| `StreamingGateway.chat_stream` | the summary is spawned after the assistant message and `RUN_FINISHED` does not wait for it |
| `StreamingGateway._resolve_context` | the returned payload carries the summary |
| `ContextList` | renders the summary, renders without one, `data-testid` for the journey |
| `ChatWidget` | a `context_update` for a context already shown keeps its summary |

Run from this worktree: `task wt:test:agent`, `task wt:test:admin`, `task fix:all`.

## E2E journey

Extends **MAG-99** (chat), in `e2e/web/tests/chat.spec.ts` — appended to the serial group,
after every test that counts contexts, so none of their counts move.

> **Given** the owner has been talking to Maggie in one thread for two exchanges, with
> `CONTEXT_SUMMARY_EVERY_MESSAGES=4`
> **When** the second answer has streamed
> **Then** `GET /agent/contexts` reports a non-empty `summary` on that thread, which is not
> the `[fake-llm]` sentence,
> **And** the Mind panel shows it under the thread's label after a reload,
> **When** the owner then asks a question whose scripted answer only matches if the summary
> is in the system prompt (`system_contains`)
> **Then** Maggie answers it rather than the "no scenario" sentence.

The last step is the one that proves the injection: the scenario is unreachable unless the
summary really travelled into the model's system prompt.

## Definition of done

1. Tests — the table above.
2. E2E — the journey above, in `chat.spec.ts` (MAG-99).
3. Not a bug fix.
4. CI green on the PR linking MAG-11.
5. Linear: the agent module's functional spec and the user guide gain the thread summary,
   under the documentation index.

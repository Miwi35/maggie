# References for Context-aware conversation history

## The code being replaced

### `_load_conversation_history`, twice

- **Location:** `agent/app/llm/streaming.py` and `agent/app/llm/gateway.py`
- **Relevance:** the method this ticket removes. Two near-identical copies — the global
  window, the `user`/`assistant` filter, the same-role merge, the `try/except` that
  returns `[]`. The duplication is the reason the new code is one module both import.
- **Key patterns to keep:** never raising, and merging consecutive same-role turns (the
  API refuses two user turns in a row).

### `app/llm/contexts.py`

- **Location:** `agent/app/llm/contexts.py`
- **Relevance:** the precedent for « shared by the streamed path and the proaction path, so
  a second copy cannot drift » (MAG-11, MAG-14). `route_message` joins it for the same
  reason, and `active_contexts_section` is the one place the open threads are described.
- **Key patterns:** best-effort throughout, `MAX_SUMMARIZED_CONTEXTS` capping the tokens
  on the summaries while every thread keeps its label.

## The shape to copy

### `ContextSummarizer` (MAG-11)

- **Location:** `agent/app/llm/context_summary.py`, spec
  `agent-os/specs/2026-10-01-1113-context-summaries/`
- **Relevance:** the nearest module of the same kind — reads the thread through
  `message_repo.find_by_context`, bounded per pass, all best-effort, a module docstring
  that says what the thing is for and what it deliberately does not do.

### `_resolve_proaction_context` (MAG-14)

- **Location:** `agent/app/llm/gateway.py`
- **Relevance:** the precedent for a gateway returning a `context_id` in its result so the
  caller can store the message in the thread. `POST /agent/chat` copies what
  `POST /agent/proaction` already does in `agent/app/api/routes.py`.

## The e2e side

### `71-context-summary-recall.yaml` and the MAG-11 journey

- **Location:** `agent/fixtures/fake-llm/71-context-summary-recall.yaml`,
  `e2e/web/tests/chat.spec.ts` (« a long thread is summarized… »)
- **Relevance:** how a journey proves something reached the model when it is not observable
  from a browser — a scenario that cannot match unless the text is there
  (`system_contains`). `history_contains` is the same trick for the messages array.

### `10-context-router-existing.yaml` and `05-context-router-new-topic.yaml`

- **Location:** `agent/fixtures/fake-llm/`
- **Relevance:** how the router is scripted, how a capture group carries an id the fixture
  cannot know, and how the numeric prefix expresses precedence. The new
  `04-context-router-budget-again.yaml` sits below 05 precisely so the change-of-subject
  scenario does not catch its message.

### `e2e/web/tests/chat.spec.ts`

- **Location:** `e2e/web/tests/chat.spec.ts`
- **Relevance:** serial, `retries: 0`, and every test leaning on the conversation the ones
  above it wrote. A new test goes last, and must not insert messages that break the counts
  the earlier ones assert.

# Context-aware conversation history — Plan

Ticket: [MAG-13](https://linear.app/meven/issue/MAG-13/construire-lhistorique-envoye-au-llm-selon-le-contexte)
Project: Conversation continue · blocked by [MAG-11](https://linear.app/meven/issue/MAG-11) (landed)
Shaping notes and decisions: [shape.md](shape.md) · Standards: [standards.md](standards.md) · References: [references.md](references.md)

## Acceptance criteria

1. The context is routed **before** anything is built from it: the history, the system
   prompt and the `context_update` event all come after the routing call, on the streamed
   path (`StreamingGateway.chat_stream`) and on the non-streamed one (`LLMGateway.chat`).
2. The history sent to the model is the messages of **the thread the current message was
   routed into** (up to `CONTEXT_HISTORY_MESSAGES`, default 40), merged with a **short
   global window** (`RECENT_HISTORY_MESSAGES`, default 8) so a reference to what was just
   said elsewhere is not lost. No thread at all (routing failed, no model) falls back to
   the global window alone.
3. A message that comes in through the global window but belongs to **another thread** is
   labelled `[fil « <label> »] ` so the model can tell the current conversation from a
   neighbouring one. Messages of the current thread are sent verbatim.
4. The **other** open threads reach the model as their summary, in the system prompt
   (where MAG-11 already puts them), never as raw messages. The current thread is marked
   `← fil en cours` in that list, and carries the last tool calls recorded in it, so what
   Maggie already did in the thread survives the turn.
5. The conversation sent always starts with a `user` message, whatever the history holds —
   a thread whose oldest loaded message is one of Maggie's own would otherwise be refused
   by the API.
6. `LLMGateway.chat` no longer sends the user's message twice: the caller that stored it
   (`exclude_message_id`) lets the history carry it, and only a caller that stored nothing
   (A2A) hands it over to be appended.
7. `POST /agent/chat` stores its answer in the thread the question was routed into, and
   re-summarizes it — the same two sinks as `POST /agent/proaction`.
8. `app/memory/conversation.py` and its test are gone, and nothing imports them.
9. `MAX_CONVERSATION_HISTORY` is replaced by `CONTEXT_HISTORY_MESSAGES` and
   `RECENT_HISTORY_MESSAGES`; no code reads the old name.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`, `standards.md`, `references.md`. No visuals — the
ticket has no attachment and nothing changes on screen.

## Task 2: The two windows the repository has to serve

- `agent/app/db/message_repository.py` — `find_by_context()` gains an optional `user_id`,
  so a thread's messages can be loaded with the owner checked in the query rather than
  trusted from the router (MAG-203 is the related ticket; the router validates ownership,
  this is the belt).
- `agent/app/config.py` — `context_history_messages: int = 40` and
  `recent_history_messages: int = 8` replace `max_conversation_history`. The two
  docstrings that name the old setting (`app/llm/contexts.py`,
  `app/llm/context_summary.py`) are updated.

Tests it owes: `find_by_context` with a `user_id` that owns the thread and one that does
not, on `chat_db` (real SQL).

## Task 3: `app/llm/history.py` — the conversation the model is sent

New module, one entry point shared by both gateways so a second copy cannot drift:

```python
async def build_history(user_id, *, context_id=None, pending_message=None) -> list[dict]
```

- loads the thread's messages and the global window, merges them by id, orders by
  `created_at`;
- labels the messages of another thread with that thread's own label (from
  `context_repo.find_active`), `[autre fil] ` when the thread is gone;
- keeps only `user`/`assistant` rows with content, merges consecutive same-role turns,
  drops leading assistant turns;
- appends `pending_message` when the caller stored nothing;
- never raises: a database that will not answer costs the model the conversation, not the
  user the answer.

Tests it owes: the thread's messages are in; a message of another thread is labelled; one
of the current thread is not; the global window brings in what the thread does not hold;
the same message loaded twice appears once; chronological order; consecutive same-role
turns merged; a history starting on an assistant turn is trimmed; `pending_message`
appended, and merged into a trailing user turn; `context_id=None` falls back to the
window with no labelling; a repository that raises gives `[]` plus the pending message.

## Task 4: Route first, on both paths

- `agent/app/llm/contexts.py` — `route_message(client, text, user_id, message_id=None)`:
  `resolve_context` plus the tagging of the message it came from, best-effort. This is
  what `StreamingGateway._resolve_context` was, lifted so `LLMGateway.chat` can use the
  same one. `active_contexts_section(user_id, current_context_id=None)` marks the current
  thread, always carries its summary, and lists the last tool calls recorded in it.
- `agent/app/llm/streaming.py` — route, emit `context_update`, *then* build the history
  and the system prompt. `_load_conversation_history` is deleted.
- `agent/app/llm/gateway.py` — `chat()` routes the message, builds the history around the
  thread, passes `current_context_id` to the system prompt and returns `context_id` in
  its result. `_load_conversation_history` is deleted. `proaction()` is unchanged: it has
  no history to build and its thread is only knowable from the answer (MAG-14).
- `agent/app/api/routes.py` — `POST /agent/chat` stores the answer in
  `result["context_id"]` and awaits `maybe_summarize`, like `POST /agent/proaction`.

Tests it owes: routing happens before the history is loaded (asserted on call order); the
history is built with the resolved context id; a routing failure still answers, with the
global window; the current thread is marked and carries its tool calls; the other threads
carry their summaries; `/agent/chat` stores the answer in the routed thread; the user's
message is not doubled.

## Task 5: Delete the dead code

`agent/app/memory/conversation.py` and `agent/tests/test_conversation_memory.py`. The
three standards/skill files that list the test file as an example are updated to name a
test that exists.

## Task 6: The e2e journey

- `agent/app/llm/fake.py` — a `history_contains` match condition, matched on the
  conversation sent **minus the message being answered**. Without it a journey has no way
  to assert what the history held: the messages array is not observable from a browser,
  exactly like the system prompt, and `system_contains` is how MAG-11 and MAG-22 proved
  their own injection.
- `agent/fixtures/fake-llm/04-context-router-budget-again.yaml` — routes one message back
  into the « Budget e2e » thread, by capturing that thread's own id out of the list the
  router is shown. Numbered below 05 so the change-of-subject scenario does not open a
  third context.
- `agent/fixtures/fake-llm/72-thread-history-recall.yaml` — answers that message, and
  declares a sentence from the Budget thread as `history_contains`, so it is unreachable
  unless that thread's own messages were loaded.
- `docker-compose.e2e.yml` — `RECENT_HISTORY_MESSAGES: "2"` on the agent, so the global
  window cannot be what carries that sentence.
- `e2e/web/tests/chat.spec.ts` — the journey below, last in the file.

Tests it owes: `history_contains` matching and not matching, and a scenario file declaring
it, in `agent/tests/test_fake_llm.py`.

## Tests

| Unit | Tests |
|---|---|
| `build_history` | thread messages in; foreign message labelled; current-thread message not; window completes the thread; dedup; chronological; same-role merge; leading assistant dropped; `pending_message` appended and merged; no context → window only, unlabelled; repository raising → `[]` |
| `MessageRepository.find_by_context` | owner's thread returned, another user's thread empty (real SQL on `chat_db`) |
| `active_contexts_section` | current thread marked and carrying its tool calls; the others carry their summary; summary cap unchanged |
| `route_message` | tags the message, survives a tagging failure, `None` when routing failed |
| `StreamingGateway.chat_stream` | routes before loading the history; history built with the resolved id; routing failure still answers |
| `LLMGateway.chat` | history built around the routed thread; the user's message is not doubled; `context_id` returned |
| `POST /agent/chat` | the answer is stored in the routed thread |
| `Scenario.matches` | `history_contains` holds and fails; parsed from a file |

No bug fix in scope — the doubled user message in `LLMGateway.chat` is removed as part of
rewriting the history construction, and is covered by the `chat` tests above.

## E2E journey

**Extends:** MAG-99 — Parcours e2e : chat

- **Given** the conversation `chat.spec.ts` has already had: a « Conversation e2e » thread
  with several exchanges, and a « Budget e2e » thread opened by the change of subject,
  whose only exchange contains « où en est mon budget »
- **And** the agent runs with `RECENT_HISTORY_MESSAGES=2`, so the global window holds
  nothing but the last exchange
- **When** the owner sends « Reprends le fil de mon budget, s'il te plaît », which the
  router places back in the « Budget e2e » thread
- **Then** Maggie answers the scripted sentence of `72-thread-history-recall.yaml`, which
  only matches when « où en est mon budget » is in the history she was sent
- **And** the `context_update` event says `matched` on the « Budget e2e » thread, not a
  third context
- **And** the answer is a single bubble in the panel

A `[fake-llm] aucun scénario…` answer here means the thread's own messages never reached
the model — the failure names its own cause.

## Definition of Done

- [ ] Unit tests above, green (`task wt:test:agent`)
- [ ] E2E journey written and run (`task e2e:web`)
- [ ] No bug fix in scope needing a red-first test
- [ ] CI green on a PR linking MAG-13
- [ ] Module functional spec + user guide updated in Linear (ADR-006)

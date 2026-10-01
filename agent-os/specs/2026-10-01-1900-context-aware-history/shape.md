# Context-aware conversation history — Shaping Notes

Shaped autonomously from the ticket (MAG-13), the code, and the specs of MAG-11, MAG-12
and MAG-14. No question was left open, so nothing was escalated.

## Scope

The model was sent the last 50 messages of the user, every thread mixed together, loaded
*before* the context router had said which thread the message belonged to. So the routing
decided nothing: Maggie read a conversation that was whatever had happened recently, and a
thread she was actually in contributed no more than a thread she had left an hour ago.

What changes: the thread is routed first, and the conversation sent is built around it —
the thread's own messages, a short global window for cross-thread references, and the
other threads as the summaries MAG-11 already writes. Plus the dead
`app/memory/conversation.py`.

## Decisions

### 1. Where the other threads' summaries go

- **Dilemma:** the ticket asks for « les résumés des autres contextes actifs » in what is
  sent. The system prompt already carries them (MAG-11).
- **Options:** repeat them as pseudo-messages in the history · leave them in the system
  prompt and say so.
- **Choice:** leave them in the system prompt; the history carries messages only.
- **Why:** sending the same text twice costs tokens and teaches the model that a summary
  is a turn someone took. `active_contexts_section` is the one place MAG-11 put them, and
  the Mind panel, the proaction path and the streamed path all read that one place.

### 2. What to do about the tool calls lost between turns

- **Dilemma:** the ticket's framing is that only the final text survives a turn, so the
  tool calls are lost. Restoring them properly means persisting `tool_use` /
  `tool_result` blocks, which `agent_message` cannot hold — it has a `TEXT` content and
  one role.
- **Options:** change the message schema and store the blocks · surface what *is* already
  stored, the context's `tool_calls_log` · do nothing.
- **Choice:** surface `tool_calls_log` for the current thread in the system prompt, and
  file the schema change as a follow-up.
- **Why:** the log is written on every call already (`context_repo.append_tool_call`) and
  read by nothing but the Mind panel. Listing it tells Maggie she has already looked at
  the grocery list this thread, which is the behaviour the ticket is after, for ten lines
  and no migration. Replaying whole `tool_use` blocks is a different ticket: it needs a
  content column that can hold JSON blocks, a decision about how far back to replay them,
  and a cache-friendly ordering — all of it worth doing on its own, none of it worth
  bolting onto this one.

### 3. The non-streamed path routes too

- **Dilemma:** `LLMGateway.chat` never routed a context at all, so there was no thread to
  build its history around.
- **Options:** leave `/agent/chat` on the global window · route there as well.
- **Choice:** route there as well, and return the `context_id` so the route stores the
  answer in the thread.
- **Why:** the ticket names `gateway.py` as a file to change, and a history « selon le
  contexte » is not expressible without a context. `proaction()` already returns a
  `context_id` for the same reason (MAG-14), so the shape exists. The side effect is that
  `POST /agent/chat` finally tags both halves of its exchange, instead of leaving them
  outside every thread.

### 4. A message from another thread is labelled, not hidden

- **Dilemma:** the global window brings in messages of other threads. Sending them
  unmarked is what the ticket complains about; dropping them loses « et ça aussi » said a
  minute ago in a neighbouring thread.
- **Options:** drop them · send them unmarked · prefix them with their thread's label.
- **Choice:** prefix with `[fil « <label> »] `.
- **Why:** it is the cheapest thing that makes the window useful instead of confusing, and
  it is the difference between « mixed together » and « built according to the context ».

### 5. `proaction()` keeps no history

- **Dilemma:** should the proaction path get a history now that one can be built per
  thread?
- **Options:** build one from the thread it will be stored in · leave it as it is.
- **Choice:** leave it.
- **Why:** a proaction's thread is only known *from the answer* (MAG-14,
  `_resolve_proaction_context`) — there is nothing to route before the call. It already
  reads the open threads' summaries, which is what MAG-14 decided it needed.

### 6. Two settings instead of one

- **Dilemma:** `max_conversation_history = 50` no longer describes anything: the two
  windows are different sizes for different reasons.
- **Options:** keep the name for the global window · two named settings.
- **Choice:** `context_history_messages = 40` and `recent_history_messages = 8`; the old
  name is removed.
- **Why:** « max » with a value of 8 reads as a bug. Nothing sets the old variable in any
  compose file, so removing it breaks no deployment.

### 7. A history that will not load must not cost the answer

- **Dilemma:** `build_history` promises never to raise, but returning `[]` is just as fatal
  one step later — the API refuses a conversation with no message in it, so a transient
  database failure came out as `AI service error: messages: at least one message is
  required`. The old code could not hit this: it appended the user's message
  unconditionally, right after the `try/except`.
- **Options:** let the caller re-append the message · give the builder the message as a
  floor it only uses when it has nothing else.
- **Choice:** `fallback_message`, applied only when the history is empty.
- **Why:** the promise belongs to the one shared entry point. Two callers each remembering
  to guard is exactly the duplication this module exists to remove, and the floor is the
  message being answered, which both of them already hold. Found in review.

### 8. Routing only for a caller that stored its message

- **Dilemma:** `chat()` is also the A2A bridge's entry point, and that caller stores
  neither the question nor the answer.
- **Options:** route for everyone · route only when the caller stored its message.
- **Choice:** route only when `exclude_message_id` is given.
- **Why:** a thread is where a message and its answer live. For A2A there is nothing to put
  in one, so routing buys a fast-model call per peer request and a `conversation_context`
  row nobody ever writes in — kept permanently active by its own `touch()`. Found in
  review.

### 9. `POST /agent/chat` awaits the summary

- **Dilemma:** the route awaits `maybe_summarize` after answering, so a human waits for it.
- **Options:** await it, like `POST /agent/proaction` · spawn it with
  `fastapi.BackgroundTasks`.
- **Choice:** await it.
- **Why:** the two sinks of an exchange must leave a thread in the same state, which is
  what the comment above `/agent/proaction` already promises; splitting them is how one
  path ends up with summaries the other does not have. The cost is bounded and rare —
  `maybe_summarize` counts first, so in production (`CONTEXT_SUMMARY_EVERY_MESSAGES=10`)
  roughly one reply in five pays one Haiku round-trip, and the streamed path the admin and
  the web app use does not pay it at all. Revisit if the mobile app's p95 shows it.

### 10. `history_contains` in the fake LLM

- **Dilemma:** nothing a browser can see says what the history held.
- **Options:** assert on Maggie's wording · add a match condition on the history.
- **Choice:** add `history_contains`, mirroring `system_contains`.
- **Why:** it is exactly how MAG-11 and MAG-22 proved an injection that is not observable
  from the outside — a scenario that *cannot match* unless the thing is there. An
  assertion on wording would hold with any plausible answer, which the testing standard
  sends to the eval suite instead.

## Context

- **Visuals:** None. The ticket has no attachment and nothing changes on screen.
- **References:** see [references.md](references.md).
- **Product alignment:** `agent-os/product/mission.md` and the « Conversation continue »
  project. MAG-11 (summaries), MAG-12 (lifecycle) and MAG-14 (proactions read the threads)
  all built the thread as the unit of conversation; this ticket is the one that makes the
  model's own context follow it. No contradiction with the product docs.

## Standards Applied

- `global/testing` — the Definition of Done; this is a gateway and repository change, so
  happy path **and** every error branch, plus an e2e journey.
- `agent/architecture` — where a new module goes in `agent/app/`, how settings are
  declared with pydantic-settings, how the gateways are wired.
- `agent/testing` — pytest async auto mode, `conftest` fixtures (`chat_db` for real SQL),
  no assertion on « a mock was called » where state can be read.
- `global/e2e-environment` — the fake LLM, the scenario files, `task e2e:web`, the agent's
  environment in `docker-compose.e2e.yml`.
- `global/worktree-checks` — `task wt:test:agent`, `task wt:lint:agent`, `task fix:all`;
  never a formatter from a worktree against the dev stack.

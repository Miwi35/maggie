# Conversation Context Summaries — Shaping Notes

Ticket: [MAG-11](https://linear.app/meven/issue/MAG-11/resumer-les-contextes-de-conversation)
Project: Conversation continue

## Scope

A `conversation_context` row holds a label, a status and a log of tool calls — nothing
about what was *said*. So the only memory of a thread is the raw messages, and the only
thing the model ever sees is the last 50 of them, all threads mixed together: a
conversation that ran longer than that is simply gone, and a thread Maggie is not
currently in contributes nothing at all.

This ticket gives a context a summary, keeps it up to date with Haiku, and puts it in the
system prompt.

What it does **not** carry, and why:

| Out of scope | Ticket that carries it |
|---|---|
| `active → dormant → closed`, and the final summary on closing | MAG-12 — it owns `update_status` and the scheduler loop; it calls the summarizer this ticket ships |
| Rebuilding the history sent to the model per context, and trimming the raw messages | MAG-13 |
| Giving a proaction the history and a context | MAG-14 |

So what lands here is: two columns, one summarizer, one trigger, one line in the system
prompt — and the thread's summary on screen in the Mind panel, because a `Feature` has to
be acceptable in production and coverable by a journey.

## Decisions

Taken alone, per the autonomy rule in `CLAUDE.md`. Each one is a question the ticket, the
standards or the existing code did not already settle.

### The summary is injected, the raw history is left alone

**Options:** cut `max_conversation_history` now that summaries exist / drop from the
history the messages a summary already covers / inject the summaries and change nothing
about the raw messages.
**Choice:** inject, cut nothing.
**Why:** the ticket's third bullet ("à la place d'une partie des messages bruts") is
MAG-13's sentence written short — that ticket is *"Construire l'historique envoyé au LLM
selon le contexte: les messages du contexte choisi, quelques messages récents globaux et
les résumés des autres contextes actifs"*, and it is `blockedBy` this one. Today the
context is resolved *after* the history is loaded, so there is no per-context history to
cut against: trimming here could only mean dropping the current thread's own recent turns
and trusting a summary that may be ten messages stale. That is a regression this ticket
has nothing to assert against, and MAG-13 would have to undo it.

### The trigger is a background task in the streaming gateway

**Options:** await the summary in the stream before `RUN_FINISHED` / spawn it as a
background task / leave every trigger to MAG-12's scheduler loop.
**Choice:** a task spawned after the assistant message is persisted, with the public
`summarize()` the scheduler will call for the dormant and closed transitions.
**Why:** the threshold ("dépasse N messages") is only knowable where the messages are
written, so the trigger belongs to the gateway; but nobody is waiting for the result, and
awaiting a Haiku call before `RUN_FINISHED` would hold the chat's thinking indicator up
for a second on every Nth message. MAG-12 explicitly owns the status transitions *"avec un
résumé final"*, so this ticket ships the summarizer it calls rather than its loop.

### Incremental, counted from `summary_updated_at`

**Options:** re-summarize the whole thread every time / add a `summarized_message_count`
column / count the messages created after `summary_updated_at` and feed the previous
summary back in.
**Choice:** the last one.
**Why:** the ticket asks for exactly two columns, and `summary_updated_at` already answers
"how much is new" — `agent_message` is indexed on `(user_id, created_at)`. Re-reading a
thread from its first message would grow the Haiku call without bound, for a thread that
is meant to be long.

### The summary shows under the label in the Mind panel

**Options:** prompt-only, invisible / a line under the context label in the Mind panel / a
screen of its own.
**Choice:** one line under the label, truncated to two, `data-testid="mind-context-summary"`.
**Why:** a `Feature` is accepted by the owner in production and needs an e2e journey, and
a summary that only ever reaches a system prompt has neither — the owner would have to
believe us. The Mind panel exists to show what Maggie is holding in mind, and a thread's
summary is the most direct answer to that; it is also what makes the journey able to
assert the summary without reading the model's input.

### One setting for the fast model, used by both Haiku calls

**Options:** keep `"claude-haiku-4-5-20251001"` written in the summarizer too / a
`anthropic_fast_model` setting both the context router and the summarizer read.
**Choice:** the setting.
**Why:** the literal was already in `streaming.py` three times (the call, and the two
metric labels). A second caller makes it five, in two files, with the pricing table in
`metrics.py` keyed on it — a model rename would have to find all of them.

## Risks

- **The fake LLM has no scenario for the summarizer.** Then the stored summary becomes
  `[fake-llm] aucun scénario…` and gets injected into every later system prompt in the
  e2e run. Two fixtures ship with this ticket: one for the summarizer's call, one that
  only matches once the summary is in the system prompt, which is how the journey proves
  the injection.
- **A failed summary must not break a conversation.** Everything the summarizer does is
  inside its own try/except and logged as a warning: the stream has already been sent to
  the user by the time it runs.

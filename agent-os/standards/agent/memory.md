# Agent Memory

The design is ADR-010 (Linear, *Registre des décisions*), background in *Étude — la mémoire
d'un assistant*. This file is the operational contract: what the code must hold true. Change
the ADR first, then this file.

## One unit, one place

Maggie's long-term memory is **a note** — nothing else. A note is a Markdown document she
writes, titles, tags, links, rewrites and archives herself. Episodic recall (the daily
journal) is a note tagged `journal` with its date in the title, not a second mechanism.

**No business schema.** No `Person`, `Place` or `Routine` entity, no category enum, no closed
tag list. Tags, links and sources are JSONB on the note. A `grep` over `agent/app/` must find
no enumeration of memory categories — that check belongs in the review of any memory PR.

The five memories and where they already live:

| Memory | Lives in | Owner-visible at |
|---|---|---|
| Working | conversation window, `conversation_context` | the chat thread and its context chips |
| Episodic | `message`, `conversation_context`, `journal` notes | the inspector, the conversation history |
| Semantic | **notes** (`memory_note`) | the inspector (MAG-20) |
| Procedural | skills (Markdown files), `instruction` directives | agent settings, Skills and Directives tabs |
| Prospective | `proaction` | the proaction log (MAG-155) and settings |

## Storage

Postgres (`maggie_agent`) is the **store**; Markdown is the **format**. Two tables:

- `memory_note` — `id`, `user_id`, `title`, `summary` (one line, feeds the index), `body`
  (Markdown), `tags` (JSONB), `links` (JSONB, note ids), `sources` (JSONB), `importance`
  (1–5, set by Maggie), `status` (`active` | `archived`), `pinned` (owner only),
  `created_at`, `updated_at`, `confirmed_at`, `last_used_at`, `use_count`, `archived_at`,
  `archive_reason`, `forget_after`.
- `memory_event` — the write journal: `note_id`, `at`, `actor` (`maggie` | `encoder` |
  `consolidation` | `owner`), `action`, `reason` (one line), `previous_body`, `source`.
  Version history *is* the events that carry a `previous_body`.

Every read and write is filtered by `user_id`, with the two-user isolation test every agent
repository ships (MAG-108). No filesystem store: the agent pod has no persistent volume, and
files give no transaction between a chat turn and the nightly consolidation.

## One write door

Every write — Maggie mid-turn, the background encoder, the nightly consolidation, the owner
in the inspector — goes through the same service. It is the only place that stamps the
source, appends the `memory_event`, and publishes on Mercure. A repository write that
bypasses it is a bug, not a shortcut.

## Tools

Five, no more:

- `read_notes(ids)` — open full notes; touches `last_used_at` and `use_count`
- `search_memory(query)` — full text, accent- and typo-tolerant (`tsvector` french,
  `unaccent`, `pg_trgm`); active notes only unless asked otherwise
- `write_note(...)` — create or rewrite; the skill requires a search first, so a rewrite
  replaces instead of piling up. Merging and splitting are `write_note` + `archive_note`
  with a reason, not extra tools
- `archive_note(id, reason)` — leaves the index and the default search, stays visible
- `forget_note(id)` — real deletion, history included; only on the owner's explicit word

## Recall

- **Always present**: the index — one line per active note (title, tags, one-line summary,
  importance, last used). It gets **its own cache breakpoint**, after the stable prefix
  (personality, skills, tools), so a memory change rewrites that block alone. Below the
  provider's minimum cacheable block size it just stays volatile.
- **Opened on demand**: `read_notes`, then `search_memory` as a fallback.
- The index is **bounded**. Past the configured token budget, keep the highest
  importance × recency and append the count that was left out — and log it. A silently
  truncated index reads as "that is all she knows".
- **Before acting** (slot, reminder, event, proaction) Maggie opens the notes touching the
  people, places and moments in play. That rule lives in the memory skill, never in code.

## Encoding

- **Mid-turn**, Maggie writes when the owner asks her to remember, corrects her, or says
  something plainly durable — and says so in half a sentence.
- **In the background**, a cheap model proposes note changes against the existing notes
  (compare, then add / rewrite / archive / do nothing). It runs per conversation context,
  debounced after silence and always on context close — not once per turn.
- The background encoder proposes; it writes through the same door, with the source message
  on every change, and every change shows in the inspector. Nothing lands untraceable.

## Consolidation and forgetting

A silent nightly proaction — Maggie's sleep. It rereads the notes touched since the last run
and their neighbours (shared tags or links), then merges duplicates, splits catch-alls, dates
what changes with time, resolves contradictions (most recent wins, the old wording stays in
the history with its end date), re-weighs importance, and archives what no longer serves. A
doubt becomes a note tagged `question` to raise at the next natural occasion. Each run leaves
a report.

Forgetting is **archive first** — visible in the inspector, with Maggie's reason, undoable —
then real deletion after a configurable delay (default 90 days). A pinned note is never
forgotten. "Forget that" from the owner deletes at once.

Importance, age, last use and use count are **signals shown to Maggie**, not a decay formula
that decides on its own. No hard-coded rule about what is worth keeping.

## Reliability

- Every note carries its **sources**: origin `dit` (the owner said it) / `observé` (read from
  the app's data) / `déduit` (Maggie inferred it), with the date and the excerpt. `déduit`
  must be announced as such when it is used.
- `confirmed_at` is the last time the information was said again or confirmed.
- **Maggie never answers about what she knows without opening the note.** No note, no claim —
  she says she does not know.
- "What do you know about X?" → search, read, restate with dates and sources. "Why do you
  think that?" → the origin, the date, the excerpt, and the note it came from.

## Ruled out, and why

No embeddings or pgvector (absent from the shared production Postgres, and a vector is not
something the owner can read or correct), no third-party memory framework (Mem0, Letta,
LangMem), no dedicated memory service, no knowledge graph, no invisible summary. Any PR that
reintroduces one argues it against the simpler option it replaces — that is an ADR, not a
commit.

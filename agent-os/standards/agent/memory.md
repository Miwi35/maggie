# Agent Memory

The design is ADR-010 (Linear, *Registre des décisions*), background in *Étude — la mémoire
d'un assistant*. This file is the operational contract: what the code must hold true.

**ADR-010 is still *Proposée*.** Nothing here is implemented until the owner accepts it and
the ADR's status line says *Acceptée* — MAG-163 carries that validation. The rules below
describe the target; the current `memory` table, its `MemoryType` enum and its four tools
(`store_memory`, `search_memory`, `update_memory`, `delete_memory`) are what MAG-17 replaces.
Change the ADR first, then this file.

## One unit, one place

Maggie's long-term memory is **a note** — nothing else. A note is a Markdown document she
writes, titles, tags, links, rewrites and archives herself. Episodic recall (the daily
journal) is a note tagged `journal` with its date in the title, not a second mechanism.

**No business schema.** No `Person`, `Place` or `Routine` entity, no category enum, no closed
tag list. Tags, links and sources are JSONB on the note. From MAG-17 on, a `grep` over
`agent/app/` must find no enumeration of memory categories — that check belongs in the review
of any memory PR.

The five memories and where they already live:

| Memory | Lives in | Owner-visible at |
|---|---|---|
| Working | conversation window, `conversation_context` | the chat thread and its context chips |
| Episodic | `agent_message`, `conversation_context`, `journal` notes | the inspector, the conversation history |
| Semantic | **notes** (`memory_note`) | the inspector (MAG-20) |
| Procedural | skills and `instruction` directives, in the database like notes (MAG-187) | agent settings, Skills and Directives tabs, and the mirror |
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

**The agent has no migration tool.** Its schema comes from `AgentBase.metadata.create_all`,
which creates missing tables and never alters an existing one or installs an extension.
MAG-17 settles that before adding a column anywhere: either it brings a migration tool in, or
it writes down how the schema evolves. Do not assume `agent/migrations/` exists.

Every read and write is filtered by `user_id`, with the two-user isolation test every agent
repository ships (MAG-108).

**Why not a folder of `.md` files on object storage.** Durability is not the reason — an
S3 bucket solves that, and its versioning would give history for free. The reason is that
this memory is not a pile of documents but a pile of documents with **living metadata**:
importance, last used, use count, forget-after, read and written constantly to build the
index and to pick what the nightly consolidation rereads. Object storage offers no index and
no query — `ListObjectsV2` returns keys and dates, not metadata — and the freshness "touch"
on every read means one PUT, hence one bucket version, per consultation: the real edit
history drowns in it. Second reason: an unreachable bucket would leave Maggie amnesiac,
where an unreachable mirror only leaves the mirror stale.

## The mirror

The memory is **also** written out as a folder of `.md` files on an S3-compatible bucket:
one file per note (metadata and sources in YAML frontmatter), a `SOMMAIRE.md`, archived
notes under `archive/`, skills under `competences/`, one prefix per user. That is the
readable, greppable, versioned copy the owner asked for — outside the cluster and outside
the app.

It is **one-way and off the response path**: written after the nightly consolidation and on
demand from the inspector, never read back, and a bucket failure is logged and shown but
blocks nothing. Only notes changed since the last pass are rewritten, so the bucket does not
collect a version per note per night. MAG-195 carries it, including the bucket, keys and
region the owner provisions himself; it stays disabled (`MEMORY_MIRROR_ENABLED=false`) in dev
and e2e, so no MinIO joins the fourteen services already in that stack.

## One write door

Every write — Maggie mid-turn, the background encoder, the nightly consolidation, the owner
in the inspector — goes through the same service. It is the only place that stamps the
source, appends the `memory_event`, and publishes on Mercure. A repository write that
bypasses it is a bug, not a shortcut.

Publishing means a new `memory` stream in `agent/app/mercure/topics.py` (`STREAMS`) and the
regenerated `agent/contract/mercure-topics.json` — the admin contract test goes red until
both land, on purpose. The third step is covered by nothing: `AGENT_TOPICS` in the API's
`MercureSubscriberTokenFactory` is a hardcoded list, and forgetting `memory` there publishes
updates no token can ever receive. MAG-17 adds it and extends
`MercureSubscriberTokenFactoryTest`.

## Tools

Five, no more:

- `read_notes(ids)` — open full notes; touches `last_used_at` and `use_count`
- `search_memory(query)` — full text; active notes only unless asked otherwise
- `write_note(...)` — create or rewrite; the skill requires a search first, so a rewrite
  replaces instead of piling up. Merging and splitting are `write_note` + `archive_note`
  with a reason, not extra tools
- `archive_note(id, reason)` — leaves the index and the default search, stays visible
- `forget_note(id)` — real deletion, history included; only on the owner's explicit word

`search_memory` keeps its name with new semantics, and the others replace tools the system
prompt still names: `agent/app/personality/default.yaml` tells Maggie to call `store_memory`,
so MAG-17 rewrites that paragraph too, or the prompt advertises a tool that no longer exists.

Search starts on `to_tsvector('french', …)`. The only precedent is a dead one — the GIN index
in `api/migrations/Version20260215161926.php`, dropped by `Version20260219140000.php` when
memory moved to the agent — so there is no working example on `maggie_agent` to copy. Accent
and typo tolerance wants `unaccent` and `pg_trgm`, which nothing here installs; `maggie_agent`
is owned by the role the agent connects as, and both are trusted extensions, so this should
work — MAG-16 confirms it on the shared production Postgres and says so in the ticket rather
than assuming either way.

## Recall

- **Always present**: the index — one line per active note (title, tags, one-line summary,
  importance, last used). It is a system block of its own, sitting between the cached stable
  block (personality, skills) and the volatile one, with its own cache breakpoint, so a
  memory change rewrites that block alone — see `build_system` and `cache_tools` in
  `agent/app/llm/prompt_cache.py`. Below the provider's minimum cacheable block size it just
  stays volatile.
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

## The memory skill is not durable yet

The two rules that carry recall — search before writing, consult before acting — live in a
skill so they can change without a deploy. But skills are files under `/app/data/skills`
(`agent/app/skills/index.py`) and the agent pod mounts no volume there: they vanish on every
deploy. **MAG-187 fixes that, and MAG-17 is blocked by it** — otherwise the behaviour this
file describes silently disappears at the first redeploy after it ships.

Skills move to the **same store as the notes**, and appear in the mirror alongside them.
They would have suited the bucket well — they have no living metadata, so the argument above
does not apply to them — but one store means one backup, one isolation rule, one inspector
and no new dependency, and a bucket unreachable at boot would strip Maggie of everything
procedural.

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

No embeddings or pgvector (a vector is not something the owner can read or correct, and
pgvector has to be confirmed present on the shared Postgres before it is even an option), no
third-party memory framework (Mem0, Letta, LangMem), no dedicated memory service, no
knowledge graph, no invisible summary — and not Anthropic's own `memory_20250818` tool
either: it imposes a file semantics, leaves the storage to us anyway, and carries neither
sources nor a write journal. Keeping our own tools on the Messages API is ADR-001. Any PR
that reintroduces one of these argues it against the simpler option it replaces — that is an
ADR, not a commit.

Object storage is **not** on that list — it is the mirror. And if the owner ends up reading
the mirror more than the inspector, a folder of `.md` files becomes the right primary store
after all, and that is a new ADR, not a drift.

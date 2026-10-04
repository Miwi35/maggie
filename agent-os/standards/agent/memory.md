# Agent Memory

The design is ADR-010 (Linear, *Registre des décisions*), background in *Étude — la mémoire
d'un assistant*. This file is the operational contract: what the code must hold true.

**ADR-010 is still *Proposée*.** Nothing here is implemented until the owner accepts it and
the ADR's status line says *Acceptée* — MAG-163 carries that validation. The rules below
describe the target; the current `memory` table, its `MemoryType` enum and its four tools
(`store_memory`, `search_memory`, `update_memory`, `delete_memory`) are what MAG-17 replaces.
MAG-195 comes first and builds the storage layer this file assumes: bucket client, frontmatter,
reconciler, outbox, conflicts. Change the ADR first, then this file.

## One unit, one place

Maggie's long-term memory is **a note** — nothing else. A note is a Markdown document she
writes, titles, tags, links, rewrites and archives herself. Episodic recall (the daily
journal) is a note tagged `journal` with its date in the title, not a second mechanism.

**The note is a file, and the file is the truth.** It lives as `<title>.md` on an
S3-compatible bucket. The owner may edit it with any tool he likes and Maggie picks the change
up; Postgres holds a **derived index** that can be dropped and rebuilt from the bucket at any
time. Writing to the index without writing the file is a bug — the next reconciliation
overwrites it.

**No business schema.** No `Person`, `Place` or `Routine` entity, no category enum, no closed
tag list. Tags, links and sources are JSONB on the note. From MAG-17 on, a `grep` over
`agent/app/` must find no enumeration of memory categories — that check belongs in the review
of any memory PR.

The five memories and where they already live:

| Memory | Lives in | Owner-visible at |
|---|---|---|
| Working | conversation window, `conversation_context` | the chat thread and its context chips |
| Episodic | `agent_message`, `conversation_context`, `journal` notes | the inspector, the conversation history |
| Semantic | **the bucket's `.md` files**, indexed in `memory_note` | the inspector (MAG-20), or the file itself |
| Procedural | skills in the `skill` table (MAG-187, shipped), `instruction` directives; skills move to the bucket in MAG-218 | agent settings, Skills and Directives tabs, then the file itself |
| Prospective | `proaction` | the proaction log (MAG-155) and settings |

## The bucket holds the truth

An S3-compatible bucket, **mandatory**, one prefix per user:

```
<user>/<title>.md          one note, frontmatter + Markdown body
<user>/archive/<title>.md  archived — archiving is moving the file
<user>/conflits/…          Maggie's version of a note the owner edited first
<user>/competences/…       skills, once MAG-218 lands
<user>/SOMMAIRE.md         generated; its header says not to edit it
```

Frontmatter carries `title`, `summary`, `tags`, `links`, `sources`, `pinned`, `importance`,
`confirmed_at`, `forget_after`. Forgetting a note is deleting the object.

**The rule that removes the ambiguity: if it is in the file it counts, and what is not in the
file is a counter.** No field written in a file is ever ignored — otherwise the owner edits
something and nothing happens, which is the trap this design exists to close.

## What Postgres keeps

- `memory_note` — the **derived index**, rebuildable from the bucket at any time: every
  frontmatter field, plus `path`, `etag`, `body_hash`, and the body (kept for full-text search
  and for degraded mode). Dropping this table and resyncing must lose nothing; that is the
  disaster-recovery story and it deserves a test.
- The two fields that must **never** appear in a file, because they change on every read and
  would otherwise write one bucket version per consultation: `last_used_at`, `use_count`.
- `memory_event` — the write journal: `note_id`, `at`, `actor` (`maggie` | `encoder` |
  `consolidation` | `owner`), `action`, `reason` (one line), `previous_body`, `source`.
- `memory_outbox` — writes not yet pushed to the bucket.

Every read and write is filtered by `user_id`, with the two-user isolation test every agent
repository ships (MAG-108). **The agent has no migration tool**: its schema comes from
`AgentBase.metadata.create_all`, which creates missing tables and never alters an existing one
or installs an extension. MAG-195 settles that before adding a column anywhere. Do not assume
`agent/migrations/` exists.

## Reconciliation

One `ListObjectsV2` per pass over the user's prefix returns key, date and **ETag** for every
file — 1000 per page, so hundreds of notes in a single request. Compare each ETag with the one
in the index: unknown key → read and index; different ETag → re-read and re-index; in the
index but absent from the listing → drop it from the index, its last body staying in
`memory_event`. A file is fetched **only** when its ETag moved. No user metadata is asked of
the API, because everything is in the file.

Cadence: a background loop every 60 s (configurable), **plus** an opportunistic pass at the
start of a turn when the last one is older than 30 s — so "I edit the file, then I ask" works
at once — plus a button in the inspector, plus always before the nightly consolidation. Bucket
event notifications are out: OVH's S3 compatibility does not guarantee them and they would
need a queue or a public endpoint.

**A mass-deletion guard.** An empty bucket against a full index looks like the owner deleting
everything. A pass that would drop more than half the notes (at least five) stops, deletes
nothing, and says so in the inspector.

## When the bucket does not answer

The bucket is mandatory, but an outage must not cost Maggie her memory.

- **Reads** keep working off the index, which holds every body. It is flagged **stale**, its
  age goes to Maggie in the volatile prompt block and to the inspector, so she can say it if
  asked.
- **Writes** go to `memory_outbox` in the same transaction as the index; the reconciler pushes
  and clears them when the bucket returns, and the note shows as "not yet synchronised". No
  write is lost, nothing diverges in silence.
- **Boot** starts on the index and retries with backoff. Readiness stays green — a restart loop
  would make it worse — but the failure is logged at `critical` and counted in the metrics. An
  outage must not look normal (the Mercure-secret precedent in `global/real-time`).

## Conflicts: the owner wins, nothing is thrown away

Maggie writes with `If-Match` on the ETag she last saw; a 412 is a conflict. OVH's support for
conditional writes **has to be verified** (MAG-195 says which it got), so the fallback is
mandatory: re-read the ETag immediately before writing and compare — a narrow race, not a
proof, hence the preference for `If-Match` where it works.

On conflict the owner's file stays untouched, Maggie's version goes to
`conflits/<title>-<timestamp>.md`, a `memory_event` records it, and a `question`-tagged note
makes her raise it at the next natural occasion. Never a silent last-write-wins. Inside the
agent — one replica, several writers — a per-note lock serialises them, so the only real
conflicts are with the owner.

## One write door

Every write — Maggie mid-turn, the background encoder, the nightly consolidation, the owner
in the inspector — goes through the same service. It is the only place that **writes the file
first** (or queues it in `memory_outbox` when the bucket is down), then updates the index,
stamps the source, appends the `memory_event`, and publishes on Mercure. An index write that
skips the file is a bug, not a shortcut: the next reconciliation overwrites it.

The owner has a **second, perfectly legitimate door**: editing the file in the bucket.
Reconciliation picks it up, stamps a `memory_event` with `actor = owner`, and no code path may
treat it as less authoritative than a write of its own.

Publishing means a new `memory` stream in `agent/app/mercure/topics.py` (`STREAMS`) and the
regenerated `agent/contract/mercure-topics.json` — the admin contract test goes red until
both land, on purpose. The third step is covered by nothing: `AGENT_TOPICS` in the API's
`MercureSubscriberTokenFactory` is a hardcoded list, and forgetting `memory` there publishes
updates no token can ever receive. MAG-17 adds it and extends
`MercureSubscriberTokenFactoryTest`.

## Tools

Five, no more:

- `read_notes(ids)` — open full notes from the index; touches `last_used_at` and `use_count`,
  the two fields that stay out of the file precisely so this costs no bucket version
- `search_memory(query)` — full text over the index; active notes only unless asked otherwise
- `write_note(...)` — create or rewrite; **writes the file**, then the index. The skill requires
  a search first, so a rewrite replaces instead of piling up. Merging and splitting are
  `write_note` + `archive_note` with a reason, not extra tools
- `archive_note(id, reason)` — **moves the file under `archive/`**; leaves the index and the
  default search, stays visible
- `forget_note(id)` — **deletes the object**, history included; only on the owner's explicit word

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

Careful with the word *index*: below, **the summary** is the listing that goes in the prompt —
the same content the generated `SOMMAIRE.md` shows. The *index* is the `memory_note` table.

- **Always present**: the summary — one line per active note (title, tags, one-line summary,
  importance, last used). It is a system block of its own, sitting between the cached stable
  block (personality, skills) and the volatile one, with its own cache breakpoint, so a
  memory change rewrites that block alone — see `build_system` and `cache_tools` in
  `agent/app/llm/prompt_cache.py`. Below the provider's minimum cacheable block size it just
  stays volatile.
- **Opened on demand**: `read_notes`, then `search_memory` as a fallback.
- The summary is **bounded**. Past the configured token budget, keep the highest
  importance × recency and append the count that was left out — and log it. A silently
  truncated summary reads as "that is all she knows".
- When the index is stale (no successful reconciliation for longer than the threshold), the
  volatile block says so with its age, so Maggie can tell the owner instead of answering as if
  everything were current.
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

## Skills, and the two shipped fixes

The two rules that carry recall — search before writing, consult before acting — live in a
skill so they can change without a deploy. Skills used to be files under `/app/data/skills`
with no volume mounted, so they vanished on every deploy; **MAG-187 shipped** and they now live
in the `skill` table of `maggie_agent`, with `skill_index.rebuild()` reloading from it at
startup. **MAG-188 shipped** too: the pre-deploy `pg_dump` covers both databases, so the index
and the counters are backed up like the rest.

Skills follow the notes to the bucket in **MAG-218** — the argument is stronger for them than
for notes, since a skill is a document the owner wants to open and has no read counters at all.
That ticket reuses this reconciler rather than inventing a second one; `render_markdown` and
`parse_markdown` already exist in `agent/app/skills/index.py`. Directives (`instruction`) stay
in the database: they are sentences, not documents.

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

**One brick does come in**, deliberately: the object storage is a mandatory dependency of the
agent — `aioboto3`, a secret, a bucket the owner provisions. That is the price of the decisive
criterion; without a file that counts, the memory stays something only the app can open. It
adds **no service to the e2e stack**: the bucket is faked in-process behind the same port as
the real client, so reconciliation, conflicts and degraded mode are all covered by the journeys.

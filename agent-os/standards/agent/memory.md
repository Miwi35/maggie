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

**The note is a file, and the file is the truth.** It lives as a `.md` object on an
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

An S3-compatible bucket with **versioning on**, mandatory. Layout:

```
<user>/<slug>.md           one note, frontmatter + Markdown body
<user>/archive/<slug>.md   archived — archiving is moving the file
<user>/conflits/…          Maggie's version of a note the owner edited first
<user>/SOMMAIRE.md         generated; its header says not to edit it
competences/…              skills, once MAG-218 lands — see below
```

`competences/` sits **outside** `<user>/` on purpose: skills are global, shared by every user
and carry no `user_id` (`agent/architecture` in these standards, MAG-108). Putting them under a
user prefix would silently make them per-user.

**The note reconciler owns `<user>/*.md` and `<user>/archive/*.md`, and nothing else.**
`conflits/`, `SOMMAIRE.md`, `competences/` and any unknown prefix are skipped — otherwise
Maggie's own conflict copies and the generated summary would come back as notes.

Frontmatter: `id` (ULID), `title`, `summary`, `tags`, `links` (note ids), `sources`, `pinned`,
`importance`, `confirmed_at`, `forget_after`, `created_at`, and `archived_at` + `archive_reason`
once archived. `status` is read from the prefix. `updated_at` is the object's `LastModified`.

**`id` is the identity, not the key.** The reconciler matches on the frontmatter `id`, so
renaming the file, retitling the note or moving it under `archive/` are **updates** that keep
the counters and the history — a key-only reconciler would read all three as delete + create.
The key is a slug of the title (accents folded, `/` and control characters stripped, bounded
length) because the owner has to recognise his files; on a key collision the writer appends
`-2`. If two files claim the same `id` — the owner copied one — the most recently modified keeps
it, the other is given a fresh `id` and reported in the inspector.

**The rule that removes the ambiguity: if it is in the file it counts, and what is not in the
file is a counter.** No field written in a file is ever ignored — otherwise the owner edits
something and nothing happens, which is the trap this design exists to close.

## What Postgres keeps

- `memory_note` — the **derived index**, rebuildable from the bucket: every frontmatter field,
  plus `path`, `etag`, `body_hash`, and the body (kept for full-text search and degraded mode).
- The two fields that must **never** appear in a file, because they change on every read and
  would otherwise write a bucket version per consultation: `last_used_at`, `use_count`. They are
  the **documented exception** to the rebuild promise: a rebuild from an empty index cannot
  restore them and resets them (`use_count` 0, `last_used_at` null). Everything else a rebuild
  restores exactly, and that deserves a test.
- `memory_event` — the write journal: `note_id` (the ULID), `at`, `actor` (`maggie` | `encoder` |
  `consolidation` | `owner`), `action`, `reason` (one line), `previous_body`, `source`.
- `memory_outbox` — writes not yet pushed to the bucket.

Removing a note from the index is a **soft delete**: flag the row, keep the counters and the
history. A hard delete would destroy the only copy of data the bucket cannot give back.

Every read and write is filtered by `user_id`, with the two-user isolation test every agent
repository ships (MAG-108). **The agent has no migration tool**: its schema comes from
`AgentBase.metadata.create_all`, which creates missing tables and never alters an existing one
or installs an extension. MAG-195 settles that before adding a column anywhere. Do not assume
`agent/migrations/` exists.

## Reconciliation

One `ListObjectsV2` per pass over the owned prefixes returns key, `LastModified` and **ETag** for
every file — 1000 per page, so hundreds of notes in a single request, and no user metadata is
needed because everything is in the file. Compare each ETag with the index: unknown → read and
index; changed → re-read and re-index; a file is fetched **only** when its ETag moved.

Two rules keep that from destroying anything:

- **Deletions are applied only when every page of the pass succeeded.** A failed page, a wrong
  prefix or credentials pointing at another bucket all look like "every key is absent".
- **An ETag the write door just wrote is not an owner edit.** The door records the ETag the PUT
  returned as part of the same unit of work; without that, Maggie's own fresh object looks
  exactly like an owner edit, gets re-indexed with `actor = owner`, and raises a conflict against
  herself.

Cadence: a background loop every 60 s (configurable) — same shape as `_execution_loop` in
`agent/app/queue/scheduler.py`, and note that `start_scheduler()` sits inside the RabbitMQ
`try/except` in `agent/app/main.py`: the reconciler must not die with RabbitMQ. **Plus** an
opportunistic pass at the start of a turn when the last one is older than 30 s, so "I edit the
file, then I ask" works at once. That pass is the only time the bucket touches the response
path, so it gets a **hard timeout**: past it the turn proceeds on the index, flagged stale. It
hooks where the memory context is already built — `agent/app/llm/streaming.py` and
`agent/app/llm/gateway.py`. Plus a button in the inspector, plus always before the nightly
consolidation. Bucket event notifications are out: OVH's S3 compatibility does not guarantee
them and they would need a queue or a public endpoint.

**A mass-deletion guard, as a second line of defence.** A pass that would remove more than half
the notes (at least five) stops, removes nothing, and says so in the inspector. It is not the
first line — that is the all-pages-succeeded rule above, because the guard misses four notes out
of four.

**Reconciliation samples states, it does not journal them.** Several owner edits between two
passes collapse into one; the intermediate versions are recoverable from the bucket's versioning,
not from `memory_event`. That is why versioning is required rather than nice to have.

## When the bucket does not answer

The bucket is mandatory, but an outage must not cost Maggie her memory.

- **Reads** keep working off the index, which holds every body. It is flagged **stale**, its age
  goes to Maggie in the volatile prompt block and to the inspector, so she can say it if asked.
- **Writes** go to `memory_outbox` in the same transaction as the index; the reconciler pushes
  them when the bucket returns, **through the conflict path** — the owner may have edited the
  file during the outage. A PUT that timed out may still have landed, so a retry compares the
  current ETag before concluding anything, or it would conflict with Maggie's own write.
- **Boot** starts on the index and retries with backoff. Readiness stays green — a restart loop
  would make it worse — but the failure is logged at `critical` and counted in the metrics, the
  way `MercurePublishMiddleware` logs an unsignable secret. An outage must not look normal.

## Conflicts: the owner wins, nothing is thrown away

Maggie rewrites with `If-Match` on the ETag she last saw, and **creates** with
`If-None-Match: *` — without the latter, two writers creating the same title both succeed and one
is lost. A 412 is a conflict. OVH's support for conditional writes **has to be verified**
(MAG-195 says which it got), so the fallback is mandatory: re-read the ETag immediately before
writing and compare — a narrow race, not a proof, hence the preference for the headers where
they work.

On conflict the owner's file stays untouched, Maggie's version goes to
`conflits/<slug>-<timestamp>.md`, a `memory_event` records it, and a `question`-tagged note makes
her raise it at the next natural occasion. Never a silent last-write-wins. Inside the agent —
one replica, several writers — a per-`id` lock serialises them, so the only real conflicts are
with the owner.
## One write door

Every write — Maggie mid-turn, the background encoder, the nightly consolidation, the owner
in the inspector — goes through the same service. It is the only place that **writes the file
first** (or queues it in `memory_outbox` when the bucket is down), then updates the index,
stamps the source, appends the `memory_event`, and publishes on Mercure. An index write that
skips the file is a bug, not a shortcut: the next reconciliation overwrites it.

The owner has a **second, perfectly legitimate door**: editing the file in the bucket.
Reconciliation picks it up, stamps a `memory_event` with `actor = owner`, **publishes on Mercure
like any other write** — otherwise the inspector would not refresh on an owner edit — and no code
path may treat it as less authoritative than a write of its own.

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
- `archive_note(id, reason)` — **moves the file under `archive/`** and writes `archived_at` and
  `archive_reason` into its frontmatter; leaves the summary and the default search, stays visible
- `forget_note(id)` — **deletes every version of the object**, history included; only on the
  owner's explicit word. Versioning is on, so a plain delete would leave a delete marker and keep
  the content where neither the inspector nor `memory_event` can reach it — that is not forgetting

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

Careful with the word *index*: below, **the summary** is the listing that goes in the prompt. The
*index* is the `memory_note` table. The generated `SOMMAIRE.md` is the same listing **minus the
counters** — writing *last used* into a bucket object would cost a version per consultation,
which is the whole reason those two fields stay out of the files. It is regenerated when a note
changes (debounced) and after the nightly consolidation, not on every read.

- **Always present**: the summary — one line per active note (title, tags, one-line summary,
  importance, last used). It is a system block of its own, sitting between the cached stable
  block (personality, skills) and the volatile one, with its own cache breakpoint, so a
  memory change rewrites that block alone. `build_system(stable, volatile)` in
  `agent/app/llm/prompt_cache.py` has no third block today: MAG-16 changes that signature and its
  two call sites (`agent/app/llm/gateway.py`, `agent/app/llm/streaming.py`). Below the provider's
  minimum cacheable block size the summary just stays volatile.
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
That ticket reuses this reconciler rather than inventing a second one. `render_markdown` already
helps as is; `parse_markdown` takes a `Path` and reads from disk, so it needs a `str`/`bytes`
signature before it can parse a bucket body, and its docstring still mentions the dead "mirror".
Directives (`instruction`) stay in the database: they are sentences, not documents.

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
adds **no service to the e2e stack**: the bucket is faked in-process behind the same interface as
the real client, the way `LLM_PROVIDER=fake` does for the model (`agent/app/llm/fake.py`). The
fake must be able to inject an outage and a 412, or the degraded-mode and conflict journeys
promised here cannot exist.

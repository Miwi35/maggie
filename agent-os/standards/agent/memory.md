# Agent Memory

Design: **ADR-011** (Linear, *Registre des décisions*), accepted 4 Oct 2026, replacing ADR-010.
Needs it answers: *Mémoire de Maggie — besoins*. Background: *Étude — la mémoire d'un assistant*.
This file is the operational contract: what the code must hold true. Change the ADR first.

The point is not an archive to consult. It is that **each thing the owner told Maggie comes back
at the moment it changes something** — "un bouquet pour la fête des mères" has to surface
"Françoise n'aime pas les roses", with no word in common. What does not surface is a bug.

**Never put the owner's health information in this repo or in a ticket**, examples included.

## The memory is a folder of Markdown files

The files are the only truth. The owner edits them with any tool he likes; his version is the one
Maggie uses — see *his corrections win*, below, for what that obliges. Everything else — the
index, the counters — is rebuilt from them.

```
<utilisateur>/Mémoire/
  LISEZMOI.md, AGENTS.md    how to read this folder, for a human and for any agent
  sommaire.md               table of contents, one line per fiche
  journal.md                what Maggie changed, when, why
  Conversations/AAAA/MM/AAAA-MM-JJ HHhMM — <sujet>.md
  <folders Maggie chooses>/…   e.g. Famille/Françoise/Françoise n'aime pas les roses.md
```

**One `Mémoire/` per user, under their own prefix** — that prefix is what the isolation rule below
validates against, and what an archive is cut along. A path must never escape it.

**The filename is the title, so it needs a rule**: fold accents, strip `/`, `:` and control
characters, bound the length, and on collision append `-2`. `sommaire` and `journal` are reserved.
Two fiches may legitimately want the same true sentence; the suffix is what keeps both.

**Identity is an `id` in the carte, not the path.** The night shift renames and moves files, so
links between fiches, `sources` and the index's counters all key on that `id`. Without it a rename
is a delete plus a create: the counters are lost and every reference dangles.

`LISEZMOI.md`, `AGENTS.md`, `sommaire.md` and `journal.md` are **generated and maintained by
Maggie**, and say so in their own first line. The folder is a deliverable in itself: handed to
another agent as a zip, it must be understandable with nothing else.

## Two layers

**Conversations are the raw memories.** Every exchange is kept **whole**, timestamped to the
minute, with its context. Maggie must be able to say "tu me l'as dit le 4 octobre à 18 h 14" and
retell the anecdote in full. **Nothing in a conversation file is ever rewritten** — no
summarising in place, no trimming. Losing the owner's own words loses the *because* that gives a
fact its meaning.

**Fiches are the knowledge**: durable facts, stories, people, habits, regularities. Maggie writes
them from the conversations, names them and files them **for a human reader**:

- the **title is a true sentence**, not a label — `Françoise n'aime pas les roses.md`;
- folders stay **shallow** and follow the main subject; Maggie creates and reorganises them;
- **no category comes from the code** — no enum, no fixed taxonomy, no `Person` entity. Once the
  old table is gone (see *what it replaces*), a `grep` over `agent/app/` must find no list of
  memory categories;
- **every claim cites its source**: the conversation and its time, or the app data it was read
  from, plus its provenance — `dit` (he said it), `observé` (read from the app), `déduit` (Maggie
  inferred it). `déduit` is announced as such whenever it is used;
- information that stops being true is **dated and marked replaced**, never silently erased.

Maggie never answers about what she knows without opening the fiche. No fiche, no claim.

## The carte

Every conversation file and every fiche opens on a **carte** in YAML frontmatter — about
80 tokens, the unit recall works on:

- `id` — a ULID, the file's identity across renames and moves
- `resume` — a few lines, what this is about
- `moments` — the key moments, timestamped
- `personnes` — who is involved
- `evoque` — **the situations and themes it should surface for**: `fleurs, cadeau, fête des
  mères, deuil`. This is what makes recall work without a shared word, and the nightly pass keeps
  enriching it
- `sources` — for a fiche: the conversations and app data behind it, with their provenance

Maggie judges relevance **on the carte alone**, and opens the full text — or just the useful
passage — only when she needs the detail. A conversation that touches several subjects gets
**one carte per subject**.

## Recall, four levels

Only level 2 sits on the **read** path, voice included, so only level 2 needs a timeout there.

**The write path is off the response path too.** A turn writes to the **local copy** only; pushing
to the bucket happens after the answer, never during it. A conversation file is written as the
exchange goes; its carte is written by the night shift. Need 12 — remembering must not slow Maggie
down, voice included — is a rule about both directions, not just recall.

1. **A permanent profile** — the 50 to 100 essential facts, always in the prompt, in the cached
   block. It is generated from the fiches, not hand-written, and **bounded**: past the budget, say
   how many fiches it does not list rather than truncate in silence. What goes in is Maggie's call
   from the signals below, not a score.
2. **An automatic recall every turn** — a fast search over the cartes, **with a relevance
   threshold**, bringing **at most three** memories. It must stay cheap enough for voice: give it
   a hard timeout and continue without it rather than delay the answer. The 5 a.m. daily planning
   (`daily_planning_hour`, `_daily_planning_loop` in `agent/app/queue/scheduler.py`) runs the same
   search over the day's upcoming events, which is what makes the recall *proactive*.
   **Both are measured**: the latency of this level and the size of the injected block go in the
   metrics, or "a memory that slows Maggie down" is only ever noticed in use.
3. **Maggie's own search**, with the associated terms she chooses, when she judges she needs more.
4. **Opening the full text**, only for a detail.

**Two blocks, not one.** The profile is **cached** — its own block, after the stable prefix, so a
fiche changing rewrites it alone and not the personality. The recalled cartes change every turn and
go in the **volatile** block: putting them with the profile would make the profile uncacheable, and
caching is the whole reason the profile can be 100 facts. Below the provider's minimum cacheable
block size the profile simply rides along in the volatile block.

`build_system(stable, volatile)` (`agent/app/llm/prompt_cache.py`) has only two blocks today:
adding the profile changes that signature and its two call sites, `agent/app/llm/gateway.py` and
`agent/app/llm/streaming.py` (both already build `volatile` from `get_memory_context`).

## The index is Maggie's, and throwaway

A Postgres table in `maggie_agent`, **rebuilt from the files** at any time: the cartes, the evoked
terms, aliases, links between fiches (by `id`), usage counters, French full-text search — **and the
file bodies**, which is what lets levels 1 to 3 keep working when the bucket does not answer.

It **holds no truth of its own**: every row is derived from a file and reproducible from it. That is
the distinction that matters, not whether it stores text. What need 9 means is that the owner never
has to know the index exists — not that it must stay thin.

- **Usage counters are never written into the files.** They change on every read; in a versioned
  bucket they would bury the real corrections.
- A **reindex in place** keeps them: it refreshes derived rows and leaves the counters alone.
  **Dropping the table** loses them, and nothing else — that is the recovery story, and it deserves
  a test. Say which of the two any given command does.
- **Full-text search starts at `to_tsvector('french', …)`.** Accent and typo tolerance wants
  `unaccent` and `pg_trgm`, which nothing here installs and whose availability on the shared
  Postgres has to be checked rather than assumed. Until then the `evoque` terms and the aliases
  carry that slack — and a miss on a misspelling is a case for the learning loop, not a surprise.
- **Embeddings are an option, not a starting point**: only if misses persist after the correction
  loop below, and then in-process in the agent — no pgvector, no vector service.

Two things live in Postgres and are **not** part of the rebuildable index, because they are
Maggie's mechanics rather than the owner's knowledge: the usage counters, and the **missed-recall
cases** below. They survive a reindex, and they stay out of the files on purpose (see *what stays
out*).

**Schema changes** follow the convention already in the repo: the agent has no migration tool, so
`context_repo.run_migrations()` (called at boot from `agent/app/main.py`) *is* the migration
history — `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, idempotent, every boot. `create_all` only ever
creates whole tables, and only for a model imported in `agent/app/main.py` with the
`# noqa: F401 — register model with AgentBase before create_all` pattern; forget the import and the
table is silently missing.

## The night shift

Off the response path, in batch, every night. Maggie:

- summarises the day's conversations and **writes their cartes** (the conversation text itself
  stays untouched);
- creates and updates the **fiches**, files and renames them, **merges duplicates and splits
  catch-alls** — need 8 wants one fiche per subject, surfacing in every situation it touches;
- resolves contradictions **between two things the owner said** — the most recent wins, the old
  wording stays, dated and marked replaced. That rule never applies against an edit of his: a
  later rewrite by Maggie does not beat his correction;
- surfaces **regularities with their proof** ("deux vols ratés avant 7 h" — and the two
  conversations that say so);
- **enriches `evoque`** on the fiches that need it;
- flags the orphans: a fiche with no source, or one nothing points at;
- **lets what was circumstantial fade.** A dentist appointment last spring and a passing craving
  stop being worth surfacing; a person, a lasting preference, a recurring constraint do not. Maggie
  is **shown the signals** — age, last use, usage count, how often the fact was said again — and
  **she** decides, with her reason in the journal. No decay formula decides for her, and **the file
  is never touched**: fading means leaving the permanent profile and the active fiches, not being
  deleted. Need 4 asks for a memory that is kept up; need 15 forbids losing anything;
- keeps **`sommaire.md`** and **`journal.md`** up to date, and leaves a **report per pass** — what
  it filed, settled, faded and flagged — in the journal and in the proaction log (MAG-155).

Nothing disappears in silence: every change lands in `journal.md` with its reason. **A doubt is not
resolved alone**: when the night shift cannot settle one, it becomes a question Maggie raises at the
next natural occasion, the same way a set-aside correction does.

## Learning from its own misses

Every "tu aurais dû t'en souvenir" is **recorded as a case**, with what the owner asked and what
should have surfaced. At night Maggie works out why the fiche did not come up, adds the missing
associations to its `evoque`, and sweeps the fiches at risk of the same miss. **Each case is
replayed every night** from then on, so a fixed miss cannot come back unnoticed.

This is the only tuning loop: no hand-set weights, no thresholds for the owner to discover. The
cases live in Postgres beside the counters, not in the files — they are Maggie's mechanics, and
need 14 keeps the archive to the owner's knowledge alone.

## His corrections win

A push of Maggie's must never silently overwrite an edit of his, and "most recent wins" does not
arbitrate between them. So: **compare before pushing.** If the remote file changed since the copy
was taken, his version stays, Maggie's is set aside, and she raises it at the next natural
occasion. The journal records both. Everything else is a silent loss of a correction, which is the
one thing this design exists to prevent.

## Storage

**An S3-compatible bucket, with versioning on** — and nothing beyond the S3 protocol. The
endpoint, the region and the keys are `MEMORY_*` settings, and **which provider hosts it belongs in
the provisioning ticket, never in the repo**: naming one here is how a setting quietly becomes a
dependency. Hosting it away from the server puts the memory outside the cluster, so it survives
deploys with its history.

The agent works on a **local copy** of the folder and syncs it with the bucket; the copy is a
cache, re-hydrated from the bucket at boot, since the agent pod has no persistent volume.

**What an outage guarantees, precisely**, because the local copy is not durable and a restart
during an outage leaves none: the index keeps each carte and the full text it searches, so levels
1 to 3 of recall keep working from Postgres alone — only level 4, opening a file, is unavailable,
and Maggie says so instead of inventing. Writes are queued until the bucket is back. An outage is
logged at `critical` and counted; it must never look normal.

**Going back has to be reachable**, not merely possible: need 15 asks for it, so versioning being on
is half an answer. The owner must be able to see a file's previous versions and restore one — from
the web view (MAG-20) as well as from the bucket — and a deletion must not leave content in a
version he cannot get to. A delete that only hides is not a delete.

The owner provisions the bucket and a key pair limited to it himself; the settings are `MEMORY_*`
in `maggie-env`. Nothing is created by the agent.

## Isolation, and what stays out of the files

One memory per user, no leak between them, with the two-user isolation test every agent repository
ships — the rule is in `agent/architecture`, *Data ownership*. Per-user paths are validated: no
title may escape its own prefix.

The files hold **only what is true of the owner**. Maggie's personality, the conversation state, the
usage counters and the missed-recall cases stay out of them, so that an archive handed to another
agent carries the knowledge and nothing else. The one exception is the `id` in a carte: it is
index mechanics, and it is allowed in because identity has to survive a rename — another agent
reading the archive can ignore it, which is the test for whether a mechanical field may stay.

The skills the owner teaches follow the same rules — visible, correctable, not lost — in **their own
prefix, outside any user's folder**, since they are global and carry no `user_id` until MAG-108.
Putting them under a user would make them personal in silence. MAG-187 shipped them into the `skill`
table; MAG-218 moves them to files.

## What it replaces

None of this is live yet, and the old mechanism is still wired in. The implementation retires, in
one go: the `memory` table and its `MemoryType` enum (`agent/app/db/memory_model.py`), the four
tools `store_memory` / `search_memory` / `update_memory` / `delete_memory`
(`agent/app/llm/tools.py`), the injection of every factual row into the volatile block
(`agent/app/memory/agent_memory.py`), and the MÉMOIRE paragraph of
`agent/app/personality/default.yaml`, which still tells Maggie to call `store_memory`. Leaving that
paragraph is how the prompt ends up advertising a tool that no longer exists.

The rows already stored become fiches, and the message history already in Postgres becomes
conversation files. What has lost its source is written as having lost it, not dropped.

## Ruled out

No vector database, no graph database, no dedicated memory service, no third-party memory
framework (Mem0, Letta, LangMem), no invisible summary, and no extraction that throws the
owner's words away. Not the provider's own memory tool either: it imposes a file semantics, leaves
the storage to us anyway, and carries neither sources nor provenance — our own tool loop on the
Messages API is ADR-001. The one dependency that does come in is the S3 bucket, and it is what
makes the memory portable. Reintroducing any of the rest takes a new ADR, not a commit.

**No Mercure stream, for now.** Nothing watches the memory live until the web view needs it
(MAG-20); when it does, the stream, `agent/contract/mercure-topics.json` and the hardcoded
`AGENT_TOPICS` in the API's `MercureSubscriberTokenFactory` all land together, or updates are
published that no token can receive. Do not add one before.

In the e2e stack the bucket is **faked in-process** behind the same interface as the real client,
the way `LLM_PROVIDER=fake` does for the model (`agent/app/llm/fake.py`) — no service joins the
fourteen already there. The fake must be able to inject an outage, so the degraded path is
covered by a journey rather than by hope.

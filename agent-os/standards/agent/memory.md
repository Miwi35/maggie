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
Maggie uses. Everything else — the index, the counters — is rebuilt from them.

```
Mémoire/
  LISEZMOI.md, AGENTS.md    how to read this folder, for a human and for any agent
  sommaire.md               table of contents, one line per fiche
  journal.md                what Maggie changed, when, why
  Conversations/AAAA/MM/AAAA-MM-JJ HHhMM — <sujet>.md
  <folders Maggie chooses>/…   e.g. Famille/Françoise/Françoise n'aime pas les roses.md
```

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
- **no category comes from the code** — no enum, no fixed taxonomy, no `Person` entity. A `grep`
  over `agent/app/` must find no list of memory categories;
- **every claim cites its source**: the conversation and its time, or the app data it was read
  from, plus its provenance — `dit` (he said it), `observé` (read from the app), `déduit` (Maggie
  inferred it). `déduit` is announced as such whenever it is used;
- information that stops being true is **dated and marked replaced**, never silently erased.

Maggie never answers about what she knows without opening the fiche. No fiche, no claim.

## The carte

Every conversation file and every fiche opens on a **carte** in YAML frontmatter — about
80 tokens, the unit recall works on:

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

Only level 2 sits on the response path, voice included, so only level 2 is bounded.

1. **A permanent profile** — the 50 to 100 essential facts, always in the prompt, in the cached
   block. It is generated from the fiches, not hand-written.
2. **An automatic recall every turn** — a fast search over the cartes, **with a relevance
   threshold**, bringing **at most three** memories. It must stay cheap enough for voice: give it
   a hard timeout and continue without it rather than delay the answer. The 5 a.m. daily planning
   (`daily_planning_hour`, `_daily_planning_loop` in `agent/app/queue/scheduler.py`) runs the same
   search over the day's upcoming events, which is what makes the recall *proactive*.
3. **Maggie's own search**, with the associated terms she chooses, when she judges she needs more.
4. **Opening the full text**, only for a detail.

The profile and the recalled cartes are a **system block of their own**, between the cached stable
block and the volatile one, so a memory change rewrites that block alone. `build_system(stable,
volatile)` (`agent/app/llm/prompt_cache.py`) has only two blocks today: adding the third changes
that signature and its two call sites, `agent/app/llm/gateway.py` and
`agent/app/llm/streaming.py` (both already build `volatile` from `get_memory_context`).

## The index is Maggie's, and throwaway

A Postgres table in `maggie_agent`, **rebuilt from the files** at any time: the cartes, the evoked
terms, aliases, links between fiches, usage counters, and French full-text search. It holds no
knowledge of its own — it only points at files. The owner never has to know it exists.

- **Usage counters are never written into the files.** They change on every read; in a versioned
  bucket they would bury the real corrections.
- Dropping the table and reindexing must lose nothing but those counters. That is the recovery
  story, and it deserves a test.
- **Embeddings are an option, not a starting point**: only if misses persist after the correction
  loop below, and then in-process in the agent — no pgvector, no vector service.

**Schema changes** follow the convention already in the repo: the agent has no migration tool, so
`context_repo.run_migrations()` (called at boot from `agent/app/main.py`) *is* the migration
history — `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, idempotent, every boot. `create_all` only ever
creates whole tables.

## The night shift

Off the response path, in batch, every night. Maggie:

- summarises the day's conversations and **writes their cartes** (the conversation text itself
  stays untouched);
- creates and updates the **fiches**, files and renames them;
- resolves contradictions — the most recent wins, the old wording stays, dated and marked
  replaced;
- surfaces **regularities with their proof** ("deux vols ratés avant 7 h" — and the two
  conversations that say so);
- **enriches `evoque`** on the fiches that need it;
- flags the orphans: a fiche with no source, or one nothing points at;
- keeps **`sommaire.md`** and **`journal.md`** up to date.

Nothing disappears in silence: every change lands in `journal.md` with its reason.

## Learning from its own misses

Every "tu aurais dû t'en souvenir" is **recorded as a case**, with what the owner asked and what
should have surfaced. At night Maggie works out why the fiche did not come up, adds the missing
associations to its `evoque`, and sweeps the fiches at risk of the same miss. **Each case is
replayed every night** from then on, so a fixed miss cannot come back unnoticed.

This is the only tuning loop: no hand-set weights, no thresholds for the owner to discover.

## Storage

**An S3-compatible bucket, with versioning on** — and nothing beyond the S3 protocol. The
endpoint, the region and the keys are `MEMORY_*` settings, so the provider is replaceable without
touching a line of code. **Which provider it is belongs in the provisioning ticket, not here, and
not in the code**: naming one in the repo is how a setting quietly becomes a dependency. Hosting
it away from the server puts the memory outside the cluster, so it survives deploys with its
history.

The agent works on a **local copy** of the folder and syncs it with the bucket; the copy is a
cache, re-hydrated from the bucket at boot, since the agent pod has no persistent volume. A
bucket outage must not cost Maggie her memory: she keeps answering from the local copy and the
index, says so when asked, and queues her writes until the bucket is back. An outage is logged at
`critical` and counted — it must never look normal.

The owner provisions the bucket and a key pair limited to it himself; the settings are `MEMORY_*`
in `maggie-env`. Nothing is created by the agent.

## Isolation, and what stays out of the files

One memory per user, no leak between them, with the two-user isolation test every agent
repository ships (MAG-108). Per-user paths are validated: no title may escape its own prefix.

The files hold **only what is true of the owner**. Maggie's personality, the conversation state
and the index stay out of them, so that an archive handed to another agent carries the knowledge
and nothing else. The skills the owner teaches follow the same rules — visible, correctable, not
lost — but in their own folder outside the knowledge archive (MAG-187 shipped them into the
`skill` table; MAG-218 moves them to files).

## Ruled out

No vector database, no graph database, no dedicated memory service, no third-party memory
framework (Mem0, Letta, LangMem), no invisible summary, and no extraction that throws the
owner's words away. The one dependency that does come in is the S3 bucket, and it is what makes
the memory portable. Reintroducing any of the rest takes a new ADR, not a commit.

In the e2e stack the bucket is **faked in-process** behind the same interface as the real client,
the way `LLM_PROVIDER=fake` does for the model (`agent/app/llm/fake.py`) — no service joins the
fourteen already there. The fake must be able to inject an outage, so the degraded path is
covered by a journey rather than by hope.

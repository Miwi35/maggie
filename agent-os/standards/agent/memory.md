# Memory folder (ADR-011)

Maggie's memory is a folder of Markdown files per user, in an S3 bucket. **The files are the only truth**; Postgres holds a disposable index. Detailed spec: Linear « Spec : Mémoire vivante ». Code: `agent/app/memory/`, `agent/app/db/memory_index_*.py`.

## The folder
- Bucket prefix = the user's ULID (`sub` of the JWT): `<ULID>/Mémoire/…`. Every path goes through `app/memory/paths.py` — a name with `/`, `\`, `:`, `..` or a control character never reaches the bucket or the disk. Test every new entry point with `%2e%2e` and a separator.
- Generated files at the root say so on their first line and carry a seal (`<!-- sceau: … -->`): `LISEZMOI.md`, `AGENTS.md`, `sommaire.md`, `journal.md`. Regenerated only while the seal matches — an edit by the owner is kept.
- `Conversations/AAAA/MM/AAAA-MM-JJ HHhMM — sujet.md` are never rewritten. `Conflits/` holds the versions of Maggie set aside. Other folders hold fiches (a carte in YAML frontmatter: `id`, `resume`, `moments`, `personnes`, `evoque`, `alias`, `liens`, `sources`; then text). File name = title; `id` is the identity.

## The two layers (MAG-17)
- **Conversations, kept whole.** `app/memory/conversations.py` appends every message of a thread to its file as it is stored (`MessageRepository.create`, `update_context`, and before any memory tool) — never from a tool, never by the model: what the owner said in passing is on file even if Maggie calls nothing. Each message is headed `## AAAA-MM-JJ HHhMM — rôle`. `MemoryService.write` refuses to rewrite a conversation file (append only).
- **Fiches, as knowledge.** Written by Maggie with `memory_write_fiche` (`app/memory/fiches.py`): the title is a true sentence, folders are hers (at most 3, shallow), the body is two lists the code writes — `## Ce qu'on sait` and `## Ce qui n'est plus vrai`. Each line: `texte — provenance · source[ · heure]`; provenance is `dit`, `observé` or `déduit`. A `dit` claim must cite an existing conversation file **and a minute present in it**; an `observé` one `app : <la donnée>`. A claim that stops being true is moved to the second list with its date and reason, never dropped. `déduit` claims are returned by `memory_open` as `a_annoncer`.
- **No category in code.** Nothing in `agent/app/` enumerates kinds of memory or names an entity such as « Personne »: the structure comes from the folders and titles Maggie chooses. A `grep` for it must stay empty.
- Tools: `memory_search`, `memory_open`, `memory_write_fiche` (`app/memory/tools.py`), all through `MemoryService`. The old `memory` table is read-only, used once by `app/memory/legacy.py` (`task agent:memory:import-legacy`, and on the first turn) to turn rows into fiches under `Ancienne mémoire/` and old messages into conversation files; what has no source says so.
- Nothing about an installed provider appears in `agent/` or `infra/`: S3 client only (`MEMORY_*` settings, documented in `infra/k8s/secrets.example.yaml`). `MEMORY_PROVIDER=fake` is the in-memory bucket with injectable outages (e2e, tests).

## Synchronisation
- Local copy ↔ bucket, both ways, rehydrated at boot (the pod has no persistent disk). **The owner's version always wins**: a file changed in the bucket is pulled; if both sides changed, Maggie's version goes to `Conflits/` and `journal.md` records both.
- Writes: local copy first, then the **outbox** (Postgres table, the only durable queue), pushed after the answer with a compare-before-push (`ETag`). Never `await` the network in a turn.
- Bucket down = degraded mode, not an error: Maggie answers from the local copy and the index, `MemoryService.notice()` tells her so (she says it if asked whether her memory is up to date), writes stay in the outbox, a `critical` log and `maggie_memory_degraded` fire. Back up → the outbox is pushed, nothing lost.

## The index
- Tables in `maggie_agent`: cards, evoked terms, aliases, links by id, bodies of the files (so recall works during an outage), French full-text search (`to_tsvector('french', …)`, GIN index in `run_migrations`). **Usage counters live in their own table, never in the files**, and survive a rebuild.
- Emptying the index loses nothing: `task agent:memory:rebuild` (or `MemoryService.rebuild`) restores everything from the files except the counters.
- Every query is filtered by `user_id`; ship a two-user isolation test (MAG-108).

## Tests
Use `FakeBucket` (`app.memory.bucket`), never a mock of the S3 client for behaviour; `agent/tests/memory/conftest.py` has the fixtures. SQLite stands in for Postgres in unit tests: search is `LIKE` there, FTS is covered by the e2e journey `e2e/web/tests/memory-folder.spec.ts`.

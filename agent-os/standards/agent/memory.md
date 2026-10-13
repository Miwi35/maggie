# Memory folder (ADR-011)

Maggie's memory is a folder of Markdown files per user, in an S3 bucket. **The files are the only truth**; Postgres holds a disposable index. Detailed spec: Linear « Spec : Mémoire vivante ». Code: `agent/app/memory/`, `agent/app/db/memory_index_*.py`.

## The folder
- Bucket prefix = the user's ULID (`sub` of the JWT): `<ULID>/Mémoire/…`. Every path goes through `app/memory/paths.py` — a name with `/`, `\`, `:`, `..` or a control character never reaches the bucket or the disk. Test every new entry point with `%2e%2e` and a separator.
- Generated files at the root say so on their first line and carry a seal (`<!-- sceau: … -->`): `LISEZMOI.md`, `AGENTS.md`, `sommaire.md`, `journal.md`. Regenerated only while the seal matches — an edit by the owner is kept.
- `Conversations/AAAA/MM/AAAA-MM-JJ HHhMM — sujet.md` are never rewritten. `Conflits/` holds the versions of Maggie set aside. Other folders hold fiches (a carte in YAML frontmatter: `id`, `resume`, `moments`, `personnes`, `evoque`, `alias`, `liens`, `sources`; then text). File name = title; `id` is the identity.
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

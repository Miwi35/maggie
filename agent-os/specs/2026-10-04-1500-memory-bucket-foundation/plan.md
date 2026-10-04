# Memory storage foundation — Plan

Ticket: MAG-195 · Contract: `agent-os/standards/agent/memory.md` (ADR-010, PR #61) · Schema rule: `agent-os/standards/agent/architecture.md`

The note file in the bucket is the source of truth; Postgres (`memory_note`) is an index reconciled from it.
Out of scope: note tools (MAG-17), summary and search (MAG-16), inspector (MAG-20), skills (MAG-218).

## Acceptance criteria

1. Layout `<user>/<slug>.md`, `<user>/archive/`, `<user>/conflits/`, generated `<user>/SOMMAIRE.md`; keys are validated against escape; the reconciler reads only `<user>/*.md` and `<user>/archive/*.md`.
2. Identity is the frontmatter ULID: rename, retitle and move to archive are updates; duplicate ids are told apart; a file without id gets one stamped and written back.
3. One ListObjectsV2 per pass, fetch only on ETag change, deletions only when every page was listed, soft deletes, mass-deletion guard (more than half and at least 5).
4. The write door records the PUT's ETag in the same unit of work, so Maggie's own writes are not re-read.
5. Loop every 60 s independent of RabbitMQ; opportunistic pass at turn start when the last one is older than 30 s, bounded by a timeout (`skipped`, distinct from `stale`); a pass always runs before consolidation; manual trigger `POST /agent/memory/sync`.
6. Bucket down: the index is served marked stale with its age in the volatile block, writes go to the outbox in the same transaction, flush compares the current ETag first, boot starts on the index with backoff, critical log + metrics, readiness stays green.
7. Conflicts: `If-Match` / `If-None-Match: *`, fallback re-reading the ETag when the endpoint refuses conditions; the loser goes to `conflits/<slug>-<timestamp>.md` with a `memory_event`; one lock per note.
8. Full rebuild from the bucket (`task agent:memory:rebuild`, `POST /agent/memory/rebuild`) gives back the same index; `last_used_at` / `use_count` survive.
9. Mercure stream `memory` (agent publishes, `agent/contract/mercure-topics.json` regenerated, `AGENT_TOPICS` in the API token factory).
10. In-memory fake bucket behind the same interface (outage, 412); `MEMORY_BUCKET=fake` on the e2e stack.
11. Owner provisions the bucket and the `MEMORY_BUCKET*` secrets in `maggie-env`; `MEMORY_BUCKET` empty keeps the agent on the old `memory` table.

## E2E journey — `e2e/web/tests/memory-bucket.spec.ts` (extends MAG-99, chat)

- **Given** a stack with the simulated bucket and a note the owner wrote in it, **When** he edits the note and Maggie is asked, **Then** the index follows (a `memory` Mercure message arrives) and she answers with the new version.
- **Given** the bucket is down, **When** Maggie is asked and a note is written, **Then** she still answers from the index and says so, and the write is pending.
- **Given** the bucket is back, **When** the next pass runs, **Then** the pending write is in the bucket and the outbox is empty.

## Verified vs not

OVH's support for `If-Match` / `If-None-Match` on PUT is **not verified** (no credentials in this session): the client probes by behaviour and falls back to re-reading the ETag before writing (`MEMORY_BUCKET_CONDITIONAL_WRITES=false` forces the fallback).

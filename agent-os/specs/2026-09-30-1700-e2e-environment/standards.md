# Standards for the Deterministic E2E Environment

Read the files; this page records only what each one binds in this spec.

| Standard | What it binds here |
|---|---|
| [global/testing](../../standards/global/testing.md) | Definition of Done, the e2e rule this ticket unblocks, the worktree caveat this ticket removes |
| [global/taskfile](../../standards/global/taskfile.md) | Docker-only commands, task naming, `e2e:*` namespace |
| [docker/dockerfiles](../../standards/docker/dockerfiles.md) | The e2e stack reuses the `dev` target; no new stage |
| [docker/traefik-routing](../../standards/docker/traefik-routing.md) | Label-based routing, which the e2e Traefik keeps and constrains |
| [api/testing](../../standards/api/testing.md) | Endpoint minimums (401/400/DB), Alice fixtures, `tests/Support` traits |
| [agent/testing](../../standards/agent/testing.md) | `respx` mocking for the transcription and TTS tests |
| [deployment](../../standards/deployment/) | CI job shape, what a PR check may assume |

## Decisions from Linear that bind this spec

- **ADR-005** — e2e runs on an isolated stack with test data, a test login and a
  deterministic fake LLM. Playwright for web, Maestro for mobile. This ticket builds the
  first three words of that sentence; MAG-95 builds the fake LLM.
- **ADR-006** — the human documentation lives in Linear. The stack gets a page under the
  documentation index rather than a README in the repo.
- **MAG-94 comment, 30 Sept** — agents work in parallel git worktrees, so the stack must
  run once per worktree: derived Compose project name, no fixed host ports, separate
  volumes, and `task e2e:*` plus API tests targeting the current worktree's stack.

## Gotchas this spec has to respect

From `CLAUDE.md`, each one a past regression:

- A module missing from `discovery.scan_dirs` in `api/config/packages/mcp.yaml` has its MCP
  tools silently hidden — the smoke journey asserts one tool per module for that reason.
- A stale Elasticsearch index returns an empty list silently — the seed command rebuilds
  the indices, and the smoke journey searches.
- `/_mcp` needs a bearer; tools read the user through `McpUserContext`. The smoke journey
  uses the test login's JWT, not `SERVICE_TOKEN`, so it exercises the same path a journey
  will.

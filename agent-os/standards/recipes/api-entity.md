# Add a new entity

**When**: a ticket introduces a new stored domain object. A new property on an existing one → `api-entity-field.md`; a value computed on the fly is not an entity.
**Reference**: `api/modules/finance/src/Entity/Placement.php`, `api/modules/finance/src/Mcp/Tool/ManagePlacementsTool.php`, `api/modules/finance/tests/Api/PlacementApiTest.php`, `api/modules/finance/tests/Mcp/PlacementToolsTest.php` (MAG-555: owned by the user, indexed, API Platform + one `manage_*` tool).

## Steps

Follow the `.claude/skills/new-entity` skill (it walks the « New Entity Checklist » of `agent-os/standards/api/mcp-tools.md`), in this order:

1. Entity in `api/modules/<module>/src/Entity/`, enums in `src/Enum/`, repository with `findByUser()` (conventions: `agent-os/standards/api/entities.md`).
2. `Create/Update/Delete<Entity>Command` + handlers + use cases, routed `sync` in `api/config/packages/messenger.yaml` (→ `api-write-use-case.md`).
3. HTTP operations and State processors (→ `api-operation.md`).
4. `manage_<entities>` tool, or the split set for a rich entity (→ `api-mcp-tool.md`).
5. Migration in `api/migrations/` (→ step 6 of `api-entity-field.md`), label `lock:migration`.
6. Contract: `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`; add the recording to `api/tests/Contract/RecordedResponseContractTest.php` + `fixtures/RecordedResponses.yaml` if the mobile reads it.
7. e2e seed in `api/fixtures/e2e/` when a journey needs rows; then `admin-screen.md`, `mobile-screen.md`.

## Tests owed

- Projection by dispatching the commands (Mercure create/update/delete, ES index/delete), API test, MCP tool test — as listed in the recipes above and `agent-os/standards/api/testing.md`.
- Run: `task wt:test:api -- --testsuite <Module>` and `task wt:test:api -- --testsuite Contract`.

## Standards

- `agent-os/standards/api/entities.md`, `agent-os/standards/api/mcp-tools.md`, `agent-os/standards/api/testing.md`
- `agent-os/standards/backend/projection.md`, `agent-os/standards/backend/module-boundaries.md`, `agent-os/standards/api/resources-only.md`

## Pitfalls

- No owner contract (`OwnedByUserInterface` / `OwnedThroughInterface`) → never published, and not scoped by `CurrentUserExtension`: another user's rows leak.
- A new module needs its bundle in `config/bundles.php`, `composer.json` (path repo + test namespace), `phpunit.dist.xml` (testsuite + source), `deptrac.yaml` and `discovery.scan_dirs` — a missing one fails silently (zero tests, hidden tools).
- Enums under `src/Entity/` break the Doctrine mapping root: `src/Enum/`.
- No `Patch` on an identifier-less `uriTemplate`; id `#[ApiProperty(writable: false)]` (contract tests enforce both).

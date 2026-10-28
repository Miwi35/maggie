# Add a field to an existing entity

**When**: a ticket adds (or renames) a stored property on an entity that already exists. A new entity → `api-entity.md`; a computed value nobody stores goes in a read use case or service, not a column.
**Reference**: indexed API Platform resource: `api/modules/finance/src/Entity/Loan.php`, `api/modules/finance/src/MessageHandler/UpdateLoanHandler.php`, `api/migrations/Version20261021120000.php`, `api/modules/finance/tests/Api/LoanApiTest.php` (MAG-522). Not indexed, set through a controller: `SafetyCushion` in 863fea9ec (MAG-669).

## Steps

1. `api/modules/<module>/src/Entity/<Entity>.php` — private property with `#[ORM\Column(...)]`: nullable, or a default both in PHP and in `options: ['default' => …]` so existing rows stay valid; enums from `src/Enum/` with `enumType:`. Constraints (`Assert\…`) on the property; getter + setter (or one intention method setting related fields together).
2. Serialization: API Platform serializes the public getters, there are no serialization groups (only `Qualification` uses `#[Groups]`). A field never sent by clients → `#[ApiProperty(writable: false)]`; never exposed → no getter or `#[Ignore]`.
3. Mercure: add the key to `toMercurePayload()` (or the `toArray()` it filters). The key must equal the Doctrine property name, or be mapped in the `$propertyMap` of `filterPayload()`, else differential updates drop it.
4. Elasticsearch, if the entity is `#[Indexed]`: `#[IndexedField(type: …)]` on the property and the key in `toSearchDocument()`.
5. Write path: the field in `Create<Entity>Command` / `Update<Entity>Command` (nullable = unchanged on update), applied and validated in the handler/use case (`\DomainException` on a bad value); in the State processors (`src/State/*Processor.php`) or the controller that builds the command; in the MCP tool's arguments, description and JSON (`api-mcp-tool.md`).
6. Migration: `api/migrations/Version<YYYYMMDDHHMMSS>.php` (`final class … extends AbstractMigration`, `getDescription`, `up`, `down`), newer than the latest on `main`, additive, defaults for existing rows. From a worktree write it by hand or `task e2e:console -- doctrine:migrations:diff`. Ticket label `lock:migration`.
7. e2e fixtures (`api/fixtures/e2e/*.yaml`) when a journey needs the value.
8. Clients, each its own ticket after this one: `admin-api-field.md`, `mobile-api-field.md`.

## Tests owed

- `api/modules/<module>/tests/Api/<Entity>ApiTest.php`: POST/PATCH with the field persists it (reload the row), a bad value → 400/422 with `assertMercureUpdateCount(0)`, GET returns it (ES-served if indexed), `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched(<Entity>::class)`.
- `tests/Mcp/<Subject>ToolsTest.php` if the tool takes or returns it; `tests/MessageHandler/…HandlerTest.php` for each validation branch.
- Contract: `task wt:test:api -- --testsuite Contract` shows the change; `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` rewrites `openapi.json`, `mcp-tools.json`, `responses/*.json`; commit the diff. Then the mobile `DtoContractTest`: `task wt:test:mobile -- --tests 'com.maggie.app.data.model.DtoContractTest'`.
- Run: `task wt:test:api -- --testsuite <Module>`.

## Standards

- `agent-os/standards/api/entities.md`
- `agent-os/standards/backend/projection.md`, `agent-os/standards/global/real-time.md`
- `agent-os/standards/api/testing.md`
- `api/contract/README.md`

## Pitfalls

- An indexed entity is served from Elasticsearch: a property without `#[IndexedField]` / not in `toSearchDocument()` reads as its PHP default on every GET, with no error. Deploy runs `app:elasticsearch:mapping:update --all` and `app:elasticsearch:reindex --all` (`infra/scripts/deploy-k3s.sh`); locally reindex, then `task api:console -- app:elasticsearch:status --check`.
- `isX()` serializes as `x` over HTTP while the Mercure payload may say `isX`: name the client DTO after the recorded response (CLAUDE.md Gotchas).
- A NOT NULL column without a DB default fails on existing rows in production: always `options: ['default' => …]` or nullable.
- Two migrations with the same or an older version than `main` collide in the train: take a timestamp after the latest file in `api/migrations/`.
- A field forgotten in the processor or the `Update…Command` is accepted by the API and silently not saved: test the PATCH round trip.

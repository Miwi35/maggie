# Add an MCP tool

**When**: Maggie must read or change something of a module (`manage_<entities>` for an entity's CRUD, or a domain verb: `set_trip`, `end_errand`). The write it dispatches is its own recipe (→ `api-write-use-case.md`); an HTTP route for the same action → `api-operation.md`.
**Reference**: write: `api/modules/calendar/src/Mcp/Tool/SetTripTool.php`; read: `api/modules/calendar/src/Mcp/Tool/ListTripsTool.php` (+ `Mcp/TripJson.php`); CRUD in one tool: `api/modules/finance/src/Mcp/Tool/ManagePlacementsTool.php`; tests: `api/modules/calendar/tests/Mcp/TripToolsTest.php`.

## Steps

1. `api/modules/<module>/src/Mcp/Tool/<Name>Tool.php` — `final class`, one tool per class, `#[McpTool(name: '<snake_case>', description: '…')]` (`Mcp\Capability\Attribute\McpTool`). The description is Maggie's only manual: what it does, every argument and its units/format, what it returns, what a refusal looks like, nothing deduced.
2. Constructor: `McpUserContext` (`Maggie\Core\Mcp`), plus `MessageBusInterface` for a write, the repository for a read. Never `Security`, never `EntityManagerInterface` for writing.
3. `__invoke(...)`: typed scalar arguments (camelCase); closed sets as `#[Schema(enum: [...])]` (`Mcp\Capability\Attribute\Schema`). First `$user = $this->userContext->requireUser()` inside `try`, `catch (MissingMcpUserException)` → `{"error": …}`.
4. CRUD tool: an `action` argument, `match ($action)` to a private method per action, `default` → error listing the valid actions; a missing required argument → error naming it.
5. Write: convert arguments (enum `tryFrom` or refusal naming the field), dispatch the command with `userId: (string) $user->getId()`, read `$envelope->last(HandledStamp::class)?->getResult()`. Catch `HandlerFailedException`, unwrap the module's domain exceptions into `{"error", "field"?}`; rethrow anything else.
6. Read: a repository method taking the `User` (`findByUser()`, `findLeavingBetween($user, …)`), never `findAll()`.
7. Output: always `json_encode([...], JSON_THROW_ON_ERROR)`; `{"success": true, "<noun>": …}` on a write. Shape the entity in a static `api/modules/<module>/src/Mcp/<Noun>Json.php` shared by the tools.
8. New module only: add `modules/<name>/src` to `discovery.scan_dirs` in `api/config/packages/mcp.yaml`. Tools are autowired; nothing else to register.
9. Destructive tool (deletes what the user made) not named `delete_*` nor `manage_*`+`action: delete`: add a rule to `agent/data/policy.yaml`; for a new `manage_*`/`delete_*` add its French object to `_MANAGE_OBJECTS` / `_DELETE_TOOLS` in `agent/app/policy/summary.py`.

## Tests owed

- `api/modules/<module>/tests/Mcp/<Subject>ToolsTest.php` (`KernelTestCase`, `FixtureLoaderTrait`, `SecurityTokenTrait`, `MercureAssertionTrait`, `ElasticsearchAssertionTrait`; `setUp`: `bootKernel`, `loadFixtures`, `resetMercure()`, `resetAsyncTransport()`; tool from `self::getContainer()->get(<Name>Tool::class)`): no user bound → `error`; missing argument and unknown `action`; happy path after `loginFixtureUser()` reloading the row from DB; `assertMercureUpdatePublished()`; `assertElasticsearchIndexDispatched()` if indexed; another user's id refused; a read never lists the neighbour's rows.
- `api/modules/<module>/tests/Mcp/<Subject>AccessTest.php` (`WebTestCase`): `POST /_mcp` `tools/call` without a bearer → 401 (`TripAccessTest`).
- Contract: `api/contract/mcp-tools.json` moves → `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`, commit the diff.
- Journey with Maggie: a fake-LLM scenario calling the tool (`agent-fake-llm-scenario.md`), in the journey of `e2e-web-journey.md` / `e2e-mobile-journey.md`.
- Run: `task wt:test:api -- --filter '<Subject>ToolsTest|<Subject>AccessTest'`.

## Standards

- `agent-os/standards/api/mcp-tools.md`
- `agent-os/standards/api/testing.md`, `agent-os/standards/global/testing.md`
- `agent-os/standards/agent/architecture.md` (policy and approvals)

## Pitfalls

- A module missing from `discovery.scan_dirs` has its tools silently hidden — the contract diff shows them deleted.
- Never resolve the user from the database (`findAll()[0]`); never trust an id from Maggie without the handler checking it is the user's (MAG-547, MAG-175).
- An incomplete input is refused with the current state (`current…` in the error), never completed from "now" or the stored value (`mcp-tools.md`, calendar schedule contract).
- An uncaught `HandlerFailedException` becomes a JSON-RPC error Maggie cannot read: unwrap the domain causes, let only real bugs through.
- A renamed tool or argument breaks Maggie with no PHP error (`McpToolsContractTest`): read the `mcp-tools.json` diff and update the `agent/fixtures/fake-llm/` scenarios that call it.

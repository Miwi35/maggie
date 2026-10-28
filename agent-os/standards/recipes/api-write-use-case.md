# Add a write use case

**When**: any ticket that changes data on the API (create, update, set, remove, an action like `restock`, `set_trip`). Not for a read (a repository query or a read use case called directly), not for the effect of a state change (→ `api-domain-effect.md`); exposing it over HTTP or MCP comes after (→ `api-operation.md`, `api-mcp-tool.md`).
**Reference**: `api/modules/calendar/src/Message/SetTripCommand.php`, `api/modules/calendar/src/MessageHandler/SetTripHandler.php`, `api/modules/calendar/src/UseCase/SetTrip.php` (MAG-654); with a service: `api/modules/grocery/src/UseCase/RestockProduct.php` → `Service/GroceryListItems.php`.

## Steps

1. `api/modules/<module>/src/Message/<Verb><Noun>Command.php` — `final readonly class`, public promoted constructor args: `userId` (string) first, identifiers as strings (`<noun>Id`), values as scalars or backed enums from `src/Enum/`. Never an entity. Partial update: nullable fields, `null` = unchanged.
2. `api/modules/<module>/src/MessageHandler/<Verb><Noun>Handler.php` — `#[AsMessageHandler]`, `__invoke(<Verb><Noun>Command $command): <Entity>`. Loads the rows by id (`Ulid::isValid()` first), **checks they belong to `$command->userId`** (another user's row answers exactly like a missing one), then calls the use case. Returns the entity (or `void` for a delete).
3. `api/modules/<module>/src/UseCase/<Verb><Noun>.php` — plain class, autowired, one public `execute(...)` taking entities. Does the write: `persist`, entity methods, one `$this->em->flush()`. Time from `Psr\Clock\ClockInterface`, never `new \DateTimeImmutable()`.
4. Business rule shared by several use cases, or heavy computation → `api/modules/<module>/src/Service/<Noun>.php`, injected in the use case (the service is the single place the logic lives).
5. Refusals → `api/modules/<module>/src/Exception/Invalid<Noun>Exception.php` extending `\DomainException` (a `field` property when a caller must name the field, see `InvalidTripException`). Thrown from handler/use case; callers unwrap `HandlerFailedException`.
6. Route the command in `api/config/packages/messenger.yaml` → `framework.messenger.routing`: `'Maggie\<Module>\Message\<Verb><Noun>Command': sync` (next to the module's other commands, never in the module's config).
7. Nothing to publish or index by hand: `ProjectionMiddleware` projects the unit of work of the root command.

## Tests owed

- `api/modules/<module>/tests/MessageHandler/<Verb><Noun>HandlerTest.php` (`KernelTestCase`, `FixtureLoaderTrait`, `MercureAssertionTrait`, `ElasticsearchAssertionTrait`; `resetMercure()` + `resetAsyncTransport()` in `setUp`): dispatch the command on `MessageBusInterface` with nobody logged in; reload the row and assert its state; `assertMercureUpdatePublished(...)`, `assertElasticsearchIndexDispatched(<Entity>::class)` if indexed; each refusal → `HandlerFailedException` whose previous is the domain exception, and `assertMercureUpdateCount(0)`; another user's id refused. Model: `grocery/tests/MessageHandler/RestockProductHandlerTest.php`.
- `api/modules/<module>/tests/UseCase/<Verb><Noun>Test.php` and `tests/Service/<Noun>Test.php` when they hold rules: happy path and every error branch (`grocery/tests/UseCase/RestockProductTest.php`).
- Fixtures: `tests/<Dir>/fixtures/<TestClass>.yaml` (nelmio/alice).
- Run: `task wt:test:api -- --filter '<Verb><Noun>'` then `task wt:test:api -- --testsuite <Module>`.

## Standards

- `agent-os/standards/api/entities.md` (Messenger commands, routing)
- `agent-os/standards/backend/projection.md`
- `agent-os/standards/backend/module-boundaries.md`
- `agent-os/standards/api/testing.md`, `agent-os/standards/global/testing.md`

## Pitfalls

- A handler or use case that injects `HubInterface`, `EntityBroadcaster` or dispatches `IndexDocumentCommand` fails `core/tests/Projection/ProjectionArchitectureTest.php`.
- A write made outside the bus (controller or console command calling `em->flush()`) is never published nor indexed: dispatch the command.
- Ownership is the handler's job, not the caller's: MAG-547, MAG-175 and #212 each fixed a command that acted on another user's row.
- An unrouted command still runs (default sync) but is missing from the reviewed routing list: always add the line.
- A user resolved in a handler from `Security` is empty in a worker and in Maggie's calls: the user comes from `$command->userId`.

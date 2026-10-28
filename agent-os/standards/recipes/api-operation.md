# Expose a resource or an action over HTTP

**When**: a ticket needs a new route under `/api/` — CRUD on an entity, a filter, a domain action (`check`, `reorder`, `simulate`) or a computed domain resource. Not for an MCP tool (→ `api-mcp-tool.md`); the write behind it is a command (→ `api-write-use-case.md`).
**Choose**:
- **API Platform operation** (default) — CRUD on an entity with an `{id}`: `Get`, `GetCollection`, `Post`, `Patch`, `Delete` on the entity, writes through a State processor.
- **Dedicated controller** — an identifier-less route (`/api/me/…`, `/api/finance/cushion-config`), an action that is not a CRUD verb, a body that is not the entity, or a computed read (`…/simulation`, `recipe-needs`).
**Reference**: API Platform: `api/modules/finance/src/Entity/Placement.php` (`#[ApiResource]` block), `api/modules/finance/src/State/CreatePlacementProcessor.php`, `api/modules/finance/tests/Api/PlacementApiTest.php`. Controller: `api/modules/core/src/Controller/UserLocationController.php` + `api/modules/core/tests/Controller/UserLocationControllerTest.php`.

## Steps — API Platform operation

1. On the entity: `#[ApiResource(operations: [...])]` with explicit `uriTemplate` (module prefix, e.g. `/finance/placements/{id}`; the `/api` prefix comes from `config/routes/api_platform.yaml`). Explain the class with `//` comments, not a docblock (published as the resource description).
2. Reads: indexed entity → `provider: ElasticsearchCollectionProvider::class` / `ElasticsearchItemProvider::class` (`Maggie\Core\Elasticsearch\State`, owner-checked); otherwise the default Doctrine provider, scoped to the user by `Maggie\Core\Doctrine\CurrentUserExtension` (entity implements `OwnedByUserInterface` or `OwnedThroughInterface`). Filters: `#[ApiFilter(...)]` or a module `src/Filter/<Noun>Filter.php` (e.g. `TripDayFilter`).
3. Writes: `api/modules/<module>/src/State/<Verb><Entity>Processor.php` implementing `ApiPlatform\State\ProcessorInterface` (`@implements ProcessorInterface<Entity, Entity>`): build the command from `$data` (user from `Symfony\Bundle\SecurityBundle\Security` on create, from `$data->getUser()` on update), dispatch on `MessageBusInterface`, return `->last(HandledStamp::class)->getResult()`. Map a handler's `\DomainException` to `BadRequestHttpException` (`DispatchesProjectCommandTrait` in finance).
4. Field rules as `Symfony\Component\Validator\Constraints` on the entity; the id carries `#[ApiProperty(writable: false)]`.

## Steps — dedicated controller

1. `api/modules/<module>/src/Controller/<Noun>Controller.php` — `final class`, `#[Route('/api/<path>', name: 'api_<module>_<noun>', methods: [...])]` on the method. Routes are picked up by attribute (`config/routes.yaml` → `routing.controllers`); nothing to register unless it needs env arguments (module `config/services.yaml`).
2. User from `Security::getUser()` (`instanceof User` else 401 JSON). Parse and validate the body at the boundary: each bad field → 400 `{"error": …}` naming it (`InvalidLocationField` pattern), before any dispatch.
3. Write = dispatch a command; catch `HandlerFailedException` and answer its domain cause as 400. Read = call a read use case or repository filtered by the user.
4. Answer JSON (`JsonResponse`), or `204` when there is nothing to read back.

## Tests owed

- `api/modules/<module>/tests/Api/<Entity>ApiTest.php` (API Platform) or `tests/Controller/<Noun>ControllerTest.php` (`WebTestCase`, `FixtureLoaderTrait`, `AuthenticatedTestTrait`, `MercureAssertionTrait`, `ElasticsearchAssertionTrait`; `resetMercure()` + `resetAsyncTransport()` in `setUp`): 401 without `authHeaders()`; 400/422 on a missing or invalid field with `assertMercureUpdateCount(0)`; happy path reloading the row; `assertMercureUpdatePublished()`; `assertElasticsearchIndexDispatched()` / `assertElasticsearchDeleteDispatched()` if indexed; another user's id → 404. `PATCH` sends `application/merge-patch+json`, and once with the whole record read before (admin behaviour).
- Contract: `task wt:test:api -- --testsuite Contract` fails on the new route; regenerate with `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`, add a recording in `api/tests/Contract/RecordedResponseContractTest.php` (+ `fixtures/RecordedResponses.yaml`) when the mobile reads the response, commit the diff.
- e2e journey: `e2e-web-journey.md` / `e2e-mobile-journey.md`.
- Run: `task wt:test:api -- --filter '<Entity>ApiTest|<Noun>ControllerTest'`.

## Standards

- `agent-os/standards/api/resources-only.md` (no screen endpoint)
- `agent-os/standards/api/testing.md`, `agent-os/standards/global/testing.md`
- `agent-os/standards/backend/projection.md`, `agent-os/standards/global/real-time.md`

## Pitfalls

- Never a `Patch` on an `uriTemplate` without an identifier (`/…/me`): API Platform builds a fresh entity → 500 (a1deb6dd1, user preferences). Use a controller; `api/tests/Contract/MeOperationContractTest.php` enforces it.
- A writable `id` makes the admin's save answer 400 (it sends the IRI back): `EditableResourceIdContractTest`.
- A `\DomainException` not mapped in the processor/controller surfaces as a 500 (f6203b5d5, f1e81e547): catch `HandlerFailedException`, or reject at the boundary.
- A relation resolved for a write must be a managed Doctrine entity, not the ES-hydrated copy (20fea18c7, « A new entity was found through… »).
- An ES-served collection on a stale index returns an empty list silently: `task api:console -- app:elasticsearch:status --check`.
- No `/api/home…` or per-screen route: aggregate with a domain filter or an index (`resources-only.md`).

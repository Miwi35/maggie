# API Testing (PHPUnit)

Coverage is not optional: see the Definition of Done in
[global/testing](../global/testing.md) for what every ticket owes, including the
mandatory e2e journey and the red-first rule for bug fixes.

## Base Classes

| Type | Extends | When |
|---|---|---|
| Unit | `TestCase` | Services, entities, pure logic, middleware |
| Integration | `KernelTestCase` | Repositories, MCP tools (real DB) |
| API | `WebTestCase` | HTTP endpoints (real DB + router) |

## File Location & Naming

Tests live in the module they cover, never in `api/tests/`:

```
api/modules/calendar/tests/
  Entity/EventTest.php
  Service/RecurrenceServiceTest.php
  Repository/EventRepositoryTest.php
  Mcp/CreateEventToolTest.php
  Projection/…ProjectionTest.php
  Api/EventApiTest.php

api/tests/Support/          — shared helpers only (traits, in-memory hub)
```

- Mirror the module's `src/` structure under its `tests/`
- Namespace: `Maggie\{Module}\Tests\{Type}` (helpers stay `App\Tests\Support`)
- Declare the namespace in `composer.json` `autoload-dev`, and add a
  `<testsuite>` plus a `<source><directory>` entry in `phpunit.dist.xml` —
  a module missing from either runs zero tests or reports no coverage

## Fixtures (nelmio/alice)

DB-backed tests use `FixtureLoaderTrait` with YAML fixtures:

```php
class GetTasksToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    public function testReturnsPendingTasks(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');
        // purges DB, loads YAML, persists entities
        $task = $this->getFixture('task_pending');
    }
}
```

YAML files co-located in `fixtures/` subdirectory next to test class:

```yaml
# modules/calendar/tests/Mcp/fixtures/GetTasksToolTest.yaml
Maggie\Calendar\Entity\Task:
  task_pending:
    name: Pending task
    priority: '<(Maggie\Calendar\Enum\TaskPriority::Medium)>'
  task_done:
    name: Done task
    doneDate: '<(new \DateTimeImmutable())>'
```

- `@ref_name` for entity references, `<(php expr)>` for DateTimeImmutable/enums
- One YAML per test class, shared across all test methods
- `loadFixtures()` purges + loads (each test starts clean)
- `purgeDatabase()` for tests needing empty DB

## Enforcement: MCP Tool Tests

**Every MCP tool MUST have a test class** in `modules/{module}/tests/Mcp/{Subject}ToolsTest.php`.

Every tool test covers, at minimum:

- **no user bound** — the tool answers `{"error": "No user bound…"}` (see [mcp-tools](mcp-tools.md))
- **bad input** — a missing required argument, and an unknown `action`
- **happy path with DB assertions** — reload the entity and check its state
- **Mercure** — `assertMercureUpdatePublished()`
- **Elasticsearch** — `assertElasticsearchIndexDispatched()` on indexed entities

Bind the user the way a real MCP call does, with `loginFixtureUser()` from
`SecurityTokenTrait` after `loadFixtures()`.

Write tool tests (KernelTestCase):

```php
public function testCreateTaskPersistsToDatabase(): void
{
    $this->loadFixtures('CreateTaskToolTest.yaml');
    $tool = $this->getTool();

    $result = $tool('Buy groceries', 'high');

    $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
    self::assertTrue($data['success']);

    // Verify persistence
    $tasks = $em->getRepository(Task::class)->findAll();
    self::assertCount(1, $tasks);
}
```

Read tool tests (KernelTestCase):

```php
public function testReturnsPendingTasksByDefault(): void
{
    $this->loadFixtures('GetTasksToolTest.yaml');

    $result = $this->getTool()('pending');

    $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
    self::assertSame('pending', $data['status']);
    self::assertContains('Pending task', array_map(fn($t) => $t['name'], $data['tasks']));
}
```

## Enforcement: Endpoint Tests

**Every REST controller and API Platform operation MUST have a test class** in
`modules/{module}/tests/Controller/` or `tests/Api/` (`WebTestCase`), covering:

- **401** — the same request without `authHeaders()` from `AuthenticatedTestTrait`
- **400** — a missing or invalid field in the payload
- **happy path with DB assertions** — reload the entity and check its state
- **Mercure** — `assertMercureUpdatePublished()`
- **Elasticsearch** — `assertElasticsearchIndexDispatched()`, or
  `assertElasticsearchDeleteDispatched()` on delete, for indexed entities

Reference: `modules/grocery/tests/Controller/CheckGroceryItemControllerTest.php`.

Never declare a `Patch` on an `uriTemplate` without an identifier (`/…/me`) — it
instantiates a new entity and returns 500. Use a dedicated controller and test it here.

## Enforcement: Mercure Publication Tests

**Every entity MUST have its Mercure publication tested** by dispatching its commands on the bus (`KernelTestCase`), not by mocking the Hub: `ProjectionMiddleware` publishes what each command changed (`agent-os/standards/backend/projection.md`).

```php
public function testDeleteStorePublishesTheDeletionToTheOwner(): void
{
    $this->resetMercure();
    $this->resetAsyncTransport();

    $bus->dispatch(new DeleteStoreCommand(storeId: $id, userId: $ownerId));

    $this->assertMercureDeletePublished('/api/stores/'.$id);
    $this->assertElasticsearchDeleteDispatched('stores');
}
```

References: `core/tests/Projection/ProjectionMiddlewareTest.php`, `grocery/tests/Projection/GroceryProjectionTest.php`, `cookbook/tests/Projection/MealProjectionTest.php`. Assert once (`assertMercurePublishedOnce`) wherever a flow used to broadcast by hand.

## Assertions

- `self::assertSame()` over `assertEquals()` (strict)
- `self::assertResponseStatusCodeSame(201)` for HTTP
- JSON: `json_decode($content, true, 512, JSON_THROW_ON_ERROR)`
- Use `assertContains`/`assertNotContains` on mapped arrays for shared fixture datasets

# API Testing (PHPUnit)

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
  Middleware/MercurePublishMiddlewareTest.php
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

## Enforcement: Mercure Publication Tests

**Every entity MUST have Create/Update/Delete Mercure tests** in `MercurePublishMiddlewareTest` (unit test — `TestCase` with mocked Hub).

3 tests per entity (9 total for Event + Agenda + Task):

```php
public function testCreate{Entity}PublishesToMercure(): void
{
    $entity = new Entity();
    $entity->setName('Test');

    $middleware = new MercurePublishMiddleware($this->hub);
    $envelope = new Envelope(new Create{Entity}Command(name: 'Test'));

    $middleware->handle($envelope, $this->createPassthroughStack($entity));

    self::assertCount(1, $this->publishedUpdates);
    $data = json_decode($this->publishedUpdates[0]->getData(), true);
    self::assertSame('Test', $data['name']);
    self::assertStringContainsString('/api/{entities}/', $data['@id']);
}

public function testDelete{Entity}PublishesToMercure(): void
{
    $id = (string) new Ulid();

    $middleware = new MercurePublishMiddleware($this->hub);
    $envelope = new Envelope(new Delete{Entity}Command({entity}Id: $id));

    $middleware->handle($envelope, $this->createPassthroughStack());

    self::assertCount(1, $this->publishedUpdates);
    $data = json_decode($this->publishedUpdates[0]->getData(), true);
    self::assertSame('/api/{entities}/' . $id, $data['@id']);
    self::assertTrue($data['deleted']);
}
```

## Assertions

- `self::assertSame()` over `assertEquals()` (strict)
- `self::assertResponseStatusCodeSame(201)` for HTTP
- JSON: `json_decode($content, true, 512, JSON_THROW_ON_ERROR)`
- Use `assertContains`/`assertNotContains` on mapped arrays for shared fixture datasets

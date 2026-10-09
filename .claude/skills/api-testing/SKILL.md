---
name: api-testing
description: "PHPUnit testing patterns for the Symfony API. Use when writing or reviewing tests for entities, MCP tools, Mercure publication, repositories, and middleware."
user-invocable: false
---

# API Testing (PHPUnit)

## Required Coverage — nothing ships without it

| Touched | Minimum tests |
|------|------|
| MCP tool | no user bound, missing required arg, unknown `action`, happy path asserting DB state, `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched()` when indexed |
| Endpoint (controller, API Platform operation) | 401 unauthenticated, 400 bad input, then the same list |
| Entity | Mercure publication and indexing, asserted by dispatching its commands on the bus (`GroceryProjectionTest`) |
| Service, repository | happy path **and** every error branch |

Reference: `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`,
`api/modules/grocery/tests/Controller/CheckGroceryItemControllerTest.php`.

**Bug fix → red first.** Write the reproduction test, run it, watch it fail for the
reason the bug describes, then fix. Both go in the same PR.

**The feature also needs an e2e journey** — see `agent-os/standards/global/testing.md`
(Definition of Done).

## Base Classes

| Type | Extends | When |
|------|---------|------|
| Unit | `TestCase` | Services, entities, pure logic, middleware |
| Integration | `KernelTestCase` | Repositories, MCP tools (real DB) |
| API | `WebTestCase` | HTTP endpoints |

## File Location

Mirror module source structure under `api/tests/`:
- Namespace: `App\Tests\{Module}\{Type}\{Class}Test`

## Fixtures (nelmio/alice)

```php
use App\Tests\Support\FixtureLoaderTrait;

class MyToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void { self::bootKernel(); }

    public function testSomething(): void
    {
        $this->loadFixtures('my_fixture.yaml');
        $entity = $this->getFixture('ref_name');
    }
}
```

YAML fixtures in `fixtures/` subdirectory next to test class:
- `@ref_name` for entity references
- `<(Maggie\Module\Enum\MyEnum::Case)>` for PHP backed enums
- `<(new \DateTimeImmutable())>` for datetime expressions
- Do NOT set `createdAt`/`updatedAt` (auto-set in constructors)
- Call `$this->em()->clear()` before tool calls that read fixture data

## Enforcement: MCP Tool Tests

**Every MCP tool MUST have a test class** in `tests/{Module}/Mcp/`, covering the full
list above. Bind the user the way a real MCP call does, with `loginFixtureUser()` from
`SecurityTokenTrait` after `loadFixtures()`.

## Enforcement: Mercure Publication Tests

**Every entity MUST have its Mercure publication tested** by dispatching its commands on the bus (`ProjectionMiddlewareTest`, `GroceryProjectionTest`).

Use `MercureAssertionTrait` for write tool integration tests:

```php
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\MercureAssertionTrait;

class MyToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreatePublishes(): void
    {
        // ... create via tool ...
        $this->assertMercureUpdatePublished('/my_entities/');
        $this->assertElasticsearchIndexDispatched();
    }
}
```

Note: publication does not need a logged-in user: `ProjectionMiddleware` publishes to the owner of each row, so Delete commands and `OwnedThroughInterface` entities are covered by dispatching the command.

## Assertions

- `self::assertSame()` over `assertEquals()` (strict types)
- JSON: `json_decode($result, true, 512, JSON_THROW_ON_ERROR)`

## Reference

For full details, read `agent-os/standards/api/testing.md`

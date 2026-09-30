---
name: api-testing
description: "PHPUnit testing patterns for the Symfony API. Use when writing or reviewing tests for entities, MCP tools, Mercure publication, repositories, and middleware."
user-invocable: false
---

# API Testing (PHPUnit)

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

**Every MCP tool MUST have a test class** in `tests/{Module}/Mcp/`.

## Enforcement: Mercure Publication Tests

**Every entity MUST have Create/Update/Delete Mercure tests** in `MercurePublishMiddlewareTest`.

Use `MercureAssertionTrait` for write tool integration tests:

```php
use App\Tests\Support\MercureAssertionTrait;

class MyToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
    }

    public function testCreatePublishes(): void
    {
        // ... create via tool ...
        $this->assertMercureUpdatePublished('/my_entities/');
    }
}
```

Note: Delete commands and `OwnedThroughInterface` entities don't publish Mercure without an authenticated user in `KernelTestCase`. Cover via unit tests in `MercurePublishMiddlewareTest` instead.

## Assertions

- `self::assertSame()` over `assertEquals()` (strict types)
- JSON: `json_decode($result, true, 512, JSON_THROW_ON_ERROR)`

## Reference

For full details, read `agent-os/standards/api/testing.md`

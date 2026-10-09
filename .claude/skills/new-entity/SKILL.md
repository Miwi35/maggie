---
name: new-entity
description: "Complete checklist for adding a new API entity. Use when creating a new Doctrine entity with all required MCP tools, Messenger commands, Mercure publication, and tests."
user-invocable: true
argument-hint: "[entity-name] [module-name]"
---

# New Entity Checklist

When adding entity `$0` in module `$1`, follow ALL steps:

## 1. Entity class

Create `api/modules/$1/src/Entity/$0.php`:
- ULID primary key (`Symfony\Component\Uid\Ulid`)
- Implement `MercurePublishable` interface with `toMercurePayload()`
- Annotate with `#[ApiResource(mercure: true)]`
- Ownership: implement `OwnedByUserInterface` (direct FK) or `OwnedThroughInterface` (indirect)

## 2. Messenger Commands

Create in `api/modules/$1/src/Message/`:
- `Create$0Command.php` — readonly DTO with named constructor args
- `Update$0Command.php` — `$0Id` + nullable fields for partial update
- `Delete$0Command.php` — `$0Id` only

## 3. Message Handlers

Create in `api/modules/$1/src/MessageHandler/`:
- `Create$0Handler.php` — `#[AsMessageHandler]`, returns entity
- `Update$0Handler.php` — `#[AsMessageHandler]`, returns entity
- `Delete$0Handler.php` — `#[AsMessageHandler]`, returns void

## 4. Use Cases

Create in `api/modules/$1/src/UseCase/`:
- `Create$0.php` — persistence logic
- `Update$0.php` — persistence logic
- `Delete$0.php` — persistence logic

## 5. Register command routing

Add to `api/modules/$1/config/packages/messenger.yaml`:
```yaml
framework:
    messenger:
        routing:
            'Maggie\$1\Message\Create$0Command': sync
            'Maggie\$1\Message\Update$0Command': sync
            'Maggie\$1\Message\Delete$0Command': sync
```

## 6. MCP Tools

Create in `api/modules/$1/src/Mcp/Tool/`:
- `Create$0Tool.php` — injects `MessageBusInterface`, dispatches Create command
- `Update$0Tool.php` — injects `MessageBusInterface`, dispatches Update command
- `Delete$0Tool.php` — injects `MessageBusInterface`, dispatches Delete command
- `Get{$0}sTool.php` — injects Repository, queries directly

All tools: `#[McpTool(name: 'snake_case', description: '...')]`, return JSON string.

## 7. Mercure publication unit tests

Nothing to register: `ProjectionMiddleware` publishes and indexes what the commands change. Cover Create/Update/Delete by dispatching the commands on the bus, with `MercureAssertionTrait` (see `api/modules/grocery/tests/Projection/GroceryProjectionTest.php`).

## 8. MCP tool integration tests

Create `api/modules/$1/tests/Mcp/$0ToolsTest.php`:
- Use `FixtureLoaderTrait` + `MercureAssertionTrait`
- Create YAML fixture in `fixtures/` subdirectory
- Test each tool with DB persistence + Mercure publication assertions
- Use `<(Maggie\$1\Enum\MyEnum::Case)>` for enums in fixtures
- Call `$this->em()->clear()` before reading fixture data through tools

## 9. Database migration

```
task api:console -- doctrine:migrations:diff
task api:console -- doctrine:migrations:migrate --no-interaction
```

## 10. Verify

```
task api:lint && task api:test
```

## Reference

For full details, read `agent-os/standards/api/mcp-tools.md` (checklist section) and `agent-os/standards/api/entities.md`

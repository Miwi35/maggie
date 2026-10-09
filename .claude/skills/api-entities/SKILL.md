---
name: api-entities
description: "Doctrine entity conventions. Use when creating or modifying entities in api/ including ULID keys, MercurePublishable, ApiResource, Messenger commands, and modular bundle structure."
user-invocable: false
---

# Entity Conventions

## IDs

All entities use **ULID** (`Symfony\Component\Uid\Ulid`) as primary key:

```php
#[ORM\Id]
#[ORM\Column(type: 'ulid')]
private Ulid $id;

public function __construct() {
    $this->id = new Ulid();
}
```

## API Platform

- All entities exposed via `#[ApiResource]`
- All entities use `#[ApiResource(mercure: true)]` for real-time

## Mercure Publication

Every entity MUST implement `MercurePublishable`:

```php
use Maggie\Core\Contract\MercurePublishable;

#[ApiResource(mercure: true)]
class Task implements MercurePublishable
{
    public function toMercurePayload(): array
    {
        return [
            'name' => $this->name,
            'priority' => $this->priority->value,
            'isDone' => $this->isDone(),
        ];
    }
}
```

- `toMercurePayload()` returns all fields consumers need (no IRI — middleware adds `@id`)
- `ProjectionMiddleware` publishes what every command changed (`agent-os/standards/backend/projection.md`)
- Delete publishes `{'@id': '...', 'deleted': true}`
- Topic pattern: `/api/{entities}/{id}`

## Messenger Commands

Every entity MUST have Create/Update/Delete commands:

```
Message/Create{Entity}Command.php    — readonly DTO with named constructor args
Message/Update{Entity}Command.php    — entityId + nullable fields (partial update)
Message/Delete{Entity}Command.php    — entityId only
MessageHandler/Create{Entity}Handler.php  — #[AsMessageHandler], returns entity
MessageHandler/Update{Entity}Handler.php  — #[AsMessageHandler], returns entity
MessageHandler/Delete{Entity}Handler.php  — #[AsMessageHandler], returns void
UseCase/Create{Entity}.php           — persistence logic
UseCase/Update{Entity}.php           — persistence logic
UseCase/Delete{Entity}.php           — persistence logic
```

Register in `config/packages/messenger.yaml` with `sync` transport.

## Modular Bundles

Local Symfony bundles live in `api/modules/{name}/` — `calendar`, `core`,
`cookbook`, `grocery`, `finance`, `notification`:
- Use `AbstractBundle` with `prependExtension()` for Doctrine mappings
- Namespace: `Maggie\{ModuleName}\`
- Each module has its own `src/`, `config/`, `tests/` structure
- `src/Entity/` holds entities only; backed enums go in `src/Enum/`
- Register the bundle in `config/bundles.php`, the path repository and the test
  namespace in `composer.json`, and a testsuite in `phpunit.dist.xml`

## Ownership

- `OwnedByUserInterface` — direct FK to User
- `OwnedThroughInterface` — indirect ownership (e.g., Task owned through Agenda)

## Reference

For full details, read `agent-os/standards/api/entities.md`

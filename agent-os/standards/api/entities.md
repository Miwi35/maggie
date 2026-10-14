# Entity Conventions

## IDs
All entities use **ULID** (`Symfony\Component\Uid\Ulid`) as primary key. ULIDs are time-ordered and sortable.

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

**Every entity MUST implement `MercurePublishable`** (from `Maggie\Core\Contract`)**:**

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
            // all fields relevant to real-time consumers
        ];
    }
}
```

- `toMercurePayload()` returns all fields consumers need (no IRI — middleware adds `@id`)
- `ProjectionMiddleware` publishes what every command changed (`agent-os/standards/backend/projection.md`)
- Delete publishes `{'@id': '...', 'deleted': true}`
- Topic pattern: `/api/{entities}/{id}`

## Enums

Backed enums live in the module's `src/Enum/`, never in `src/Entity/` — that
directory is the Doctrine mapping root. Reference them from the entity with a
`use` statement and `enumType:` on the column.

## Modular Bundles

Local Symfony bundles live in `api/modules/{name}/` — `calendar`, `core`, `cookbook`, `grocery`, `finance`, `notification`:

- `AbstractBundle` with `prependExtension()` for the Doctrine mappings
- Namespace `Maggie\{ModuleName}\`; each module has its own `src/`, `config/`, `tests/`
- `src/Entity/` holds entities only; backed enums go in `src/Enum/`
- Register the bundle in `config/bundles.php`, the path repository and the test namespace in `composer.json`, a testsuite in `phpunit.dist.xml`, and the module's `src` in `discovery.scan_dirs` of `config/packages/mcp.yaml`

## Ownership

- `OwnedByUserInterface` — direct FK to User
- `OwnedThroughInterface` — indirect ownership (e.g. Task owned through Agenda)

## Messenger Commands

**Every entity MUST have Create/Update/Delete commands for the write path:**

```
Message/Create{Entity}Command.php    — readonly DTO with named constructor args
Message/Update{Entity}Command.php    — {entity}Id + nullable fields (partial update)
Message/Delete{Entity}Command.php    — {entity}Id only
MessageHandler/Create{Entity}Handler.php  — #[AsMessageHandler], returns entity
MessageHandler/Update{Entity}Handler.php  — #[AsMessageHandler], returns entity
MessageHandler/Delete{Entity}Handler.php  — #[AsMessageHandler], returns void
UseCase/Create{Entity}.php           — persistence logic
UseCase/Update{Entity}.php           — persistence logic
UseCase/Delete{Entity}.php           — persistence logic
```

Commands MUST be registered in the application's `api/config/packages/messenger.yaml` (not in a module) with sync transport:

```yaml
framework:
    messenger:
        routing:
            'Maggie\Calendar\Message\Create{Entity}Command': sync
            'Maggie\Calendar\Message\Update{Entity}Command': sync
            'Maggie\Calendar\Message\Delete{Entity}Command': sync
```

`ProjectionMiddleware` then publishes and indexes automatically.

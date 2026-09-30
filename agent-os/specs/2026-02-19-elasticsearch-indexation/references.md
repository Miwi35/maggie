# Elasticsearch Indexation — References

## Key Patterns from Maggie v3 Codebase

### MercurePublishMiddleware (api/modules/calendar/src/Middleware/MercurePublishMiddleware.php)
- Implements `MiddlewareInterface`
- Gates on `ReceivedStamp` (skip first pass, only publish after handler)
- Parses command class name: `{Create|Update|Delete}{Entity}Command`
- For create/update: reads entity from `HandledStamp::getResult()`
- For delete: reads `$message->{entityId}` by convention
- Wraps in try/catch, logs errors without breaking request

### MercurePublishable Contract (api/modules/calendar/src/Contract/MercurePublishable.php)
- `getId(): Ulid`
- `toMercurePayload(): array`

### CurrentUserExtension (api/modules/core/src/Doctrine/CurrentUserExtension.php)
- OwnedByUserInterface → `WHERE alias.user = :current_user`
- OwnedThroughInterface → joins parent relation, filters on parent's user

### CQRS Flow
- Processor dispatches command to bus → Handler invokes UseCase → UseCase persists
- Commands are `final readonly class` with public constructor properties
- Handlers return the entity (available via HandledStamp)

### Entity Patterns
- All entities use ULID (`Symfony\Component\Uid\Ulid`)
- Event uses joined inheritance (Meal extends Event)
- Product uses single-table inheritance (Ingredient extends Product)
- Relations use ManyToOne with JoinColumn

## External References
- elastic/elasticsearch-php: https://github.com/elastic/elasticsearch-php
- Elasticsearch 8.x mapping types: text, keyword, date, integer, boolean, float, nested
- API Platform custom providers: https://api-platform.com/docs/core/state-providers/

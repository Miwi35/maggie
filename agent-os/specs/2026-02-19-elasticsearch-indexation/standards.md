# Elasticsearch Indexation — Standards

## Naming Conventions
- ES index names: lowercase plural (e.g. `events`, `recipes`, `grocery_lists`)
- PHP attributes: `#[Indexed]` (class), `#[IndexedField]` (property), `#[IndexedRelation]` (relation property)
- Messages: `IndexDocumentCommand`, `DeleteDocumentCommand`
- CLI commands: `app:elasticsearch:{reindex,mapping:update,status}`

## Code Patterns
- All ES code lives in `api/modules/core/src/Elasticsearch/`
- Interface contract: `IndexableInterface` in `api/modules/core/src/Contract/`
- Each module annotates its own entities (no central entity list)
- Middleware mirrors `MercurePublishMiddleware` exactly (same `parseCommandClass()` convention)

## Testing
- Unit tests mock ES client
- Integration tests tagged `@group elasticsearch` and excluded from default suite
- ES tests require running ES container

## Infrastructure
- ES 8.17.0, single-node, security disabled (internal network only)
- 256MB heap (dev environment)
- Docker profile: `search`
- No Traefik routing (ES is internal)

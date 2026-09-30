# Maggie Finance — MVP Foundation — References

## Documents produit
- [`agent-os/product/finance-functional-spec.md`](../../product/finance-functional-spec.md) — spec fonctionnelle v3.0 (source de vérité). Modules M1–M3 = §4 ; règles enveloppes = §5.3 ; glossaire = §8.
- [`agent-os/product/finance-roadmap.md`](../../product/finance-roadmap.md) — décomposition des 12 modules + services en specs séquencées.

## Pattern de référence dans le repo — module `grocery`
Le module Finance calque `Maggie\Grocery`. Fichiers clés à copier/adapter :

### Bundle & config
- `api/modules/grocery/src/MaggieGroceryBundle.php` — AbstractBundle + mapping Doctrine
- `api/modules/grocery/composer.json`, `api/modules/grocery/config/services.yaml`
- `api/config/bundles.php` — enregistrement du bundle

### Entité (modèle complet)
- `api/modules/grocery/src/Entity/Product.php` — ULID, Mercure, ES, ApiResource, providers/processors
- `api/modules/grocery/src/Enum/ProductCategory.php`, `Unit.php` — enums backed string indexés

### CQRS + API Platform
- `api/modules/grocery/src/Message/CreateProductCommand.php` + `MessageHandler/CreateProductHandler.php`
- `api/modules/grocery/src/State/CreateProductProcessor.php` (+ Update/Delete)
- `api/modules/grocery/src/Repository/ProductRepository.php`

### MCP tools
- `api/modules/grocery/src/Mcp/Tool/CreateProductTool.php`, `SearchProductsTool.php`

### Tests (modèle Testing Rule)
- `api/modules/grocery/tests/Mcp/GroceryToolsTest.php` — Mercure + ES + persistance + fixtures
- `api/modules/grocery/tests/Controller/*ControllerTest.php`
- `api/modules/grocery/tests/**/fixtures/*.yaml`

### Core partagé (contrats & attributs)
- `Maggie\Core\Contract\{MercurePublishable, OwnedByUserInterface, IndexableInterface}`
- `Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait`
- `Maggie\Core\Elasticsearch\Attribute\{Indexed, IndexedField, IndexedRelation}`
- `Maggie\Core\Elasticsearch\State\{ElasticsearchCollectionProvider, ElasticsearchItemProvider}`
- `Maggie\Core\Entity\User`

## Admin
- `admin/src/modules/grocery/` — pattern de module React (list/create/edit + Mercure)

## Mobile
- `mobile/.../grocery/` — écrans Compose, repository Ktor, Koin, autocomplete plein écran (`AddItemSheet.kt`, `GroceryListsScreen.kt`)

## Skills applicables
`api-entities`, `new-entity`, `api-mcp-tools`, `api-testing`, `admin-react`, `admin-testing`, `mobile-android`, `mobile-testing`, `mercure`, `run-commands`, `pre-commit`.

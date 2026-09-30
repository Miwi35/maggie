# Maggie Finance — MVP Foundation — Standards

Conventions à respecter (pointent vers les standards existants du repo).

## Bundle & modularité
- `AbstractBundle` + `prependExtension()` pour le mapping Doctrine — voir `api/modules/grocery/src/MaggieGroceryBundle.php`.
- Namespace `Maggie\Finance`, mapping alias `MaggieFinance`.
- Réf. standard : [`agent-os/standards/api/entities.md`](../../standards/api/entities.md), skill `api-entities` / `new-entity`.

## Entités
- **ULID** en id (`Symfony\Component\Uid\Ulid`), construit dans `__construct()`.
- Implémenter `MercurePublishable` (+ `MercurePayloadFilterTrait` + `toMercurePayload()`), `OwnedByUserInterface` (relation `User`), `IndexableInterface` (`#[Indexed]` + `toSearchDocument()`).
- ApiResource : `GetCollection`/`Get` via `ElasticsearchCollectionProvider`/`ElasticsearchItemProvider` ; `Post`/`Patch`/`Delete` via State processors dédiés.
- Réf. modèle : `api/modules/grocery/src/Entity/Product.php`.

## Argent — règle stricte
- **Montants en centimes entiers** (`int`, suffixe `Cents`). Jamais de `float` pour la monnaie.
- `currency` en code ISO-4217 (string 3 char, validée). Pas de conversion multi-devises dans cette spec.

## CQRS léger
- Écritures via **Symfony Messenger** : `Message/*Command` + `MessageHandler/*Handler`, dispatchés par les State processors et les MCP tools (pattern grocery).
- Réf. skill `api-mcp-tools`, [`agent-os/standards/api/mcp-tools.md`](../../standards/api/mcp-tools.md).

## Tests — obligatoire pour chaque endpoint & MCP tool
Selon la [Testing Rule du projet](../../../CLAUDE.md) :
- 401 non authentifié, 400 validation, happy path **avec assertions de persistance DB**.
- `MercureAssertionTrait::assertMercureUpdatePublished`.
- `ElasticsearchAssertionTrait::assertElasticsearchIndexDispatched`.
- `resetMercure()` + `resetAsyncTransport()` dans `setUp`.
- Réf. : `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`, skill `api-testing`.

## Admin (React)
- Nouveau module `admin/src/modules/finance/` sur le pattern des modules existants (HydraAdmin/ResourceGuesser), abonnement Mercure SSE.
- Labels **en français**. Réf. skill `admin-react` / `admin-testing`.

## Mobile (Kotlin/Compose)
- Compose + Material 3, Ktor, Koin, Room si cache. Tests JUnit + MockK.
- **Autocomplete → écran dédié plein écran** (règle Mobile UX du repo), pas de dropdown inline.
- Build/signing : JBR Android Studio, `installProdRelease`, lint via CI. Réf. skill `mobile-android` / `mobile-testing`.

## Real-time & recherche
- Topics Mercure par entité (pattern `/api/events/{type}/{id}`). Réf. skill `mercure`.
- Indices ES : `accounts`, `categories`, `transactions`, `envelopes` (module `finance`). Vérifier via `app:elasticsearch:status --check`.

## Commandes — Docker-first
- Jamais de `php`/`composer`/`bin/console` sur l'hôte. `task api:*` uniquement. Réf. skill `run-commands`.

## Pré-commit
- `task lint:all` + `task test:all` (+ mobile séparé) avant tout commit. Réf. skill `pre-commit`.

## Réglementaire / neutralité (rappel, applicable dès l'UI)
- Ton **neutre** : informer sans juger ni recommander.
- Réversibilité : toute action manuelle annulable ; corrections tracées.

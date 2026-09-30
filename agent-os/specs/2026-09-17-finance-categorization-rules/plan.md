# Maggie Finance — Règles de catégorisation — Plan

Tranche verticale full-stack, comme les quatre précédentes : API → admin React → mobile Kotlin. Testing Rule du repo respectée (401, validation, happy path + persistance DB, Mercure, Elasticsearch).

## Tâche 1 — API : entité et moteur

### Entité `CategorizationRule` (`Maggie\Finance`)
- `labelPattern` (string, requis), `matchType` (enum `MatchType`: contains / starts_with / equals, défaut contains)
- `category` (ManyToOne Category, requis, CASCADE)
- `direction` (enum `AmountDirection`: any / debit / credit, défaut any)
- `minAmountCents` / `maxAmountCents` (int nullables, **valeur absolue**, ≥ 0 ; min ≤ max)
- `priority` (int, défaut 0), `isActive` (bool, défaut true), `user`
- `MercurePublishable` + `OwnedByUserInterface` + `IndexableInterface` (`#[Indexed(index: 'categorization_rules', module: 'finance')]`)

### Traçabilité sur `Transaction`
- `categorySource` (enum `CategorySource`: none / manual / rule, défaut none) + `categorizedAt` (nullable)
- Posés par les handlers : `manual` quand un `categoryId` est fourni explicitement, `rule` quand le moteur tranche.

### Moteur
- `Repository/CategorizationRuleRepository::findActiveForUser()` — tri `priority` DESC, `id` ASC.
- `UseCase/CategorizeTransaction` — `match(Transaction): ?CategorizationRule` et `apply(Transaction): bool` ; ne touche jamais une transaction dont `categorySource === manual`.
- Branchement dans `CreateTransactionHandler` : si aucune catégorie fournie, tenter le moteur.
- `UseCase/ApplyCategorizationRules` — passe en masse sur les transactions `categorySource !== manual` sans catégorie ; renvoie le nombre de transactions catégorisées.

### Commandes / processors / MCP
- `Create|Update|DeleteCategorizationRuleCommand` + handlers + use cases + state processors (pattern Envelope).
- Routing messenger `sync` des trois commandes (**obligatoire**, sinon pas de Mercure).
- MCP `manage_categorization_rules` : `list` / `create` / `update` / `delete` / `apply` (rattrapage en masse) / `learn` (crée une règle depuis une transaction et catégorise celle-ci).
- Migration Doctrine + `task api:es:mapping` pour l'index `categorization_rules`.

### Tests
- `tests/Api/CategorizationRuleApiTest.php` : 401, 422 (pattern vide, min > max), happy path + DB + Mercure + ES, delete.
- `tests/Mcp/CategorizationRuleToolsTest.php` : create/list/update/delete, `apply` en masse, `learn`, priorité entre deux règles concurrentes, non-écrasement d'une catégorie manuelle, fourchette de montant et direction.
- `tests/Api/TransactionApiTest.php` : une transaction créée sans catégorie hérite de la règle.

## Tâche 2 — Admin React
- `CategorizationRuleList/Create/Edit` dans `admin/src/modules/finance/` (pattern Envelope), avec libellés FR des enums.
- Bouton « Appliquer les règles » (appelle l'action d'application en masse) et retour du nombre de transactions catégorisées.
- Entrée de menu « Règles » sous Finance ; ressource enregistrée dans `resources.tsx` + `index.ts`.
- Tests Vitest sur les helpers d'enums et le hook d'application.

## Tâche 3 — Mobile Kotlin
- Modèle `CategorizationRule` + `MaggieApiService` (list / create / delete / apply) + `CategorizationRuleRepository`.
- `CategorizationRuleViewModel` + écran liste/création/suppression, entrée drawer.
- Tests MockK du ViewModel.

## Tâche finale — Vérification
- `task api:lint` + `task api:test`, `task admin:lint` + `typecheck` + `test`, mobile `testProdReleaseUnitTest`.
- `task api:es:mapping` puis `task api:es:status` (index `categorization_rules` présent).
- Roadmap : marquer `finance-categorization-rules` livré, proposer la suite (`finance-annual-envelopes` ou `finance-cushion`).

## Ordre de dépendances
Entité + moteur → branchement création → application en masse → MCP → admin → mobile. Le moteur est la seule vraie logique : il est testé en premier.

# Maggie Finance — MVP Foundation — Plan

Livraison en **tranches verticales** : chaque entité va de l'API au mobile avant de passer à la suivante. Chaque tranche respecte la [Testing Rule](../../../CLAUDE.md) (auth 401, validation 400, happy path + persistance DB, Mercure, Elasticsearch).

## Tâche 0 — Bootstrap du bundle `Maggie\Finance`
- `api/modules/finance/composer.json`, `src/MaggieFinanceBundle.php` (AbstractBundle + `prependExtension` Doctrine mapping `MaggieFinance`), `config/services.yaml`.
- Enregistrer le bundle dans `api/config/bundles.php` et le mapping.
- Enums : `AccountType`, `ObligationFlag`, `TransactionStatus`, `BudgetMode`, `Currency` (ou string ISO-4217 validée).
- Créer la migration Doctrine du schéma (ou par entité au fil des tranches).

## Tranche 1 — Account (compte bancaire)
### API
- `Entity/Account` : `name`, `bank`, `type` (AccountType), `currency`, `balanceCents` (int), `isCushion` (bool), `bridgeAccountId` (nullable), `user`. Implémente `MercurePublishable`, `OwnedByUserInterface`, `IndexableInterface` ; `#[Indexed(index:'accounts', module:'finance')]`.
- `Repository/AccountRepository`.
- Messages/Handlers : Create/Update/Delete + State processors (pattern `CreateProductProcessor`).
- MCP tools : `list_accounts`, `create_account`, `update_account`, `delete_account`, `get_account_balance`.
- Tests : `tests/Controller/*ControllerTest.php` + `tests/Mcp/AccountToolsTest.php` + fixtures.
### Admin (React)
- Module `admin/src/modules/finance/` : `AccountList`, `AccountCreate`, `AccountEdit` (pattern module admin existant), entrée resource + Mercure SSE.
### Mobile (Kotlin)
- Écran liste des comptes + création/édition (Compose + Ktor + Koin), modèle `Account`, repository, ViewModel + tests MockK.

## Tranche 2 — Category (arbre 2 niveaux)
### API
- `Entity/Category` : `name`, `parent` (self ManyToOne nullable), `obligation` (ObligationFlag), `color`/`icon` (nullable), `user`. ES + Mercure.
- Repository (helper : catégories racines, enfants d'une catégorie).
- Messages/Handlers + processors + MCP tools (`list_categories`, `create_category`, `update_category`, `delete_category`).
- Tests Controller + Mcp + fixtures (arbre parent/enfant).
### Admin + Mobile
- Admin : vue arbre / liste avec parent. Mobile : sélecteur de catégorie (autocomplete → **écran dédié plein écran** selon la règle Mobile UX du repo).

## Tranche 3 — Transaction
### API
- `Entity/Transaction` : `account` (ManyToOne), `category` (ManyToOne nullable), `amountCents` (int, signé), `currency`, `bookedAt` (date), `label`, `status` (TransactionStatus, défaut `spent`), `isExceptional` (bool). ES (`#[Indexed(index:'transactions')]`, champs date/keyword/text) + Mercure.
- Repository : requêtes par compte, par période, par catégorie.
- Messages/Handlers + processors + MCP tools (`list_transactions`, `add_expense`, `add_income`, `categorize_transaction`, `update_transaction`, `delete_transaction`). Aligner les noms sur la doc §4.2 (`add_expense`/`add_income`).
- Tests Controller + Mcp + fixtures (compte + catégorie + transaction).
### Admin + Mobile
- Admin : liste filtrable (période, compte, catégorie), création/édition, action de catégorisation.
- Mobile : liste transactions + saisie rapide dépense/revenu.

## Tranche 4 — Envelope (budget)
### API
- `Entity/Envelope` : `category` (ManyToOne), `mode` (BudgetMode: monthly/annual), `amountCents` (int), `year` (int, pour annuel), `month` (nullable, pour mensuel), `user`. ES + Mercure.
- Repository : enveloppe active pour catégorie+période ; consommé (somme transactions liées).
- Messages/Handlers + processors + MCP tools (`list_envelopes`, `set_envelope`, `get_budget_status`, `delete_envelope`). `get_budget_status` aligné doc §4.2.
- Tests Controller + Mcp + fixtures.
### Admin + Mobile
- Admin : gestion des enveloppes par catégorie, jauge consommé vs budget.
- Mobile : vue budget (mensuel + enveloppes annuelles), jauge par catégorie.

## Tâche finale — Intégration & vérif
- `task api:lint` + `task api:test` ; `task admin:lint` + `admin:typecheck` + `admin:test` ; mobile `testProdReleaseUnitTest` (+ CI lint).
- Vérifier l'indexation ES (`app:elasticsearch:status --check`) et les topics Mercure.
- Mettre à jour la roadmap Finance (marquer `finance-mvp` livré) et proposer la spec suivante (`finance-categorization-rules`).

## Ordre de dépendances
`Tâche 0` → `Account` → `Category` → `Transaction` (dépend Account + Category) → `Envelope` (dépend Category). Chaque tranche est mergeable indépendamment.

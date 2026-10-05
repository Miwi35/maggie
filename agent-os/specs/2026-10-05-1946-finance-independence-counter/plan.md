# Compteur d'indépendance (M9) — Plan

Ticket : **MAG-46**. Décisions et périmètre : `shape.md`. Code de référence :
`references.md`.

## Task 1 : Sauver la documentation de spec

Ce dossier : `plan.md`, `shape.md`, `standards.md`, `references.md`. Pas de
`visuals/` — le ticket n'a aucune pièce jointe.

## Task 2 : Le train de vie mesuré devient un service partagé

`MeasureMonthlyLifestyle` (`api/modules/finance/src/UseCase/`) reprend mot pour
mot `GetDebtTimeline::estimateMonthlyLifestyle` et sa constante : la moyenne
mensuelle consommée sur les **trois mois complets précédents**. Il expose aussi
la fenêtre (`sampleWindow()`), pour que les rentes soient mesurées sur la même.
`GetDebtTimeline` l'injecte et perd sa méthode privée — même nombre, une seule
règle.

Tests dus : aucun de plus. Le service est déjà couvert de bout en bout par
`DebtTimelineControllerTest` (fenêtre, exclusion des mensualités et des
exceptionnelles), laissé **intact** : c'est la preuve que le déménagement ne
change rien. `IndependenceCounterControllerTest` l'exerce une deuxième fois,
par l'autre lecteur.

## Task 3 : Une catégorie peut être une rente

`Category::isPassiveIncome` (booléen, défaut `false`) : colonne, champ
Elasticsearch, payload Mercure, `Create`/`UpdateCategoryCommand` et leurs
handlers, `ManageCategoriesTool`. Validation en deux endroits, parce qu'il y a
deux portes : `Assert\Callback` pour REST, et le même prédicat
(`declaresARenteWithoutIncome()`) dans les handlers pour la voie MCP, comme
`CreateLoanHandler` fait pour un prêt qui ne s'amortit pas (décision D2). La
propriété s'appelle `passiveIncome` et non `isPassiveIncome` : Symfony sérialise
`isFoo()` en `foo`, et c'est le piège que `isCushion` paie déjà. Migration
manuelle `api/migrations/Version20261006100000.php` — la colonne garde le nom
`is_passive_income` de ses voisines (`is_cushion`, `is_exceptional`).

Tests dus : `CategoryApiTest` (POST avec la case sur une recette → 201, état DB
et Mercure ; POST sur une dépense → 422 ; PATCH qui la retire ; PATCH qui
déplace une rente vers une dépense → 422), `CategoryToolsTest` (create et update
par l'outil MCP, refus sur une dépense sans rien persister).

## Task 4 : Le compteur

`GetIndependenceCounter` (`api/modules/finance/src/UseCase/`) :

- train de vie mensuel via `MeasureMonthlyLifestyle` ;
- rentes mensuelles via
  `TransactionRepository::sumPassiveIncomeByCategoryBetween()` — crédits des
  catégories `isPassiveIncome`, statuts consommés (`Spent` + `Committed`), hors
  exceptionnelles, sur la fenêtre du train de vie, groupés par catégorie ;
- mensualités de prêt via `LoanRepository`.

Réponse :

```json
{
  "coveragePercent": 60,
  "lifestyleCents": 16666,
  "passiveIncomeCents": 10000,
  "gapCents": 6666,
  "sampleMonths": 3,
  "isMeasurable": true,
  "hasPassiveIncomeCategories": true,
  "isReached": false,
  "loanPaymentsCents": 21000,
  "monthlyNeedCents": 37666,
  "coverageWithDebtPercent": 27,
  "nextMilestonePercent": 75,
  "nextMilestoneGapCents": 2500,
  "milestones": [{ "percent": 25, "isReached": true, "monthlyIncomeNeededCents": 4167 }],
  "byCategory": [
    { "categoryId": "…", "categoryName": "Loyers perçus", "monthlyCents": 10000, "sharePercent": 100 }
  ]
}
```

`isMeasurable` est faux quand le train de vie mesuré est nul : sans
dénominateur il n'y a pas de pourcentage, et `coveragePercent` vaut 0.
`hasPassiveIncomeCategories` distingue « aucune rente déclarée » de « rentes
déclarées, rien encaissé ». Pas de plafond à 100 % (D5). Ni date N ni courbe
(hors périmètre).

Puis : `IndependenceCounterController` sur `GET /api/finance/independence`
(401 sans utilisateur), et la clé `independence` de
`GetFinanceDashboard::execute()`, composée du même use case.

Tests dus : `Controller/IndependenceCounterControllerTest` (401 non
authentifié, happy path chiffre par chiffre, salaire exclu, exceptionnelle et
planifiée exclues, aucune rente déclarée, train de vie nul →
`isMeasurable: false`, couverture > 100 % non plafonnée, et la clé
`independence` servie par le dashboard — la fixture vit ici, pas dans
`FinanceDashboardControllerTest`).

## Task 5 : L'outil MCP

`GetIndependenceCounterTool` (`get_independence_counter`), gabarit
`GetDailyScoreTool` : lecture pure, `requireUser()`, description qui dit à
l'agent ce que chaque nombre signifie et que la date N n'est pas disponible.
`get_finance_dashboard` le sert déjà par sa clé.

Tests dus : `Mcp/IndependenceCounterToolTest` (sans utilisateur lié → erreur
JSON, happy path asserant les termes, aucune catégorie de rente → compteur à 0
avec `hasPassiveIncomeCategories: false`).

## Task 6 : Les contrats

`isPassiveIncome` change `openapi.json` et
`contract/responses/categories.collection.json` ; le nouvel outil change
`mcp-tools.json`. Lire l'échec de la suite `Contract`, puis
`UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` et committer le
diff.

## Task 7 : Admin

- `IndependenceCard` dans `FinanceDashboardPage`, lue depuis
  `dashboard.independence` (aucun hook de plus : le dashboard est une lecture) —
  le pourcentage en gros, la ligne « X de rentes sur Y de train de vie », le
  manque, la décomposition par catégorie, le palier suivant, et l'état « aucune
  catégorie déclarée comme rente » qui dit où le faire.
- la case « Rente » dans `CategoryForm`, **seulement** quand l'obligation est
  une recette (`FormDataConsumer`) : l'API refuse la rente ailleurs, une case
  offerte sur une dépense serait un piège ;
- la colonne « Obligation » de `CategoryList` dit « Recette · rente ».

Tests dus : `IndependenceCard.test.tsx` (rendu avec couverture, paliers,
mensualités, état non mesurable, état sans rente déclarée, couverture au-delà de
100 %) ; `CategoryForm.test.tsx` (la case apparaît sur une recette et disparaît
avec elle) ; `CategoryList.test.tsx` étendu au repère.

## Task 8 : Mobile

DTO `IndependenceCounter` + `independence` sur `FinanceDashboard`
(`data/model/DailyScore.kt`, valeurs par défaut partout), `independenceSummary()`
pour la phrase, et une carte dans `FinanceDashboardScreen` après `CapacityCard`.

Côté lecture seulement : `Category.passiveIncome` sur le DTO et
`categoryKindLabel()` dans la liste des catégories, pour voir lesquelles
alimentent le compteur. **Déclarer** une rente depuis le téléphone est reporté
(décision D9) : la case est dans l'admin, et le libellé de la carte mobile
renvoie vers l'admin ou vers Maggie plutôt que vers une case absente de
l'écran.

Tests dus : `IndependenceCounterTest` (la phrase dans ses quatre états,
`categoryKindLabel`) ; `IndependenceCardScreenTest` avec un
`FakeLoadedFinanceDashboard` — la carte est dessinée et porte les deux termes ;
`DtoContractTest` reste vert.

## Task 9 : Le parcours e2e

Fixtures `api/fixtures/e2e/50-finance.yaml` : la catégorie « Loyers perçus »
(`income`, `isPassiveIncome: true`) et une transaction de rente de 300,00 € le
mois dernier — dans la fenêtre mesurée, qui est les trois mois précédant celui
en cours. Arithmétique de la graine : rentes 30 000 / 3 = 10 000, train de vie
50 000 / 3 = 16 666, couverture 60 %, manque 66,66 €.

`e2e/web/tests/finance-independence.spec.ts` : en lecture seule sur le monde de
l'owner, comme `finance-overview.spec.ts`, plus un cas qui **déclare** une rente
depuis le formulaire de catégorie — le seul chemin que D9 laisse — sur
l'identité voisine, dont le module finance est vide et que rien d'autre ne lit.

## Tests

| Unité | Tests |
|---|---|
| `MeasureMonthlyLifestyle` | `DebtTimelineControllerTest` (intact) et `IndependenceCounterControllerTest` |
| `TransactionRepository::sumPassiveIncomeByCategoryBetween` | par le contrôleur : salaire exclu, exceptionnelle exclue, planifiée exclue |
| `GetIndependenceCounter` | `IndependenceCounterControllerTest` et `IndependenceCounterToolTest` |
| `IndependenceCounterController` | 401 non authentifié, happy path chiffre par chiffre, train de vie nul, aucune rente déclarée, > 100 %, clé du dashboard |
| `GetIndependenceCounterTool` | pas d'utilisateur lié, happy path, rien à mesurer |
| `Category` (champ) | `CategoryApiTest` : 201 + état DB + Mercure, 422 sur une dépense, 422 au déplacement, PATCH ; `CategoryToolsTest` : create/update MCP + refus |
| Contrats | `openapi.json`, `categories.collection.json`, `mcp-tools.json` régénérés |
| `IndependenceCard.tsx` | rendu, paliers, mensualités, non mesurable, aucune rente déclarée, > 100 % |
| `CategoryForm.tsx` | la case n'existe que sur une recette |
| `CategoryList.tsx` | le repère « rente » |
| `IndependenceCounter` (Kotlin) | la phrase dans ses quatre états, `categoryKindLabel` |
| `FinanceDashboardScreen` | la carte est dessinée et porte les deux termes |

Aucune correction de bug dans ce périmètre : pas de test de reproduction dû.

## E2E journey

**Extends :** MAG-102 — Parcours e2e : finance

- **Given** un utilisateur connecté dont la graine porte un train de vie mesuré
  de 166,66 € par mois (500,00 € consommés le mois dernier, moyennés sur trois
  mois) et une catégorie de recette « Loyers perçus » déclarée comme rente, qui
  a encaissé 300,00 € le mois dernier
- **When** il ouvre « Finance » puis la vue d'ensemble
- **Then** la carte « Indépendance financière » affiche **60 %**, et la ligne
  « 100,00 € de rentes sur 166,66 € de train de vie »
- **And** la décomposition nomme « Loyers perçus » pour 100,00 € par mois
- **And** le manque affiché est 66,66 €, et le palier suivant annoncé est 75 %
- **And** aucune date d'indépendance n'est affichée — elle est Premium, et une
  date vide serait une promesse
- **And** le salaire, qui est aussi une recette, n'apparaît pas dans la
  décomposition
- **When** une identité dont le module finance est vide crée une catégorie de
  recette en cochant « Rente »
- **Then** la liste la marque « Recette · rente », et son compteur dit « Pas
  encore de train de vie mesuré » plutôt que 0 %

## Definition of Done

- [ ] Tests unitaires et d'intégration ci-dessus, verts
- [ ] Parcours e2e `finance-independence.spec.ts` écrit et exécutable
- [ ] Pas de correction de bug dans le périmètre, donc pas de test rouge d'abord
- [ ] CI verte sur une PR qui lie MAG-46
- [ ] Spec fonctionnelle du module et guide utilisateur mis à jour dans Linear
      (ADR-006), ligne 9 de `finance-roadmap.md` marquée livrée

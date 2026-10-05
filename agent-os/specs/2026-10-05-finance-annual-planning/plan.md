# Maggie Finance — Session de planification annuelle — Plan

Tranche découpée **par couche**, pas en une tranche verticale : l'API + l'outil MCP (MAG-48), puis l'écran admin, puis l'écran mobile. La tranche verticale entière dépasse la limite de taille du garde-fou (800 lignes hors tests) ; le repo demande alors de découper le ticket.

Testing Rule du repo respectée à chaque tâche (401, 400, happy path + persistance DB, Mercure, Elasticsearch).

## Tâche 1 — API + outil MCP (MAG-48)

### Lecture — `UseCase/GetAnnualPlan`

`execute(User $user, int $year, int $thresholdCents = 10_000): array`, année source = `$year - 1`.

Catégories retenues : celles qui ont une enveloppe **annuelle** sur l'année source ou sur l'année cible, celles qui portent une grosse dépense sur l'année source, celles qui portent un plan sur l'année cible. Triées par montant suggéré décroissant — la plus grosse décision d'abord.

Par catégorie :

| Champ | Contenu |
|---|---|
| `categoryId`, `categoryName`, `currency` | la catégorie, et la devise de son enveloppe (sinon `EUR`) |
| `lastYear.budgetedCents` | l'enveloppe annuelle de l'année source, `null` s'il n'y en avait pas |
| `lastYear.consumedCents` | dépensé + engagé sur l'année source |
| `lastYear.events` | les grosses dépenses de l'année source : `label`, `amountCents` (positif), `month`, `bookedAt`, `status` |
| `plannedCents` | planifié + engagé sur l'année cible |
| `toArbitrateCents` | à arbitrer sur l'année cible, rendu à part, jamais sommé |
| `plannedEvents` | les plans déjà posés sur l'année cible : `id`, `label`, `amountCents` (positif), `month`, `bookedAt`, `status` |
| `envelopeId`, `envelopeCents` | l'enveloppe annuelle déjà posée sur l'année cible, `null` sinon |
| `suggestedCents` | `plannedCents` s'il est > 0, sinon `lastYear.consumedCents` |

Enveloppe du retour : `year`, `sourceYear`, `thresholdCents`, `categories`, et les totaux `totalLastYearConsumedCents`, `totalPlannedCents`, `totalSuggestedCents`, `totalEnvelopedCents`.

### Écriture — `UseCase/ApplyAnnualPlan`

`execute(User $user, int $year, array $events, array $envelopes, ?string $accountId): array`.

- `events[]` : `categoryId`, `label`, `amountCents` (> 0), `month` (1-12, défaut 1), `status` (`planned` | `committed` | `to_arbitrate`, défaut `planned`), `accountId` (sinon celui du corps), `currency`. Dispatch `CreateTransactionCommand` avec `-amountCents` et `bookedAt` au 1er du mois.
- `envelopes[]` : `categoryId`, `amountCents` (≥ 0). Enveloppe **annuelle** de l'année cible : créée si absente (`CreateEnvelopeCommand`), mise à jour si le montant diffère (`UpdateEnvelopeCommand`), laissée seule sinon.
- Entrée invalide → `\InvalidArgumentException`, traduite en 400 par le contrôleur et en `{"error": …}` par l'outil MCP.
- Retour : `eventsCreated`, `envelopesCreated`, `envelopesUpdated`, `envelopesUnchanged`, et les listes `events` / `envelopes` écrites.

### Dépôt

Deux méthodes sur `TransactionRepository`, toutes deux sur les débits catégorisés d'une période semi-ouverte :
- `findNotableDebitsBetween(User, from, until, int $minAmountCents)` — statuts `spent` + `committed`, `abs(amountCents) >= min`, les plus grosses d'abord ;
- `findPlansBetween(User, from, until)` — statuts `planned`, `committed`, `to_arbitrate`, par date croissante.

### HTTP et MCP

- `Controller/AnnualPlanController` — `GET /api/finance/annual-plan` (`year` facultatif : année suivante en novembre/décembre, année courante sinon ; `thresholdCents` facultatif) et `POST /api/finance/annual-plan`. 401 hors session, 400 sur une année ou un seuil hors bornes et sur un corps invalide.
- `Mcp/Tool/PlanAnnualBudgetTool` — outil `plan_annual_budget`, actions `review`, `schedule`, `budget`. Un événement à la fois : aucun paramètre tableau, c'est ainsi qu'on dicte une liste. Pas d'action en `add_*` ni en `plan_*` : `McpToolsContractTest` les lit comme des noms d'outils, et `add_event` entrerait en collision avec `create_event` de l'agenda.

### Tests
- `tests/Api/AnnualPlanApiTest.php` : 401 sur les deux verbes ; 400 (année hors bornes, montant ≤ 0, statut `spent`, `accountId` manquant, catégorie inconnue) ; lecture (grosses dépenses au-dessus du seuil seulement, plan déjà posé, suggéré selon les deux règles, à arbitrer isolé) ; application (transaction en débit au 1er du mois, enveloppe annuelle créée puis mise à jour puis inchangée) + Mercure + Elasticsearch.
- `tests/Mcp/PlanAnnualBudgetToolTest.php` : `review`, `schedule`, `budget`, action inconnue, paramètres manquants, isolation par utilisateur.
- Contrat : `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` (OpenAPI + liste des outils MCP).

## Tâche 2 — Parcours guidé admin React (ticket de suite)
Page `/finance/annual-plan` : sélecteur d'année, une ligne par catégorie (consommé l'an dernier, grosses dépenses à reconduire, plans de l'année cible, montant d'enveloppe éditable pré-rempli sur le suggéré), ajout d'un événement, bouton de validation du plan. Hook `useAnnualPlan`, entrée de menu, tests Vitest (rendu, interaction, erreur).

## Tâche 3 — Parcours guidé mobile Kotlin (ticket de suite)
Modèle `AnnualPlan`, `MaggieApiService`, `AnnualPlanRepository`, `AnnualPlanViewModel` + écran, entrée « Réglages finance » (MAG-196) et lien `maggie://finance/annual-plan`, tests MockK du ViewModel.

## Parcours e2e

Étend **MAG-102** (parcours finance). Couvert dans la tâche 1 par le canal conversationnel, puis par une journée Playwright dans la tâche 2 et une journée Maestro dans la tâche 3.

```
Étant donné une grosse dépense « FESTIVAL VIEILLES CHARRUES » de 240,00 € en Loisirs sur l'année N-1
  et aucune enveloppe annuelle Loisirs sur l'année N
Quand je demande à Maggie de préparer ma planification annuelle de l'année N
Alors elle me rend la dépense de l'an dernier et un montant suggéré de 240,00 €
Quand je lui dis de planifier le festival en juillet et de poser l'enveloppe à 300,00 €
Alors une transaction planifiée de −240,00 € existe au 1er juillet de l'année N en Loisirs
  et l'enveloppe annuelle Loisirs de l'année N vaut 300,00 €
  et le budget de l'année N montre 240,00 € réservés et 60,00 € disponibles
```

## Ordre de dépendances
Dépôt → lecture → écriture → HTTP → MCP → journée e2e. La lecture est testée avant l'écriture : c'est elle qui définit ce que la session propose, et l'écriture ne fait que valider ce qui a été proposé.

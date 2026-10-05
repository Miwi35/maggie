# Maggie Finance — Session de planification annuelle — Plan

Tranche découpée **par couche**, pas en une tranche verticale : l'API + l'outil MCP (MAG-48), puis l'écran admin, puis l'écran mobile. La tranche verticale entière dépasse la limite de taille du garde-fou (800 lignes hors tests) ; le repo demande alors de découper le ticket.

Testing Rule du repo respectée à chaque tâche (401, 400, happy path + persistance DB, Mercure, Elasticsearch).

## Tâche 1 — API + outil MCP (MAG-48)

### Lecture — `UseCase/GetAnnualPlan`

`execute(User $user, int $year, int $thresholdCents = 10_000): array`, année source = `$year - 1`.

Catégories retenues : **toutes celles que l'année source a coûtées** (`sumSpendingByCategoryBetween`, une requête, pas une par catégorie), plus celles qui ont une enveloppe annuelle sur l'une des deux années ou un plan sur l'année cible. Les débits sans catégorie sont écartés : la session budgète des catégories. Triées par montant suggéré décroissant — la plus grosse décision d'abord. Attention : `IDENTITY()` rend la valeur brute de la colonne, pas l'écriture du ULID — à normaliser avec `Ulid::fromString`, sinon une catégorie récolte deux lignes.

Par catégorie :

| Champ | Contenu |
|---|---|
| `categoryId`, `categoryName`, `currency` | la catégorie, et la devise dans laquelle l'enveloppe de la catégorie est ou sera posée : celle d'une enveloppe de l'une des deux années, sinon `EUR` — jamais celle d'une transaction, les lignes d'une catégorie pouvant être en plusieurs devises |
| `lastYear.budgetedCents` | l'enveloppe annuelle de l'année source, `null` s'il n'y en avait pas |
| `lastYear.consumedCents` | dépensé + engagé sur l'année source |
| `lastYear.events` | les grosses dépenses de l'année source : `label`, `amountCents` (positif), `month`, `bookedAt`, `status`, `isExceptional` |
| `plannedCents`, `committedCents` | planifié et engagé sur l'année cible, séparés comme dans `GetBudgetStatus` |
| `decidedCents` | `plannedCents + committedCents` : ce que l'année cible a déjà engagé d'une manière ou d'une autre |
| `toArbitrateCents` | à arbitrer sur l'année cible, rendu à part, jamais sommé dans le décidé |
| `plannedEvents` | les plans déjà posés sur l'année cible : `id` puis les mêmes champs qu'un événement |
| `envelopeId`, `envelopeCents` | l'enveloppe annuelle déjà posée sur l'année cible, `null` sinon |
| `suggestedCents` | `decidedCents` s'il est > 0, sinon `lastYear.consumedCents` |

Enveloppe du retour : `year`, `sourceYear`, `thresholdCents`, `categories`, et les totaux `totalLastYearConsumedCents`, `totalDecidedCents`, `totalToArbitrateCents`, `totalSuggestedCents`, `totalEnvelopedCents`.

### Écriture — `UseCase/ApplyAnnualPlan`

`execute(User $user, int $year, array $events, array $envelopes, ?string $accountId): array`.

**Tout est lu et validé avant la première écriture** (shape, décision 10).

- `events[]` : `categoryId`, `label` (non vide, ≤ 255), `amountCents` (> 0, ≤ 2 147 483 647), `month` (1-12, défaut 1), `status` (`planned` | `committed` | `to_arbitrate`, défaut `planned`), `accountId` (sinon celui du corps), `currency` (défaut : celle du compte, vérifiée `/^[A-Z]{3}$/`), `isExceptional` (défaut `false`). Dispatch `CreateTransactionCommand` avec `-amountCents` et `bookedAt` au 1er du mois.
- **Ce que les colonnes acceptent est vérifié ici, avec le reste** : rien ne valide une commande sur le bus, donc une devise de quatre lettres, un libellé de 300 caractères ou un montant plus large qu'un `integer` atteindraient le pilote — un 400 avec les premières lignes déjà écrites, soit exactement ce que l'ordre lire-puis-écrire existe pour empêcher.
- `envelopes[]` : `categoryId`, `amountCents` (≥ 0). Enveloppe **annuelle** de l'année cible : créée si absente (`CreateEnvelopeCommand`), mise à jour si le montant diffère (`UpdateEnvelopeCommand`), laissée seule sinon. L'enveloppe existante est relue au moment d'écrire, pas de valider : un même corps peut nommer deux fois la même catégorie.
- Compte et catégorie : `Ulid::isValid` puis `find`, puis comparaison du propriétaire. Inconnu, mal formé et « à quelqu'un d'autre » rendent la même erreur — un nom de catégorie à la place d'un id est l'erreur la plus probable avec un outil conversationnel, et Doctrine y répond par une erreur de conversion, pas par `null`.
- Entrée invalide → `\InvalidArgumentException`, traduite en 400 par le contrôleur et en `{"error": …}` par l'outil MCP.
- Retour : `eventsCreated`, `envelopesCreated`, `envelopesUpdated`, `envelopesUnchanged`, et les listes `events` / `envelopes` écrites, chaque enveloppe portant son `outcome`.

### Dépôt

Deux méthodes sur `TransactionRepository`, toutes deux sur les débits catégorisés d'une période semi-ouverte :
- `findNotableDebitsBetween(User, from, until, int $minAmountCents)` — statuts `spent` + `committed`, `abs(amountCents) >= min`, les plus grosses d'abord ;
- `findPlansBetween(User, from, until)` — statuts `planned`, `committed`, `to_arbitrate`, par date croissante.

### HTTP et MCP

- `UseCase/PlanningYear` — l'année par défaut et les bornes acceptées, une seule fois pour les deux canaux.
- `Controller/AnnualPlanController` — `GET /api/finance/annual-plan` (`year` facultatif : année suivante en novembre/décembre, année courante sinon ; `thresholdCents` facultatif) et `POST /api/finance/annual-plan`. 401 hors session ; 400 sur un `year` ou un `thresholdCents` non entier (`(int) 'abc'` vaut 0, ce qui ferait de tout débit une grosse dépense), hors bornes ou négatif, et sur un corps invalide. Le POST rattrape aussi `HandlerFailedException` en 400 : une commande refusée par son handler reste de la mauvaise entrée, et l'outil MCP y répond déjà ainsi.
- `Mcp/Tool/PlanAnnualBudgetTool` — outil `plan_annual_budget`, actions `review`, `schedule`, `budget`. Un événement à la fois : aucun paramètre tableau, c'est ainsi qu'on dicte une liste. Pas d'action en `add_*` ni en `plan_*` : `McpToolsContractTest` les lit comme des noms d'outils, et `add_event` entrerait en collision avec `create_event` de l'agenda.

### Tests
- `tests/UseCase/PlanningYearTest.php` : les deux branches de l'année par défaut sur des instants fixes (pas sur l'horloge : la règle ne change de comportement qu'en novembre et décembre), et les bornes.
- `tests/Controller/AnnualPlanControllerTest.php` : 401 sur les deux verbes ; 400 (année ou seuil non entier / hors bornes, montant ≤ 0, mois hors bornes, statut `spent` ou inconnu, devise qui n'est pas un code, `accountId` manquant, compte ou catégorie d'un autre utilisateur, catégorie inexistante, nom de catégorie à la place d'un id) ; **un plan refusé n'écrit rien** (comptes de transactions et d'enveloppes inchangés) ; lecture (grosses dépenses au-dessus du seuil seulement, seuil qui ne bouge aucun total, une seule ligne par catégorie avec l'id que le client renvoie, plan déjà posé, suggéré selon les deux règles, à arbitrer isolé) ; application (transaction en débit au 1er du mois, devise du compte, `isExceptional`, enveloppe annuelle créée puis mise à jour puis inchangée, même catégorie deux fois dans un corps) + Mercure + Elasticsearch.
- `tests/Mcp/PlanAnnualBudgetToolTest.php` : `review`, `schedule`, `budget`, action inconnue, **appel sans utilisateur lié**, paramètres manquants, année hors bornes, compte et catégorie d'un autre utilisateur, isolation en lecture.
- Contrat : `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` (OpenAPI + liste des outils MCP).

## Tâche 2 — Parcours guidé admin React (ticket de suite)
Page `/finance/annual-plan` : sélecteur d'année, une ligne par catégorie (consommé l'an dernier, grosses dépenses à reconduire, plans de l'année cible, montant d'enveloppe éditable pré-rempli sur le suggéré), ajout d'un événement, bouton de validation du plan. Hook `useAnnualPlan`, entrée de menu, tests Vitest (rendu, interaction, erreur).

## Tâche 3 — Parcours guidé mobile Kotlin (ticket de suite)
Modèle `AnnualPlan`, `MaggieApiService`, `AnnualPlanRepository`, `AnnualPlanViewModel` + écran, entrée « Réglages finance » (MAG-196) et lien `maggie://finance/annual-plan`, tests MockK du ViewModel.

## Parcours e2e

Étend **MAG-102** (parcours finance). Couvert dans la tâche 1 par le canal conversationnel, puis par une journée Playwright dans la tâche 2 et une journée Maestro dans la tâche 3.

`e2e/web/tests/finance-annual-plan.spec.ts`, avec le scénario `agent/fixtures/fake-llm/48-annual-plan.yaml` :

```
Étant donné une catégorie « Festivals » et une grosse dépense de 480,00 € dedans sur l'année N-1
  et aucune enveloppe annuelle sur l'année N
Quand je demande à Maggie de préparer ma planification annuelle, en lui disant que le festival
  coûtera 240,00 € en juillet
Alors les trois tours de `plan_annual_budget` ont réussi
  et le plan de l'année N rend la dépense de 480,00 € de l'an dernier comme candidate
  et une transaction planifiée de −240,00 € existe au 1er juillet de l'année N dans cette catégorie
  et l'enveloppe annuelle de l'année N vaut 300,00 €
  et le suggéré vaut 240,00 € — le décidé de l'année cible l'emporte sur le consommé de l'an dernier
  et le budget de l'année N montre 240,00 € réservés, 0 consommé et 60,00 € disponibles
```

## Ordre de dépendances
Dépôt → lecture → écriture → HTTP → MCP → journée e2e. La lecture est testée avant l'écriture : c'est elle qui définit ce que la session propose, et l'écriture ne fait que valider ce qui a été proposé.

# Maggie Finance — Session de planification annuelle — Plan

Découpée en tranches qui tiennent sous la limite du garde-fou (800 lignes hors tests), dans l'ordre de dépendances : **lecture du plan** (MAG-48), puis **application en un geste**, puis l'écran admin, puis l'écran mobile. La lecture définit ce que la session propose ; l'écriture ne fait que valider ce qui a été proposé, et elle est déjà possible avec `manage_envelopes` et `manage_transactions`.

Testing Rule du repo respectée à chaque tâche. **Exemptions de la tâche 1, qui ne fait que lire** : `N/A — état DB, Mercure et Elasticsearch` (rien n'est écrit, comme pour la revue mensuelle) et `N/A — action inconnue et argument requis manquant` (l'outil n'a pas d'action et ses deux paramètres sont facultatifs). La tâche 2 les rétablit toutes.

## Tâche 1 — Lecture du plan : API + outil MCP (MAG-48)

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

### Dépôt

Deux méthodes sur `TransactionRepository`, toutes deux sur les débits catégorisés d'une période semi-ouverte :
- `findNotableDebitsBetween(User, from, until, int $minAmountCents)` — statuts `spent` + `committed`, `abs(amountCents) >= min`, les plus grosses d'abord ;
- `findPlansBetween(User, from, until)` — statuts `planned`, `committed`, `to_arbitrate`, par date croissante.

### HTTP et MCP

- `UseCase/PlanningYear` — l'année par défaut et les bornes acceptées, une seule fois pour les deux canaux.
- `Controller/AnnualPlanController` — `GET /api/finance/annual-plan` (`year` facultatif : année suivante en novembre/décembre, année courante sinon ; `thresholdCents` facultatif). 401 hors session ; 400 sur un `year` ou un `thresholdCents` non entier (`(int) 'abc'` vaut 0, ce qui ferait de tout débit une grosse dépense), hors bornes ou négatif.
- `Mcp/Tool/PlanAnnualBudgetTool` — outil `plan_annual_budget`, sans action : il rend le plan. Sa description renvoie à `manage_envelopes` (mode `annual`) et `manage_transactions` pour écrire ce que l'utilisateur a décidé, tant que la tâche 2 n'est pas livrée, et dit qu'il ne touche pas à l'agenda — une dépense planifiée est une transaction, pas un rendez-vous.

### Tests
- `tests/UseCase/PlanningYearTest.php` : les deux branches de l'année par défaut sur des instants fixes (pas sur l'horloge : la règle ne change de comportement qu'en novembre et décembre), et les bornes.
- `tests/Controller/AnnualPlanControllerTest.php` : 401 ; 400 (année ou seuil non entier, hors bornes, négatif) ; une seule ligne par catégorie avec l'id que le client relit, grosses dépenses au-dessus du seuil seulement et les plus grosses d'abord, seuil qui ne bouge aucun total, budget de l'année source, plan déjà posé avec les trois statuts séparés, suggéré selon les deux règles, à arbitrer isolé, enveloppe mensuelle qui n'est pas un budget annuel, crédit et année hors fenêtre écartés, isolation entre utilisateurs, année par défaut.
- `tests/Mcp/PlanAnnualBudgetToolTest.php` : le plan, l'année par défaut, le seuil, **appel sans utilisateur lié**, année hors bornes, isolation en lecture.
- Contrat : `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` (liste des outils MCP).

## Tâche 2 — Application du plan en un geste (ticket de suite)

`UseCase/ApplyAnnualPlan` + `POST /api/finance/annual-plan` + deux actions d'écriture sur l'outil MCP (un événement à la fois : aucun paramètre tableau, c'est ainsi qu'on dicte une liste ; pas d'action en `add_*` ni en `plan_*`, `McpToolsContractTest` les lit comme des noms d'outils et `add_event` entrerait en collision avec `create_event` de l'agenda).

`execute(User $user, int $year, array $events, array $envelopes, ?string $accountId): array`.

**Tout est lu et validé avant la première écriture** (shape, décision 10).

- `events[]` : `categoryId`, `label` (non vide, ≤ 255), `amountCents` (> 0, ≤ 2 147 483 647), `month` (1-12, défaut 1), `status` (`planned` | `committed` | `to_arbitrate`, défaut `planned`), `accountId` (sinon celui du corps), `currency` (défaut : celle du compte, vérifiée `/^[A-Z]{3}$/`), `isExceptional` (défaut `false`). Dispatch `CreateTransactionCommand` avec `-amountCents` et `bookedAt` au 1er du mois.
- **Ce que les colonnes acceptent est vérifié là, avec le reste** : rien ne valide une commande sur le bus, donc une devise de quatre lettres, un libellé de 300 caractères ou un montant plus large qu'un `integer` atteindraient le pilote — un 400 avec les premières lignes déjà écrites, soit exactement ce que l'ordre lire-puis-écrire existe pour empêcher.
- `envelopes[]` : `categoryId`, `amountCents` (≥ 0). Enveloppe **annuelle** de l'année cible : créée si absente, mise à jour si le montant diffère, laissée seule sinon. L'enveloppe existante est relue au moment d'écrire, pas de valider : un même corps peut nommer deux fois la même catégorie.
- Compte et catégorie : `Ulid::isValid` puis `find`, puis comparaison du propriétaire. Inconnu, mal formé et « à quelqu'un d'autre » rendent la même erreur — un nom de catégorie à la place d'un id est l'erreur la plus probable avec un outil conversationnel, et Doctrine y répond par une erreur de conversion, pas par `null`.
- Retour : `eventsCreated`, `envelopesCreated`, `envelopesUpdated`, `envelopesUnchanged`, et les listes écrites, chaque enveloppe portant son `outcome`.
- Tests : 401, 400 sur chacune des règles ci-dessus, **un plan refusé n'écrit rien** (comptes de transactions et d'enveloppes inchangés), happy path + état DB + Mercure + Elasticsearch, même catégorie deux fois dans un corps ; côté MCP, appel sans utilisateur lié, année hors bornes, compte et catégorie d'un autre utilisateur. Le parcours e2e étend la journée de la tâche 1 : la même conversation planifie et pose l'enveloppe, puis le budget de l'année montre le réservé et le disponible.

## Tâche 3 — Parcours guidé admin React (ticket de suite)
Page `/finance/annual-plan` : sélecteur d'année, une ligne par catégorie (consommé l'an dernier, grosses dépenses à reconduire, plans de l'année cible, montant d'enveloppe éditable pré-rempli sur le suggéré), ajout d'un événement, bouton de validation du plan. Hook `useAnnualPlan`, entrée de menu, tests Vitest (rendu, interaction, erreur).

## Tâche 4 — Parcours guidé mobile Kotlin (ticket de suite)
Modèle `AnnualPlan`, `MaggieApiService`, `AnnualPlanRepository`, `AnnualPlanViewModel` + écran, entrée « Réglages finance » (MAG-196) et lien `maggie://finance/annual-plan`, tests MockK du ViewModel.

## Parcours e2e

Étend **MAG-102** (parcours finance). Couvert dans la tâche 1 par le canal conversationnel, puis par une journée Playwright dans la tâche 3 et une journée Maestro dans la tâche 4.

`e2e/web/tests/finance-annual-plan.spec.ts`, avec le scénario `agent/fixtures/fake-llm/48-annual-plan.yaml` :

```
Étant donné une catégorie « Festivals », une dépense de 480,00 € dedans sur l'année N-1
  et une petite de 9,00 € le lendemain
  et aucune enveloppe annuelle sur l'année N
Quand je demande à Maggie de préparer ma planification annuelle
Alors `plan_annual_budget` a tourné et a réussi
  et seule sa dernière réplique reste à l'écran (MAG-229)
  et le plan de l'année N compte 489,00 € consommés l'an dernier
  et ne propose que la dépense de 480,00 € comme candidate à reconduire
  et suggère 489,00 € — rien n'est décidé sur l'année N, donc c'est le consommé qui parle
```

La tâche 2 prolonge la même conversation : planifier le festival en juillet, poser l'enveloppe, et vérifier que le budget de l'année N montre le réservé et le disponible.

## Ordre de dépendances
Dépôt → lecture → HTTP → MCP → journée e2e, puis l'écriture, puis les écrans. Chaque tranche est livrable seule : la lecture seule rend déjà la session tenable en conversation, l'écriture en fait un seul geste.

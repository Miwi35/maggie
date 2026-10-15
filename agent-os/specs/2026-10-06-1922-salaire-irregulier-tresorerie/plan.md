# Salaire irrégulier et trésorerie — Plan

Douze tâches : la première sauvegarde le cadrage, les **onze suivantes font un ticket Linear chacune**, livrable seule et sous la limite du garde-fou (800 lignes hors tests). L'ordre est celui du projet : les virements internes et le salaire de référence corrigent les chiffres tout de suite, les avances et la trésorerie s'appuient dessus.

Les décisions numérotées renvoient à [`shape.md`](./shape.md).

## Modèle de données — vue d'ensemble

Trois migrations additives, aucune destructive.

| Tâche | Migration | Contenu |
|---|---|---|
| 2 | `transaction` | `transfer_kind` (`varchar(20)`, défaut `none`), `transfer_source` (`varchar(20)`, défaut `auto`), `counterpart_id` (`ulid` nullable, FK auto-référente `ON DELETE SET NULL`), index sur `(user_id, transfer_kind)` |
| 4 | `category` | `is_salary` (`boolean`, défaut `false`) |
| 8 | `transaction`, `treasury_settings` | `repays_id` (`ulid` nullable, FK auto-référente `ON DELETE SET NULL`) ; table `treasury_settings` (`id` ulid, `user_id` ulid unique, `salary_expected_day` int nullable) |
| 11 | `treasury_settings` | `overdraft_limit_cents` (int, défaut `0`) |

Énums nouvelles : `TransferKind` (`none`, `internal`, puis `advance`, `repayment`, `funding` en tâche 8) et `TransferSource` (`auto`, `manual`).

## Tâche 1 — Sauvegarder la documentation de spec

Créer `agent-os/specs/2026-10-06-1922-salaire-irregulier-tresorerie/` avec `shape.md`, `plan.md`, `standards.md`, `references.md`, et inscrire le projet dans `agent-os/product/finance-roadmap.md` (couche Optimisation, après `finance-independence-counter`). Pas de `visuals/` : aucun visuel fourni.

Tests : `N/A — documentation`. E2E : `N/A — documentation`.

## Tâche 2 — Virements internes : modèle, détection et exclusion des calculs (API + MCP) — MAG-271

**Bloquée par [MAG-46](https://linear.app/meven/issue/MAG-46)** : son PR introduit `MeasureMonthlyLifestyle` et `sumPassiveIncomeByCategoryBetween` dans les deux fichiers que cette tâche modifie le plus.

### Modèle

`Transaction` reçoit `transferKind` (`TransferKind`, défaut `None`), `transferSource` (`TransferSource`, défaut `Auto`) et `counterpart` (auto-référence `ManyToOne` nullable, `onDelete: 'SET NULL'`). Les trois entrent dans `toSearchDocument()` et dans `toMercurePayload()` (`counterpartId` pour la relation, comme `accountId` et `categoryId`), et `transferKind` + `transferSource` sont des `IndexedField(type: 'keyword')`.

Méthode de domaine `Transaction::isInternalTransfer(): bool` — `TransferKind::None !== $this->transferKind` — et `markAsInternalTransfer(Transaction $counterpart, TransferSource $source)`, qui pose les deux côtés, à l'image de `assignCategory()`.

### Détection — `UseCase/DetectInternalTransfers`

`detectFor(Transaction $transaction): ?Transaction` cherche la contrepartie d'une ligne, `execute(User $user, ?int $limitDays = null): array` repasse l'historique.

Règles (décisions 5 et 6) : même utilisateur, **comptes différents** appartenant tous deux à l'utilisateur, `amountCents` exactement opposés, même `currency`, `|bookedAt écart| <= 4 jours`, statuts dans (`spent`, `committed`), `counterpart IS NULL` des deux côtés, et `transferSource != manual` sur la ligne candidate — une décision prise à la main n'est jamais écrasée, comme `findUncategorizedForUser` respecte `CategorySource::Manual`. Tri des candidats : écart de date croissant, puis `bookedAt` croissant, puis ULID croissant. Au plus une contrepartie.

Nouvelle méthode de dépôt `findTransferCandidates(Transaction $transaction, int $windowDays): array` — une requête, pas une par candidat.

Branchement à la création : `CreateTransactionHandler` appelle la détection après l'appel existant à `CategorizeTransaction`. Rattrapage : `POST /api/finance/internal-transfers/detect` (`UseCase/DetectInternalTransfers::execute`), sur le modèle de `ApplyCategorizationRulesController`, avec `dryRun` pour montrer ce qu'il ferait avant de le faire.

### Exclusion (décision 4)

`andWhere('t.transferKind = :noTransfer')` dans les huit méthodes de `TransactionRepository` qui agrègent ou listent des mouvements : `sumConsumedBetween`, `sumMonthlyFlowsBetween`, `sumSpendingByCategoryBetween`, `sumByStatusForCategoryBetween`, `findReviewableBetween`, `findNotableDebitsBetween`, `findPlansBetween`, `sumPassiveIncomeByCategoryBetween`. `countMatching` (dédoublonnage d'import) et `findByUser` / `findByAccount` (listes brutes) ne filtrent rien : un virement interne reste une ligne du relevé.

### MCP

`manage_transactions` reçoit les paramètres `transferKind` et `counterpartId` sur `update` (pas d'action nouvelle : `McpToolsContractTest` lit les noms d'actions comme des noms d'outils), et `transferKind` figure dans `clear` pour revenir à `none`. Marquer ou démarquer à la main pose `transferSource = manual`. Outil `detect_internal_transfers` pour le rattrapage, avec `dryRun`.

### Tests

- `tests/UseCase/DetectInternalTransfersTest.php` : paire exacte appariée ; montants proches mais non opposés ignorés ; devises différentes ignorées ; 5 jours d'écart ignorés, 4 appariés ; même compte ignoré ; `planned` et `to_arbitrate` ignorés ; ligne déjà appariée laissée tranquille ; `transferSource = manual` jamais écrasé ; **trois candidats du même montant → le plus proche en date, et deux exécutions donnent le même résultat** ; isolation entre utilisateurs.
- `tests/Controller/DetectInternalTransfersControllerTest.php` : 401 ; 400 sur `limitDays` non entier ou négatif ; happy path avec état DB (les deux lignes marquées, `counterpart` croisé), Mercure sur les deux, Elasticsearch sur les deux ; `dryRun` n'écrit rien.
- `tests/Repository/TransactionRepositoryTest.php` : **un virement interne ne compte ni dans le consommé, ni dans les flux mensuels, ni par catégorie, ni dans l'enveloppe, ni dans la file de revue, ni dans les grosses dépenses, ni dans les plans, ni dans les rentes** — un cas par méthode, et le cas du propriétaire en entier (cf. *Cas du propriétaire*, cas 5).
- `tests/Mcp/ManageTransactionsToolTest.php` : marquage et démarquage manuels, `transferSource` posé à `manual`, appel sans utilisateur lié, contrepartie d'un autre utilisateur refusée.
- `tests/Mcp/DetectInternalTransfersToolTest.php` : rattrapage, `dryRun`, appel sans utilisateur lié.
- `tests/Entity/TransactionTransferTest.php` : les trois champs dans la charge utile Mercure (complète et différentielle) et dans le document Elasticsearch, aller-retour par l'hydrateur compris, et la symétrie des deux jambes.
- Contrat : `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` (liste des outils MCP, OpenAPI, réponses enregistrées).

## Tâche 3 — Virements internes : badge et correction, admin et mobile — MAG-272

Admin : colonne/badge « Virement interne » dans `AccountTransactionsView` et `TransactionEdit`, bouton « Ce n'est pas un virement interne » / « C'est un virement interne » (avec choix de la contrepartie parmi les candidats), hook `useDetectInternalTransfers` et bouton de rattrapage à côté de celui des règles de catégorisation. Mobile : badge dans `TransactionListScreen`, bascule dans l'écran de détail, `TransactionViewModel`.

Tests : Vitest (rendu avec et sans badge, bascule, erreur API) ; MockK sur `TransactionViewModel` (une transition d'état par cas : chargement, succès, erreur) ; test d'écran Compose pour le badge.

## Tâche 4 — Salaire de référence : le montant attendu de l'opération récurrente « Salaire » (API + MCP) — MAG-273

**Recadrée le 8 oct. (propriétaire)** : le salaire n'est pas un drapeau, c'est l'opération récurrente de recette dont la catégorie est « Salaire » (projet « Opérations récurrentes », MAG-304/305). Remplace la décision 8 de `shape.md`.

### Modèle

`Category::$standardKey` (colonne `standard_key`, unique par utilisateur, lecture seule en REST, dans la charge Mercure et le document ES) : `salary` sur « Salaire », `bonus` sur « Primes », nouvelle catégorie standard de recette. Un renommage garde la clé. `app:finance:categories:tag-standard [email]` (idempotente, par le bus) pose la clé sur une catégorie racine du même nom faite à la main ; `InstallStandardCategories` le fait aussi.

### Lecture — `UseCase/GetReferenceSalary`

Opérations récurrentes actives aujourd'hui, de recette, dont la catégorie (ou son parent) a la clé `salary`, hors rente. Par opération : `declared` → son montant de référence (`expected`) ; `measured` → moyenne de ses 12 derniers versements rattachés, hors virements internes, rejets et crédits exceptionnels (`measured`), son montant de référence s'il n'en a aucun. Ramené au mois (`perYear / 12`), additionné. Le salaire net déclaré sur le matelas gagne s'il est > 0. Rien : `monthlyCents = null`, `source = unknown`, `isConfigured = false`.

Retour : `monthlyCents`, `source` (`declared` | `expected` | `measured` | `unknown`), `isConfigured`, `declaredMonthlyCents`, `operations[]` (id, label, categoryName, period, referenceAmountCents, monthlyCents, source, paymentCount).

### HTTP et MCP

`GET /api/finance/reference-salary` — 401 hors session, pas de paramètre. Outil MCP `get_reference_salary`, sans argument.

### Tests

`GetReferenceSalaryTest` (chaque source, prime, deux versements le même jour, renommage, deux employeurs, trimestriel, série terminée, sous-catégorie, lecture pure), `ReferenceSalaryControllerTest`, `GetReferenceSalaryToolTest`, `TagStandardCategoriesCommandTest`, `StandardCategoriesControllerTest`, `CategoryApiTest`. *Exemptions écrites :* `N/A — état DB, Mercure et Elasticsearch` et `N/A — 400` pour l'endpoint et l'outil (lecture pure, aucun paramètre).

## Tâche 5 — Le salaire de référence dans la revue mensuelle, le score et le matelas (API) — MAG-274

**Bloquée par la tâche 4.**

- `GetMonthlyReview` : `referenceSalary` (la charge utile de `GetReferenceSalary`, réduite à `monthlyCents` et `source`) et `consumedVsReference` — `consumedCents` (le train de vie du mois, `sumConsumedBetween`), `differenceCents`, `percentOfReference` (`null` quand le repère est inconnu, jamais 0).
- `GetDailyScore` : raison `spending_above_reference_salary` avec son montant quand le consommé du mois dépasse le repère, et `referenceSalary` dans la charge utile. `decide()` reçoit `aboveReferenceSalary` et ne l'utilise **que** pour retenir le vert, exactement comme `cushionBlocksGreen` (décision 11) ; aucun chemin vers l'orange ou le rouge n'est touché.
- `GetCushionStatus` : `monthlyNetIncomeCents` vient de `GetReferenceSalary::monthlyCents`, `isConfigured` devient `monthlyCents > 0`, et la charge utile gagne `referenceSalarySource` pour que l'écran dise si le chiffre est déclaré ou mesuré (décision 10). `SafetyCushion::getTargetCents()` n'est pas touché : il multiplie ce qu'on lui donne.
- `GetFinanceDashboard` : rien à écrire, il compose — mais ses tests vérifient que le repère remonte bien dans `score` et `savingCapacity`.

### Tests

- `tests/UseCase/GetMonthlyReviewTest.php` / `GetDailyScoreTest.php` / `GetCushionStatusTest.php` : repère déclaré, repère mesuré, repère inconnu (`percentOfReference` nul, pas de raison ajoutée, matelas non configuré) ; **consommé au-dessus du repère → le vert est retenu, la couleur ne descend pas** ; consommé en dessous → le vert reste possible ; la cible du matelas suit le repère mesuré.
- `tests/Controller/MonthlyReviewControllerTest.php`, `DailyScoreControllerTest.php`, `CushionStatusControllerTest.php`, `FinanceDashboardControllerTest.php` : les nouveaux champs dans la réponse, 401 inchangé.
- Contrat : réponses enregistrées de la revue, du score, du matelas et du dashboard.

## Tâche 6 — Salaire de référence : affichage admin et mobile — MAG-275

Admin : ligne « Salaire de référence » sur `FinanceDashboardPage` et dans le panneau de revue mensuelle (montant, « déclaré » ou « mesuré sur 12 mois »), part du repère consommée. Pas de case « Salaire » sur la catégorie (MAG-273 : le salaire est l'opération récurrente de clé standard `salary`, `standardKey` en lecture seule) : repère inconnu → « non renseigné » et lien vers les opérations récurrentes. Mobile : même ligne sur `FinanceDashboardScreen` et `MonthlyReviewScreen` ; repère inconnu → lien vers le matelas.

Tests : Vitest (rendu déclaré / mesuré / inconnu, lien si inconnu, erreur API) ; MockK sur `FinanceDashboardViewModel`, `MonthlyReviewViewModel` (une transition par état) et test Compose de la ligne du dashboard.

## Tâche 7 — Avances d'épargne : réglages de trésorerie, détection et solde par source (API + MCP) — MAG-276

**Bloquée par la tâche 5** : la fenêtre de retard a besoin du repère pour savoir si un crédit est le salaire.

### Modèle

Entité `TreasurySettings` : `id` (ULID), `user` (`ManyToOne`, unique), `salaryExpectedDay` (`int` nullable, `Assert\Range(min: 1, max: 31)`), `MercurePublishable`, créée à la lecture comme `SafetyCushion` (décision 16). Commande `UpdateTreasurySettingsCommand` + handler + `PATCH`/`PUT` via un contrôleur REST dédié — jamais un `Patch` API Platform sur un `uriTemplate` sans identifiant (`/…/me` instancierait une entité neuve et rendrait 500, cf. `MeOperationContractTest`).

`Transaction` reçoit `repays` (auto-référence `ManyToOne` nullable, `onDelete: 'SET NULL'`) : la jambe débitrice d'un remboursement pointe la jambe débitrice de l'avance qu'il rembourse. `TransferKind` s'ouvre à `Advance`, `Repayment` et `Funding`.

### Retard de salaire — `UseCase/GetSalaryDelay`

`execute(User $user, ?\DateTimeImmutable $today = null): array` rend, pour le mois en cours et le précédent : `expectedOn`, `receivedCents`, `referenceCents`, `isReceived` (≥ 90 % du repère), `delayDays`, `shortfallCents`. Un crédit couvre les mois ouverts **du plus ancien au plus récent** : un virement à ~200 % le 26 ferme les deux (décision 13). Sans `salaryExpectedDay`, la réponse dit `isConfigured: false` et rien n'est « en retard ».

### Détection d'avance — `UseCase/DetectSavingsAdvances`

Un virement interne déjà apparié devient `advance` quand la jambe débitrice est sur un compte `savings` ou `investment`, la jambe créditrice sur un `checking`, et que `bookedAt` tombe dans un retard ouvert (décisions 12 et 13). `transferSource = manual` n'est jamais requalifié. Branchée derrière `DetectInternalTransfers`, à la création et au rattrapage.

### Solde par source — `UseCase/GetSavingsAdvances`

`advances[]` (id, `sourceAccountId`, `sourceAccountName`, `amountCents`, `openedOn`, `repaidCents`, `outstandingCents`, `isClosed`), `bySource[]` (compte, `outstandingCents`), `totalOutstandingCents`. `repaidCents` est la somme des remboursements qui pointent l'avance — rien n'est stocké (décision 1).

`GET /api/finance/savings-advances`, `GET`/`PATCH /api/finance/treasury-settings`, outils MCP `manage_treasury_settings` (`get`, `update`) et `get_savings_advances`.

### Tests

- `tests/UseCase/GetSalaryDelayTest.php` : **retard de 30 jours** (cas 1) ; **deux mois d'un coup le 26** (cas 2 — deux mois fermés, dans l'ordre) ; salaire partiel à 60 % → retard ouvert, manque réduit ; **une prime le même jour ne ferme rien** (cas 3) ; jour attendu non renseigné ; repère inconnu.
- `tests/UseCase/DetectSavingsAdvancesTest.php` : épargne → courant pendant un retard = avance ; hors retard = virement interne neutre ; courant → épargne jamais une avance ; compte `checking` → `checking` jamais une avance ; `manual` respecté ; isolation.
- `tests/UseCase/GetSavingsAdvancesTest.php` : solde par source avec deux sources ; avance remboursée en deux fois ; avance soldée exclue du total ; isolation.
- `tests/Entity/TreasurySettingsTest.php` + `MercurePublishMiddlewareTest` : Create/Update/Delete publiés.
- `tests/Controller/SavingsAdvancesControllerTest.php`, `TreasurySettingsControllerTest.php` : 401 ; 400 sur `salaryExpectedDay` hors 1-31 ou non entier ; happy path avec état DB, Mercure, Elasticsearch ; création à la lecture.
- `tests/Mcp/ManageTreasurySettingsToolTest.php`, `GetSavingsAdvancesToolTest.php` : appel sans utilisateur lié, action inconnue, argument requis manquant, happy path avec état DB et Mercure, isolation.
- Contrat : nouvelle entité, nouveaux outils, nouveaux endpoints.

## Tâche 8 — Avances d'épargne : contre-virements proposés et rapprochement (API + MCP) — MAG-277

**Bloquée par la tâche 7.**

- `UseCase/ProposeAdvanceRepayments` : à l'arrivée du salaire, un contre-virement **par avance ouverte**, du montant **exact** du solde restant, du courant vers la source. Rend `proposals[]` (`fromAccountId`, `toAccountId`, `amountCents`, `advanceId`, `label` suggéré) et `totalCents`, plafonné au solde disponible du courant — et dit ce qu'il n'a pas pu proposer plutôt que de proposer un virement qui mettrait à découvert.
- `UseCase/ReconcileAdvanceRepayments` : un virement interne courant → source dont le montant égale **exactement** `outstandingCents` d'une avance ouverte de cette source devient `repayment` et pose `repays`. Tout le reste est laissé en `internal` (décision 14), et une ligne `funding` n'est jamais regardée (décision 15). Branché derrière la détection, à la création et au rattrapage.
- `manage_transactions` accepte `transferKind: funding` et `repaysTransactionId` à la main. Outil `propose_advance_repayments`.
- `GET /api/finance/savings-advances` gagne `proposals`.

### Tests

- `tests/UseCase/ProposeAdvanceRepaymentsTest.php` : une avance → un contre-virement du montant exact ; deux sources → deux propositions ; solde du courant insuffisant → proposition réduite et le reste annoncé ; aucune avance ouverte → aucune proposition.
- `tests/UseCase/ReconcileAdvanceRepaymentsTest.php` : montant exact → rapproché, `repays` posé, avance soldée ; **montant partiel → rien n'est rapproché** ; **virement d'approvisionnement régulier pendant un retard → jamais compté comme remboursement** (cas du propriétaire 4), aussi bien par le montant qui ne correspond pas que par le marquage `funding` ; mauvaise source ignorée ; `manual` respecté ; deux avances de même montant → la plus ancienne d'abord, résultat stable sur deux exécutions.
- `tests/Controller/SavingsAdvancesControllerTest.php` : `proposals` dans la réponse. `tests/Mcp/ProposeAdvanceRepaymentsToolTest.php` : appel sans utilisateur lié, isolation, happy path.
- Contrat : nouvel outil, réponse enrichie.

## Tâche 9 — Avances d'épargne : écran admin et écran mobile — MAG-278

Admin : page `/finance/savings-advances` — solde par source, avances ouvertes et soldées, contre-virements proposés avec bouton « Je l'ai fait » qui crée la transaction, réglage du jour de salaire attendu. Mobile : `SavingsAdvancesScreen` + `SavingsAdvancesViewModel`, entrée en section « Chaque mois » du dashboard finance (MAG-196), lien `maggie://finance/savings-advances`.

Tests : Vitest (rendu avec et sans avance, acceptation d'une proposition, erreur) ; MockK (une transition par état) ; test d'écran Compose.

## Tâche 10 — Vue trésorerie : lecture (API + MCP) — MAG-279

**Bloquée par la tâche 8.**

`TreasurySettings` reçoit `overdraftLimitCents` (`int`, défaut `0`, `Assert\PositiveOrZero`) : le découvert autorisé, 0 signifiant « tout solde négatif est un découvert ».

`UseCase/GetTreasuryOutlook::execute(User $user, ?\DateTimeImmutable $today = null): array` — ne lit que ce qui est saisi (décision 17) :

| Champ | Contenu |
|---|---|
| `salary` | `expectedOn`, `referenceCents`, `isReceived`, `delayDays`, `shortfallCents` (de `GetSalaryDelay`) |
| `balanceCents` | solde courant des comptes `checking` |
| `projection[]` | jour par jour jusqu'au salaire attendu, ou 60 jours au plus : `date`, `balanceCents`, les mouvements `planned` et `committed` du jour |
| `overdraft` | `isForecast`, `onDate`, `worstBalanceCents`, `limitCents` |
| `advice` | `amountCents` (ce qu'il manque pour ne pas passer sous la limite), `sourceAccountId`, `sourceAccountName`, `alternatives[]` quand la plus grosse source n'y suffit pas |
| `outstandingAdvancesCents` | ce qui reste à remettre, de `GetSavingsAdvances` |

Source conseillée : la source d'économies au solde le plus élevé, les comptes `isCushion` **en dernier** — entamer le filet de sécurité déclenche un plan de recharge (M7), donc on ne le propose qu'à défaut, et on le dit.

`GET /api/finance/treasury-outlook`, outil MCP `get_treasury_outlook`.

### Tests

- `tests/UseCase/GetTreasuryOutlookTest.php` : **retard de 30 jours avec découvert prévu dans 6 jours et avance conseillée sur la plus grosse source** (cas 1) ; salaire reçu → aucun découvert, aucune avance conseillée ; aucune source suffisante → alternatives listées ; seule source disponible marquée matelas → proposée en dernier et signalée ; `salaryExpectedDay` non renseigné → `isConfigured: false`, pas de retard ; limite de découvert > 0 respectée ; projection bornée à 60 jours ; isolation.
- `tests/Controller/TreasuryOutlookControllerTest.php` : 401 ; 400 sur `overdraftLimitCents` négatif (côté réglages) ; happy path. *Exemption écrite :* `N/A — état DB, Mercure et Elasticsearch` (lecture pure).
- `tests/Mcp/GetTreasuryOutlookToolTest.php` : appel sans utilisateur lié, isolation, happy path.
- Contrat : nouvel outil, nouvel endpoint, nouveau champ de réglage.

## Tâche 11 — Vue trésorerie : écran admin et écran mobile — MAG-280

Admin : page `/finance/treasury` — salaire attendu et retard en jours en tête, courbe du solde projeté avec la zone de découvert, avance conseillée avec sa source et son bouton, réglage de la limite de découvert. Mobile : `TreasuryScreen` + `TreasuryViewModel`, bandeau sur le dashboard finance quand un découvert est prévu, lien `maggie://finance/treasury`.

Tests : Vitest (rendu avec et sans découvert prévu, salaire reçu, erreur) ; MockK (une transition par état) ; test d'écran Compose pour le bandeau.

## Tâche 12 — Proaction : prévenir quand le salaire est en retard et qu'un découvert approche — MAG-281

**Bloquée par la tâche 10.**

Commande console `app:finance:treasury-alerts` (quotidienne), sur le modèle de `CheckRemindersCommand` : pour chaque utilisateur dont le salaire est en retard et dont `GetTreasuryOutlook` prévoit un découvert dans les 7 jours, déposer une proaction dans l'agenda interne (`ScheduleProactionCommand` par le bus, MAG-326 : Mercure et Elasticsearch partent) avec un prompt qui dit le retard, la date du découvert et l'avance conseillée. Une alerte par utilisateur et par jour, pas une par passage ; rien quand le salaire est arrivé ou qu'aucun découvert n'est prévu (décision 18).

Planifiée à 08:04 UTC (`.docker/php/crontab`). Idempotence : une proaction de résumé « Alerte trésorerie » existe déjà aujourd'hui (jour de Paris), quel que soit son état. **L'exécution côté agent dépend de MAG-327** (l'exécuteur de l'agent réclame les proactions de l'API) : tant qu'elle n'est pas fusionnée, l'alerte est déposée mais pas jouée.

### Tests

- `tests/Command/TreasuryAlertsCommandTest.php` : alerte publiée pour un retard avec découvert à 6 jours ; **rien quand le salaire est arrivé** ; rien quand le découvert est à 10 jours ; rien quand `salaryExpectedDay` n'est pas renseigné ; une seule alerte pour deux passages le même jour ; isolation entre utilisateurs.
- `agent/tests/test_proaction.py` : la proaction « trésorerie » exécutée rend un message et le range dans le fil, erreur de modèle comprise (respx).

## Cas du propriétaire — cas de test du plan

Les quatre cas donnés le 6 oct. sont des tests, pas des exemples.

| # | Cas | Où il est vérifié |
|---|---|---|
| 1 | **Un retard de 30 jours** : salaire attendu le 25, rien au 25 du mois suivant | `GetSalaryDelayTest` (`delayDays = 30`, `shortfallCents` = le repère), `GetTreasuryOutlookTest` (découvert prévu, avance conseillée), journée e2e trésorerie |
| 2 | **Deux mois versés d'un coup le 26** : un seul crédit à ~200 % du repère | `GetSalaryDelayTest` (les deux mois fermés, du plus ancien au plus récent, aucun retard restant), `GetReferenceSalaryTest` (le repère ne bouge pas : il est annuel / 12) |
| 3 | **Une prime**, le même jour que le salaire, dans sa catégorie « Primes » | `GetReferenceSalaryTest` (hors repère), `GetSalaryDelayTest` (ne ferme aucun retard), `InstallStandardCategoriesTest` (la catégorie existe) |
| 4 | **Un virement d'approvisionnement régulier pendant un retard**, courant → épargne | `ReconcileAdvanceRepaymentsTest` (jamais un remboursement, par le montant comme par le marquage `funding`), `DetectSavingsAdvancesTest` (jamais une avance : le sens est inverse) |
| 5 | **La recette du 5 oct.** : 4 950 € de revenus mesurés en 11 129 € de train de vie | `TransactionRepositoryTest` — deux comptes, un virement de 3 000 € apparié, et `sumConsumedBetween` qui rend le train de vie réel, pas la somme gonflée ; journée e2e virements internes |

## Tests

Par unité touchée, d'après `agent-os/standards/global/testing.md` :

| Unité | Tests |
|---|---|
| `DetectInternalTransfers` | chaque règle d'appariement et son contraire, déterminisme sur trois candidats, `manual` respecté, isolation |
| `DetectInternalTransfersController` | 401, 400, happy path + DB + Mercure + ES, `dryRun` n'écrit rien |
| `detect_internal_transfers`, `get_reference_salary`, `manage_treasury_settings`, `get_savings_advances`, `propose_advance_repayments`, `get_treasury_outlook` | sans utilisateur lié, action inconnue et argument requis manquant (quand l'outil en a), happy path + DB + Mercure + ES, isolation |
| `manage_transactions` (étendu) | marquage et démarquage manuels, `transferSource = manual`, contrepartie d'un autre utilisateur refusée |
| `Transaction`, `Category`, `TreasurySettings` | Create/Update/Delete dans `MercurePublishMiddlewareTest` ; validation « seule une recette peut être un salaire » |
| `TransactionRepository` | un cas d'exclusion par méthode agrégeante (huit), plus `findTransferCandidates` et `sumSalaryIncomeByCategoryBetween` : happy path et chaque branche d'erreur |
| `GetReferenceSalary`, `GetSalaryDelay`, `GetSavingsAdvances`, `ProposeAdvanceRepayments`, `ReconcileAdvanceRepayments`, `GetTreasuryOutlook` | happy path et chaque branche, repère ou jour attendu inconnus compris |
| `GetMonthlyReview`, `GetDailyScore`, `GetCushionStatus`, `GetFinanceDashboard` | les nouveaux champs, et **le vert retenu sans que la couleur descende** |
| `ReferenceSalaryController`, `SavingsAdvancesController`, `TreasurySettingsController`, `TreasuryOutlookController` | 401, 400, happy path ; exemptions Mercure/ES écrites sur les lectures pures |
| `app:finance:treasury-alerts` | alerte publiée, chaque cas de silence, une seule par jour, isolation |
| Admin (`AccountTransactionsView`, `TransactionEdit`, `CategoryEdit`, `FinanceDashboardPage`, `SavingsAdvancesPage`, `TreasuryPage`, hooks) | rendu initial, l'interaction pour laquelle le composant existe, état d'erreur ou vide |
| Mobile (`TransactionViewModel`, `CategoryViewModel`, `FinanceDashboardViewModel`, `MonthlyReviewViewModel`, `SavingsAdvancesViewModel`, `TreasuryViewModel`) | une transition d'état par cas : chargement, succès, erreur |
| `agent` proaction trésorerie | happy path et branche d'erreur, HTTP simulé avec respx |
| Contrat (`api/contract/`) | régénéré et commité à chaque tâche qui change la forme de l'API ou la liste des outils |

Aucune correction de bug dans ce périmètre : le projet est une fonctionnalité. Si la tâche 2 révèle que la recette du 5 oct. correspond à un `Bug` séparé, il porte son test de reproduction écrit rouge avant le correctif.

## Parcours e2e

**Étend [MAG-102](https://linear.app/meven/issue/MAG-102/parcours-e2e-finance-comptes-transactions-regles-enveloppes-score)** — parcours e2e finance. Trois journées Playwright, une par brique visible, plus une journée Maestro pour le mobile.

### Virements internes — `e2e/web/tests/finance-internal-transfers.spec.ts` (tâches 2 et 3)

```
Étant donné un compte « Courant » et un compte « Livret », tous deux à moi
  et un débit de 3 000,00 € sur le Livret le 12
  et un crédit de 3 000,00 € sur le Courant le 13
  et une dépense ordinaire de 80,00 € sur le Courant le 14
Quand je lance la détection des virements internes depuis l'admin
Alors les deux lignes de 3 000,00 € portent le badge « Virement interne »
  et chacune montre l'autre comme contrepartie
  et l'autre onglet reçoit la mise à jour par Mercure
  et le train de vie du dashboard ne compte que les 80,00 €
Quand je retire le badge d'une des deux lignes
Alors les deux le perdent, et le train de vie remonte à 3 080,00 €
```

### Salaire de référence — `e2e/web/tests/finance-reference-salary.spec.ts` (tâches 4 à 6)

```
Étant donné une opération récurrente « Salaire » attendue à 4 950,00 € par mois
  et aucun salaire net déclaré sur le matelas
Quand j'ouvre le dashboard finance
Alors le salaire de référence affiche 4 950,00 €, « mesuré sur 12 mois »
  et la cible du matelas vaut 3 × 4 950,00 €
  et le matelas n'est plus « non configuré »
Quand je demande à Maggie « sur quel salaire tu juges mon mois ? »
Alors `get_reference_salary` a tourné et a réussi
  et le repère est 4 950,00 €, source « attendu » (MAG-273)
```

Scénario `agent/fixtures/fake-llm/95-reference-salary.yaml`, `match: user_contains: "quel salaire tu juges mon mois"`. L'écran (MAG-275) ajoute les étapes du dashboard.

### Trésorerie et avances — `e2e/web/tests/finance-treasury.spec.ts` (tâches 7 à 11)

```
Étant donné un salaire attendu le 25 et un repère de 4 950,00 €
  et aucun crédit de salaire depuis 30 jours
  et un Courant à -120,00 € et un Livret à 6 000,00 €
  et un virement Livret → Courant de 1 500,00 € pendant le retard
Quand j'ouvre la vue trésorerie
Alors elle annonce « salaire en retard de 30 jours »
  et un découvert prévu avec sa date
  et une avance conseillée sur le Livret
  et une avance en cours de 1 500,00 € à remettre sur le Livret
Quand je demande à Maggie « combien je dois remettre sur mon livret ? »
Alors `get_savings_advances` a tourné et a réussi
  et elle propose un contre-virement de 1 500,00 € vers le Livret
Quand je saisis ce contre-virement de 1 500,00 € du Courant vers le Livret
Alors l'avance est soldée et le solde à remettre tombe à 0,00 €
Quand je saisis en plus un approvisionnement de 200,00 € du Courant vers le Livret
Alors il reste un virement interne neutre, et aucun remboursement n'est compté
```

Scénario `agent/fixtures/fake-llm/91-savings-advances.yaml`, `match: user_contains: "remettre sur mon livret"`.

### Mobile — `e2e/mobile/flows/finance-treasury.yaml` (tâches 9 et 11)

Ouvrir le dashboard finance, voir le bandeau « découvert prévu », ouvrir la vue trésorerie, lire le retard et l'avance conseillée, ouvrir les avances d'épargne et lire le solde par source.

### Alerte de trésorerie — extension du parcours MAG-102 (tâche 12)

```
Étant donné un salaire attendu le 25, en retard de 6 jours, et un découvert prévu dans 6 jours
Quand `app:finance:treasury-alerts` passe, deux fois le même jour
Alors une seule proaction « Alerte trésorerie » est déposée, avec le retard, la date du découvert et l'avance conseillée avec sa source
Quand l'agent l'exécute
Alors `get_treasury_outlook` a tourné et a réussi
  et le message rangé dans le fil propose l'avance, sans dire que le virement est fait
```

Scénario `agent/fixtures/fake-llm/92-treasury-alert.yaml`, `match: user_contains: "Alerte trésorerie"`. La moitié API est couverte par `TreasuryAlertsCommandTest`, la moitié agent par `test_proaction.py` (respx) ; le pas de bout en bout rejoint `e2e/smoke/smoke.sh` (MAG-102) quand MAG-327 est fusionnée.

### Évaluation sur le modèle réel

`task e2e:eval` : le scénario de prompt-lab `scripts/prompt-lab/scenarios/90-salaire-en-retard.yaml` vérifie que Maggie choisit `get_treasury_outlook` plutôt que `manage_transactions`, et qu'elle **propose** sans jamais prétendre exécuter un virement (contrainte de neutralité, doc §7).

## Definition of Done

Par tâche, et pas une seule fois pour le projet :

- [ ] Tests unitaires et d'intégration ci-dessus, verts
- [ ] Parcours e2e écrit et exécutable (Playwright / Maestro), avec son scénario fake-LLM quand Maggie est dans la boucle
- [ ] Exemptions écrites dans ce plan, avec leur raison, pour les lectures pures
- [ ] Contrat régénéré et commité quand la forme de l'API ou la liste des outils change
- [ ] CI verte sur un PR qui lie le ticket
- [ ] Spec fonctionnelle du module et guide utilisateur à jour dans Linear (ADR-006) — pour les briques 1 à 4, un ajout à `finance-functional-spec` (M1, M2, M4, M5, M7) et une section « Quand le salaire est en retard » au guide

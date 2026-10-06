# Références — Salaire irrégulier et trésorerie

Ce que le code du module répond déjà, et qu'il ne faut donc pas réinventer.

## Marquer un jugement sur une transaction

- **Où :** `api/modules/finance/src/Entity/Transaction.php`, champs `$retrospect`, `$categorySource`, `$categorizedAt`, méthode `assignCategory()`.
- **Pourquoi c'est la référence :** c'est exactement la forme que prennent `transferKind`, `transferSource` et `counterpart`. Un jugement vit sur la ligne, avec sa provenance et son horodatage, et une méthode de domaine pose les champs ensemble plutôt que trois setters appelés dans le bon ordre.
- **À reprendre :** `markAsInternalTransfer()` sur le modèle de `assignCategory()` ; `TransferSource::Manual` traité comme `CategorySource::Manual` l'est par `findUncategorizedForUser()` — la détection automatique ne repasse jamais dessus.

## Un drapeau booléen sur la catégorie, validé contre l'obligation

- **Où :** `Category::$passiveIncome` et `validateARenteIsIncome()`, introduits par [MAG-46](https://linear.app/meven/issue/MAG-46) (PR #156) — branche `cyrus/mag-46-m9-compteur-dindependance-de-couverture`.
- **Pourquoi :** `Category::$salary` est le même geste, à un mot près. Le commentaire de MAG-46 documente le piège de nommage (`isFoo()` → `foo`) et la validation « seule une recette peut être une rente » devient « seule une recette peut être un salaire ».
- **À reprendre :** nom de colonne `is_salary`, propriété `$salary`, accesseur `isSalary()`, constante de message, `#[Assert\Callback]`, `IndexedField(type: 'boolean')`.

## Mesurer au lieu de déclarer, en un seul endroit

- **Où :** `UseCase/MeasureMonthlyLifestyle.php` (MAG-46) et `sumPassiveIncomeByCategoryBetween()` dans `TransactionRepository`.
- **Pourquoi :** `GetReferenceSalary` est le symétrique côté recettes, et la raison d'être de `MeasureMonthlyLifestyle` — « deux copies de cette règle voudraient dire deux trains de vie, et l'utilisateur devrait deviner quel écran croire » — est mot pour mot la raison d'être de `GetReferenceSalary`.
- **À reprendre :** `sampleWindow()` statique et son commentaire sur minuit ; le mois en cours exclu de la fenêtre ; l'exclusion de `isExceptional` sur les crédits ; `intdiv` et non une division flottante.

## Dériver un état au lieu de le stocker

- **Où :** `UseCase/GetCushionStatus.php` (le montant courant est la somme des comptes marqués, jamais une colonne), `UseCase/GetMonthlyReview.php` (la revue se lit des verdicts), `UseCase/GetAnnualPlan.php` (le plan n'est pas une entité).
- **Pourquoi :** `GetSavingsAdvances` suit la même règle — le solde d'une avance est la somme des remboursements qui la pointent, pas un `repaidCents` stocké à maintenir.
- **À reprendre :** la création à la lecture de `SafetyCushion` (`GetCushionStatus::createFor()`) pour `TreasurySettings`, qui évite un onboarding.

## Un rattrapage sur l'historique, avec un aperçu avant d'écrire

- **Où :** `Controller/ApplyCategorizationRulesController.php` + `UseCase/ApplyCategorizationRules.php`.
- **Pourquoi :** la détection des virements internes doit pouvoir repasser sur tout l'historique du propriétaire, et il doit pouvoir regarder avant d'accepter.
- **À reprendre :** la forme de l'endpoint, le `dryRun`, le compte rendu chiffré du retour, et le branchement à la création (`CreateTransactionHandler` appelle déjà `CategorizeTransaction`).

## Un signal quotidien et ses raisons

- **Où :** `UseCase/GetDailyScore.php`, en particulier `decide()` et son commentaire.
- **Pourquoi :** la décision 11 du shape en découle directement — le rouge et l'orange ne parlent que du budget, le matelas et la comparaison J-365 ne peuvent que retenir le vert. `spending_above_reference_salary` entre dans `reasons` et dans ce seul rôle.
- **À reprendre :** la forme des raisons (`code` + `amountCents` + contexte), et le fait qu'un signal sans sa cause se lit comme un verdict.

## Une lecture composée, pas recalculée

- **Où :** `UseCase/GetFinanceDashboard.php`.
- **Pourquoi :** `GetTreasuryOutlook` compose `GetSalaryDelay`, `GetSavingsAdvances` et les soldes de comptes ; il ne refait aucun calcul que ces trois-là savent faire.
- **À reprendre :** une seule réponse, un seul jeu de règles ; les séries sans trous (un jour de projection sans mouvement est présent à son solde).

## Un plan lu puis appliqué, jamais à moitié

- **Où :** `agent-os/specs/2026-10-05-finance-annual-planning/` — shape décisions 1, 3 et 10, plan tâches 1 et 2.
- **Pourquoi :** le découpage de ce projet reprend le sien (lecture, puis écriture, puis admin, puis mobile, pour tenir sous le garde-fou) et sa décision 3 — pas de moyenne glissante, un calcul que l'utilisateur peut refaire à la main — fonde la décision 17 du shape.

## Une commande planifiée qui réveille Maggie

- **Où :** `api/modules/notification/src/Command/CheckRemindersCommand.php` ; côté agent `agent/app/queue/proaction_publisher.py`, `proaction_consumer.py`, `agent/tests/test_proaction.py`.
- **Pourquoi :** la tâche 12 est le même mécanisme. Le déclencheur est une absence — pas de salaire — donc rien ne peut partir d'un événement de transaction.
- **À reprendre :** le parcours « commande console → file → `execute_proaction` → message rangé dans le fil », et le `dry_run` existant pour vérifier un prompt sans l'envoyer.

## Les journées e2e du module

- **Où :** `e2e/web/tests/finance-*.spec.ts`, `e2e/web/pages/`, `e2e/web/helpers/agui.js`, `agent/fixtures/fake-llm/48-annual-plan.yaml`.
- **Pourquoi :** `finance-annual-plan.spec.ts` est le modèle d'une journée où Maggie est dans la boucle : `retries: 0` parce que la conversation est à état, l'arithmétique assertée contre l'API et non contre la formulation de Maggie, et une année littérale lointaine pour qu'aucune autre journée ne croise ses données.
- **À reprendre :** le choix de valeurs qu'aucune autre journée n'utilise (ici : des montants et un jour de salaire propres à ces journées), et l'assertion sur le chiffre de l'API plutôt que sur la phrase.

## Documents produit

- `agent-os/product/finance-functional-spec.md` — M1 (matelas de trésorerie, seuil d'alerte), M2 (statuts, drapeaux de catégorie), M4 (§5.4, règles du score), M5 (revue mensuelle), M7 (§5.1, cible en mois de salaire net), M9 (train de vie calculé dynamiquement), §7 (neutralité, auditabilité, réversibilité).
- `agent-os/product/finance-roadmap.md` — où ce projet s'inscrit (couche Optimisation, après `finance-independence-counter`) ; la tâche 1 l'y ajoute.
- Projet Linear [« Salaire irrégulier et trésorerie »](https://linear.app/meven/project/salaire-irregulier-et-tresorerie-bad46046e25d) — besoins et solution validés par le propriétaire le 6 oct. 2026 : la source de toutes les règles métier de ce cadrage.

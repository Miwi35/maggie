# Références pour le compteur d'indépendance

## Le train de vie mesuré, déjà écrit

- **Emplacement** : `api/modules/finance/src/UseCase/GetDebtTimeline.php`
  (`estimateMonthlyLifestyle`, `LIFESTYLE_SAMPLE_MONTHS`) et
  `api/modules/finance/src/Repository/TransactionRepository.php`
  (`sumConsumedBetween`).
- **Pertinence** : c'est le nombre que le ticket demande de réutiliser. La
  méthode est privée : elle devient `MeasureMonthlyLifestyle`, injectée dans
  `GetDebtTimeline` comme dans le nouveau use case.
- **À reprendre** : la fenêtre (les trois mois complets précédents), l'exclusion
  des mensualités de prêt (`ObligationFlag::Debt`) et des opérations
  exceptionnelles, les statuts consommés (`Spent` + `Committed`).

## Un indicateur dérivé, de bout en bout : le matelas de sécurité

- **Emplacement** : `GetCushionStatus`, `CushionStatusController`,
  `ManageSafetyCushionTool`, `admin/src/modules/finance/CushionPage.tsx`,
  `mobile/.../finance/CushionScreen.kt`.
- **Pertinence** : même forme de problème — un état calculé, jamais stocké,
  servi en une lecture, avec un pourcentage de couverture et un écart.
- **À reprendre** : la structure du tableau de retour (`…Cents`, `coveragePercent`,
  drapeaux `is…`), le contrôleur qui renvoie 401 quand il n'y a pas d'utilisateur,
  la paire de tests `tests/Controller` + `tests/Mcp`.
- **À ne pas reprendre** : le plafond à 100 % (décision D5).

## Le dashboard, et comment il compose

- **Emplacement** : `api/modules/finance/src/UseCase/GetFinanceDashboard.php`,
  `admin/src/modules/finance/FinanceDashboardPage.tsx`,
  `mobile/.../finance/FinanceDashboardScreen.kt`,
  `mobile/.../data/model/DailyScore.kt` (DTO du dashboard).
- **Pertinence** : le compteur s'y ajoute comme `savingCapacity` — une clé
  composée d'un use case existant, pas un recalcul.
- **À reprendre** : `CapacityCard` côté admin et mobile comme gabarit de carte
  (titre, chiffre, ligne d'explication, état « pas encore renseigné »).

## La timeline de dettes, pour l'outil MCP de lecture

- **Emplacement** : `GetDailyScoreTool` (outil de lecture pur),
  `ManageLoansTool` (action `timeline`).
- **Pertinence** : `get_independence_counter` est un outil de lecture sans
  action, donc `GetDailyScoreTool` est le gabarit exact : description qui
  explique les codes à l'agent, `requireUser()`, `json_encode`.

## La catégorie et son drapeau d'obligation

- **Emplacement** : `api/modules/finance/src/Entity/Category.php`,
  `Enum/ObligationFlag.php`, `Message/{Create,Update}CategoryCommand.php`,
  `MessageHandler/*CategoryHandler.php`, `Mcp/Tool/ManageCategoriesTool.php`,
  `admin/src/modules/finance/CategoryForm.tsx`.
- **Pertinence** : `isPassiveIncome` suit le chemin exact de `obligation` —
  entité, document ES, payload Mercure, commandes, outil MCP, formulaire admin.
- **À reprendre** : `ObligationFlag::Income` existe déjà et porte la validation
  de la décision D2.

## Les parcours e2e finance

- **Emplacement** : `e2e/web/tests/finance-budget.spec.ts`,
  `e2e/web/pages/FinanceBudgetPage.js`, `api/fixtures/e2e/50-finance.yaml`.
- **Pertinence** : le parcours MAG-102 et ses fixtures ancrées
  (`<e2eDate(...)>`), dont la fenêtre de mesure du compteur dépend.
- **À reprendre** : l'objet de page, les helpers `euros()`, et la discipline
  d'assertions qui tiennent avant comme après les autres fichiers du même shard.

# Revue augmentée (trimestre, semestre, année) — Plan

Ticket : MAG-462 · Spec « Revue mensuelle », partie 8 · bloqué par MAG-456 (livré).

## Task 1: Save Spec Documentation

`plan.md` (ce fichier) et `shape.md` (décisions). Standards : voir `shape.md`.

## Task 2: Période de revue, étapes, titres (API)

- `ReviewPeriod` : depuis le mois relu → `level` (`month` · `quarter` · `semester` · `year`), période qui se clôt, période suivante, clés `2026-Q4` · `2026-S2` · `2026`. Mars/septembre = trimestre, juin = semestre (+ trimestre), décembre = année (+ semestre + trimestre) ; le niveau retenu est le plus grand.
- `ReviewStep` : `period_balance` et `period_plan` entre `projects` et `done`.
- `GET|PUT /api/finance/review/progress` rend en plus `level`, `period`, `nextPeriod` (null au niveau `month`) : les clients ne recalculent rien.
- `ActivityTitle` (+ admin `reviewTitle`, mobile `reviewTitle`) : « Revue de fin de trimestre / de semestre / d'année » sur les occurrences concernées.

Tests : `ReviewPeriodTest` (12 mois), `ReviewProgressControllerTest` (level/period, étape inconnue 400), `ActivityTitleTest`, contrats (`openapi.json`, réponse enregistrée).

## Task 3: Plan de période et bilan de période (API)

- `GET /api/finance/annual-plan?period=` (`2027` · `2027-Q1` · `2027-S1`, `year` reste accepté) : `GetAnnualPlan` lit la période source (la précédente, de même durée) et la période cible ; `period`, `sourcePeriod`, `suggestedMonthlyCents`.
- `GET /api/finance/review/period-balance?month=` : bilan de la période qui se clôt à ce mois — mois par mois (dépensé, budgété, épargné) pour les courbes, enveloppes tenues / dépassées, épargne face à la capacité, satisfaction, dépenses prévues réalisées / décalées. 400 si le mois n'est pas de fin de période.

Tests : `GetAnnualPlanPeriodTest`, `AnnualPlanControllerTest` (period, 401, 400), `PeriodBalanceControllerTest` (401, 400, niveau mois, trimestre, année).

## Task 4: Appliquer le plan en un geste (API)

`POST /api/finance/annual-plan` `{period, items[]}` : `annual` · `monthly` (+ saisons) · `one_off` · `event`. Tout est validé avant la première écriture ; l'écriture est **un seul message, un seul flush**. Un plan rejeté sur sa dernière ligne ne laisse rien en base (réponse 400 avec `index`). Rejouer le même plan met à jour, ne duplique pas.

Tests : 401, 400 (JSON, période, item, doublon, mois hors période), happy path avec état en base, **rejet sur la dernière ligne = rien en base**, rejouer = pas de doublon, Mercure `Envelope` et `Transaction`, index ES.

## Task 5: Outils MCP

- `plan_annual_budget` accepte `period`.
- `manage_period_plan` : une action d'écriture par élément (`set_annual_envelope`, `set_monthly_envelope`, `set_one_off_envelope`, `set_event`), paramètres scalaires uniquement, même validation que le POST.

Tests : sans utilisateur, action inconnue, paramètre manquant, chaque action (état en base), période invalide, scénario du faux modèle.

## Task 6: Admin web

Deux étapes dans `ReviewActivityPage` (visibles aux mois de fin de période) : **Bilan de la période** (courbes et anneaux de MAG-417/457, réutilisés) et **Planification** (lignes éditables, « Appliquer le plan »). Tests : rendu, interaction, erreur.

## Task 7: Mobile Android

Mêmes deux étapes dans `ReviewActivityScreen` / `ReviewActivityViewModel`, au doigt (téléphone + tablette). Tests : une par transition d'état du ViewModel, `DtoContractTest` sur la réponse enregistrée, rendu de l'écran.

## Tests

| Unité | Tests |
|---|---|
| `ReviewPeriod` | un cas par mois, bornes d'année |
| `GetAnnualPlan` (période) | trimestre / semestre / année, source et cible, récurrences, enveloppes mensuelles |
| `GetPeriodBalance` | séries mensuelles, enveloppes, épargne, plans |
| `ApplyPeriodPlan` + contrôleur | 401, 400, happy path (BD), atomicité, idempotence, Mercure, ES |
| `ManagePeriodPlanTool` | voir Task 5 |
| Admin / mobile | rendu, interaction, erreur ; transitions du ViewModel |

## E2E journey

**Étend :** MAG-102 — Parcours e2e : finance.

- **Given** une revue de fin de trimestre (le 25 mars) avec des dépenses sur le trimestre
- **When** je parcours les étapes jusqu'à « Planification », je règle une enveloppe et j'appuie sur « Appliquer le plan »
- **Then** les enveloppes mensuelles d'avril, mai et juin existent (page Budget), et rouvrir la revue reprend à « Planification »
- **And** un plan dont une ligne est refusée n'écrit rien (message d'erreur, aucune enveloppe créée)

Web : `e2e/web/tests/finance-review-period.spec.ts` (le navigateur prend le 25 du dernier mois de trimestre via `clock.setFixedTime`).

Mobile : pas de flow Maestro. L'horloge de l'appareil suit `E2E_NOW` (13 nov. 2030 en CI, jamais une fin de période) et un flow ne peut ni dériver une date ni la passer à l'app ; les deux étapes sont couvertes par `PeriodReviewViewModelTest`, `ReviewActivityViewModelTest`, `ReviewActivityScreenTest` et `DtoContractTest` sur les réponses enregistrées.

## Definition of Done

- [ ] Tests ci-dessus verts
- [x] Parcours e2e écrit (web ; mobile : voir ci-dessus)
- [ ] CI verte sur une PR liée au ticket
- [ ] Spec fonctionnelle Finance + guide utilisateur mis à jour **après** le merge (Docs follow main)

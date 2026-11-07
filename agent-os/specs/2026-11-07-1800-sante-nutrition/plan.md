# Santé · Nutrition — journal alimentaire et bilan calorique du jour, prévu compris (MAG-748)

Partie 11 de la « Spec : module Santé », sur MAG-746 (dépense consolidée) et MAG-707 (profil).
Maquettes (page Santé) : `project/sport/Alimentation-Tel.dc.html`, `Alimentation-Tablette.dc.html`, `Admin-Alimentation.dc.html` — non accessibles à la session : l'écran suit le texte du ticket.

## Contrats fixés pour les parties suivantes

| Quoi | Valeur |
|---|---|
| Entité | `Maggie\Health\Entity\FoodIntake` : `date` (jour local, lecture seule), `eatenAt`, `mealId?`, `ciqualCode?`, `label`, `quantityGrams?`, `kcal`, `proteinG`, `carbsG`, `fatG`, `source` (`meal` \| `manual` \| `conversation`, lecture seule). Non indexée, comme `Workout` |
| Copie | les valeurs sont copiées à la prise ; `mealId` est un identifiant, pas une relation : supprimer le repas garde la prise |
| Ressource | `/api/health/food-intakes` (`GetCollection` tri `eatenAt`, filtre `date[after\|before]`, `Get`, `Post`, `Patch`, `Delete`) |
| « Mangé » | `POST /api/health/food-intakes {"mealId"}` : une portion de chaque recette du repas, sur le jour du repas, `eatenAt` = maintenant ; une seule prise par repas (`uniq_food_intake_user_meal`), la seconde → 400 ; un repas sans aucune valeur → 400 |
| Saisie libre | `eatenAt` requis (ISO avec offset), `date` = son jour à Paris ; `label` + `kcal` (macros 0 par défaut), ou `ciqualCode` + `quantityGrams` (valeurs et libellé de Ciqual sauf ceux donnés) |
| Bilan | `GET /api/health/daily-balance?date=` → `{date, eatenKcal, plannedKcal, expendedKcal, balanceKcal, proteinG, carbsG, fatG, plannedMeals}` ; `?from=&to=` (≤ 31 j) → `{days: [...]}` sans `plannedMeals` |
| Calcul | `plannedKcal` = repas du jour pas encore mangés (repas sans valeur : 0) ; `balanceKcal` = `eatenKcal` + `plannedKcal` − `expendedKcal`, `null` avec `expendedKcal` ; macros = mangé + prévu |
| Outils MCP | `manage_food_intakes` (`list` / `create` / `update` / `delete` ; `list` rend `intakes` + `plannedMeals` ; refus `{error, field?, current?}`), `get_daily_balance(date)` |
| Événement | `Maggie\Health\Event\FoodIntakeRecorded {foodIntakeId, userId, date}` sur `event.bus`, à chaque insertion (`FoodIntakeRecordedListener`) — lu par MAG-750 |
| Ports | `Maggie\Health\Port\MealNutritionInterface`, `FoodCatalogInterface` (couche deptrac `HealthPort`), implémentés par le module Cuisine (`Maggie\Cookbook\Health\*`) |
| Topic Mercure | `/users/{userId}/api/health/food-intakes/{id}` |
| Zone d'équilibre | ± 150 kcal, couleurs douces, ni alarme ni verdict (admin et Android) |

## Décisions

1. **Santé → Cuisine par des ports** : Santé ne dépend que de Core ; elle déclare les ports qu'elle lit, la Cuisine les implémente (couche `HealthPort`, seule partie de Santé visible de Cuisine). Un module pont aurait demandé un bundle de plus pour deux classes.
2. **Portion** : une portion de chaque recette du repas, comptée comme la fiche recette (`RecipeNutrition::perServing`).
3. **Macros du bilan** : mangé + prévu, comme les kcal du bilan.
4. **`plannedMeals` dans la réponse du jour** : les lignes « Mangé » du journal sans que les clients recalculent une recette.
5. **Refus 400** pour un second « Mangé » (le ticket : « 400 ou refus clair »).

## Parcours e2e

- **Web** `e2e/web/tests/health-nutrition.spec.ts` — Given un déjeuner prévu aujourd'hui avec « Bol quinoa poulet » (seed, 515 kcal), When j'ouvre Santé › Nutrition et j'appuie sur « Mangé », Then le bilan passe de « Prévu 515 » à « Mangé 515 ».
- **Web** conversation — When je dis « J'ai mangé une salade César à midi » (fake LLM `97-health-food-intake.yaml`), Then l'API rend une prise « Salade César » de source `conversation`.
- **Mobile** — Given un déjeuner de 515 kcal créé par l'API, When j'ouvre Santé › Nutrition et j'appuie sur « Mangé », Then « Mangé 515 ».

Hors périmètre : jeûne (partie 12), compensation (13), dépense prévue (6, 15), photo du repas, objectifs de macros, carte Santé de l'Accueil (MAG-566), recherche Ciqual sur Android.

# Santé · Configuration — profil, métabolisme de base, besoin du jour, réglages d'activité (MAG-707)

Partie 10 de la « Spec : module Santé », sur MAG-705 (navigation) et MAG-706 (pesées).
Maquettes (planche « Maggie · maquettes », page Santé) : `project/sport/Nutrition-Tel.dc.html`, `Admin-Nutrition.dc.html` (titrées « Santé › Configuration ») ; anneau : `project/cuisine/Recette-Tel.dc.html`.

## Contrats fixés pour les parties suivantes

| Quoi | Valeur |
|---|---|
| Entité | `Maggie\Health\Entity\HealthProfile`, une ligne par utilisateur (`uniq_health_profile_user`), créée à la première écriture, non indexée |
| HTTP | `GET` et `PATCH /api/health/profile` (`HealthProfileController`, jamais un `Patch` API Platform sans identifiant). Le PATCH n'applique que les champs envoyés ; 400 `{"error"}` sinon, rien d'écrit |
| Topic Mercure | `health-profile` → `/users/{userId}/api/health-profile/{id}` ; payload = profil complet, republié quand une pesée change |
| Outils MCP | `get_health_profile`, `update_health_profile` (arguments = champs du PATCH, `{"error","field"}` sur refus) |
| Calcul | `HealthProfileCalculator` : `bmrKcal` = round(10 × poids + 6,25 × taille − 5 × âge + 5 / − 161) ; `needKcal` = round(bmr × facteur / 10) × 10 en `computed`, `enteredNeedKcal` en `entered` ; `null` sans sexe, âge, taille ou poids |
| Poids | la dernière pesée non écartée (`BodyMeasurementRepository::latestWeighing()`) ; un `weightKg` envoyé au profil crée une pesée manuelle maintenant |
| Réglages d'activité | `stepGoal` (8 000, pas de 500), `suggestionWindowStart`/`End` (« 08:00 »/« 20:00 »), `sessionMinutes` (15, 20, 30 ; 20), `stepSource` (`health_connect`, `none` ; `none`) — lus par les parties 4 et 15 |

### Forme JSON (`api/contract/responses/health_profile.me.json`)

```json
{ "id": "01J…", "sex": "male", "birthYear": 1988, "age": 38, "heightCm": 178, "weightKg": 72.4,
  "activityLevel": "moderate", "mode": "computed", "enteredNeedKcal": 2450, "bmrKcal": 1652, "needKcal": 2560,
  "factor": 1.55, "stepGoal": 9500, "suggestionWindowStart": "07:30", "suggestionWindowEnd": "21:00",
  "sessionMinutes": 30, "stepSource": "health_connect" }
```

`id` vaut `null` tant que le profil n'a jamais été écrit. `factor` : celui de l'activité en `computed`, besoin ÷ métabolisme (2 décimales) en `entered`.
Bornes : taille 100–250 cm, besoin saisi 1 000–5 000 par 50, pas 500–50 000 par 500, année de naissance des 120 dernières années, poids comme une pesée (0 < x ≤ 500).

## Décisions

1. **Poids** : modifier le poids dans Configuration enregistre une pesée manuelle (une seule série de poids, spec partie 16) — décision du ticket.
2. **Republication** : un listener Doctrine (`RepublishHealthProfileOnBodyMeasurementListener`, modèle `RepublishCushionOnPeerClaimListener`) plutôt qu'un événement de pesée + subscriber : aucune règle métier, seulement la projection à republier, quelle que soit la porte.
3. **Payload Mercure complet** : les chiffres dépendent de tous les champs et des pesées, le client remplace son état.
4. **Défauts** : activité `sedentary`, mode `computed`, source des pas `none` (l'import Health Connect vient avec MAG-708).
5. **Bloc « Activité »** titré comme les maquettes (le ticket le nomme « Activité physique »).

## Parcours e2e

- **Web** `e2e/web/tests/health-configuration.spec.ts` — Given le seed sans profil, When je règle Homme, 38 ans, 178 cm, 72 kg, Sédentaire, Then « 1 980 kcal » s'affiche, et la fiche d'une recette du seed montre son anneau.
- **Web** `e2e/web/tests/health-configuration-chat.spec.ts` — When je dis « Mon objectif de pas, c'est 10 000 » à Maggie (fake LLM `97-health-step-goal.yaml`), Then l'API rend `stepGoal` 10 000.
- **Mobile** `e2e/mobile/flows/32-health-configuration.yaml` — Given le profil Homme, 38 ans, 178 cm, 72 kg, Sédentaire créé par l'API, When j'ouvre Santé › Configuration, Then « 1 980 kcal » et « métabolisme de base 1 648 kcal ».

Hors périmètre : objectifs perte / maintien / prise, objectifs de macros, apports de référence des micronutriments (tableau de la maquette admin non dessiné).

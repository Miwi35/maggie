# Santé · Socle — séances d'entraînement (MAG-705)

Partie 1 de la « Spec : module Santé ». Maquettes (planche « Maggie · maquettes », page Santé) :
`project/sport/Admin-Sport.dc.html`, `Sport-Tel.dc.html`, `Sport-Tablette.dc.html`.

## Contrats fixés pour les parties suivantes

| Quoi | Valeur |
|---|---|
| Bundle | `api/modules/health`, namespace `Maggie\Health`, testsuite `Health`, couche deptrac `Health: [Core]` |
| Entité | `Maggie\Health\Entity\Workout`, servie par Doctrine (non indexée : rien n'y est cherché, comme `Trip`) |
| Ressource | `/api/workouts` (`GetCollection`, `Get`, `Post`, `Patch`, `Delete`), tri `startedAt` DESC, filtres `type`, `source` (exact), `startedAt[after|before]` |
| Topic Mercure | collection `workouts` → `/users/{userId}/api/workouts/{id}` |
| Outil MCP | `manage_workouts` (`list` / `create` / `update` / `delete`) |
| Enums | `WorkoutType` (`run`, `walk`, `bike`, `swim`, `strength`, `climbing`, `other`), `WorkoutFeeling` (`very_good`, `good`, `hard`, `very_hard`), `WorkoutSource` (`manual`, `watch`, `activity`, `estimated`, `planned`) |
| Dédoublonnage | `externalId` (≤ 255), unique par utilisateur (`uniq_workout_user_external`), `null` permis plusieurs fois |
| Navigation | admin : module « Santé », partie « Sport » (`/workouts`) ; Android : tiroir « Santé », route `health/sport`, lien `maggie://health/sport`, `HealthSection.SPORT` |

### Forme JSON (`/api/workouts`, payload Mercure identique hors `@id`/`@type`)

```json
{ "id": "01J…", "type": "run", "title": "Course", "startedAt": "2026-10-20T07:10:00+02:00",
  "durationMinutes": 32, "distanceMeters": 5400, "kcal": 386, "avgHeartRate": 152,
  "feeling": "good", "notes": null, "source": "manual", "externalId": null }
```

Unités : minutes entières (1…1440), mètres entiers (0…1 000 000), kcal entières (0…20 000), bpm entiers (20…250).

### Libellés (identiques admin et Android)

- Type : Course à pied · Marche · Vélo · Natation · Musculation · Escalade · Autre
- Ressenti : Très bien · Bien · Dur · Très dur
- Source : Saisie · Montre · Activité Sport · Estimée · Prévue
- Durée : `45 min`, `1 h 05` ; distance : `5,4 km`, `22,0 km` (une décimale, virgule) ; FC : `152 bpm` dans la fiche.

### Calculs côté client

- **Minutes par semaine** : somme de `durationMinutes` des séances non `planned`, par semaine ISO (lundi → dimanche) en heure locale, 13 semaines dont la courante (barre plus claire). Indépendant des filtres de la liste.
- **Cette semaine** : 7 pastilles L…D de la semaine ISO courante ; pleine = au moins une séance non `planned` ce jour, cerclée = au moins une séance `planned`, aujourd'hui entouré. Compteur `n faite(s) · n prévue(s)` en nombre de séances.
- « Séance de ce soir » / « Lancer » : non affichés tant qu'aucune séance `planned` à venir n'existe — rien dans ce ticket n'en crée par l'interface (parties 6).

## Décisions

1. **Non indexée** : liste filtrée par date et par utilisateur, aucune recherche plein texte — Doctrine + `CurrentUserExtension`, comme `Trip` ; évite l'index périmé.
2. **400 vs 422** : un enum inconnu est un 400 (désérialisation API Platform), une durée ≤ 0 un 422 (validation) — convention de l'API, `api-operation.md`.
3. **Admin** : la partie Sport est la ressource `workouts` (`/workouts`), le fil lit « Santé › Sport ».
4. **Téléphone** : la liste « Séances » montre les séances non prévues (maquette `past`), la tablette toutes (`rows`).

## Parcours e2e

- **Web** `e2e/web/tests/health-sport.spec.ts` — Given le seed (compte du parcours), When j'ouvre Santé › Sport et j'ajoute « Vélo, 1 h 05, 22 km », Then la ligne apparaît dans le tableau et la carte de la semaine augmente de 65 min.
- **Mobile** `e2e/mobile/flows/30-health-sport.yaml` — Given un compte neuf, When j'ouvre Santé › Sport et j'ajoute une séance, Then elle apparaît dans « Séances » avec la pastille « Saisie ».

Hors périmètre : exercices et séries, « Séance de ce soir » / « Lancer », objectifs, Health Connect, dépense consolidée.

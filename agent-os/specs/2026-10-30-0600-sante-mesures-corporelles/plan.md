# Santé · Mesures corporelles — pesées et Santé › Suivi, carte Poids (MAG-706)

Partie 2 de la « Spec : module Santé », sur le socle de MAG-705 (`agent-os/specs/2026-10-29-0100-sante-socle-seances/plan.md`).
Maquettes (planche « Maggie · maquettes », page Santé) : `project/sport/Suivi-Tel.dc.html`, `Suivi-Tablette.dc.html`, `Admin-Suivi.dc.html` — seules la carte Poids, le sélecteur de période et (admin) le tableau « Pesées » sont livrés ici.

## Contrats fixés pour les parties suivantes

| Quoi | Valeur |
|---|---|
| Entité | `Maggie\Health\Entity\BodyMeasurement`, servie par Doctrine (non indexée, comme `Workout`) |
| Ressource | `/api/body-measurements` (`GetCollection`, `Get`, `Post`, `Patch`, `Delete`), tri `measuredAt` DESC, filtre `measuredAt[after\|before]`, jusqu'à 1 000 par page (`itemsPerPage`) |
| Topic Mercure | collection `body-measurements` → `/users/{userId}/api/body-measurements/{id}` |
| Outil MCP | `manage_body_measurements` (`list` / `create` / `update` / `delete` / `exclude`) ; `create` sans `measuredAt` = maintenant, source `manual` |
| Enum | `MeasurementSource` (`manual`, `health_connect`, `withings`) |
| Dédoublonnage | `externalId` (≤ 255), unique par utilisateur (`uniq_body_measurement_user_external`), `null` permis plusieurs fois |
| Écartée | `excluded` (bool, `false` par défaut) ; « Écarter » = `UpdateBodyMeasurementCommand` avec `excluded: true` |
| Poids du jour | `BodyMeasurementRepository::firstOfDay(User, DateTimeImmutable $localDay)` : la première pesée (avec `weightKg`) non écartée du jour local de `$localDay` (son fuseau = celui de l'utilisateur, fourni par l'appelant), sinon `null` |
| Navigation | admin : partie « Suivi » (`/health/suivi`) avant « Sport » ; Android : `HealthSection.SUIVI` avant `SPORT`, route `health/suivi`, lien `maggie://health/suivi` |

### Forme JSON (`/api/body-measurements`, payload Mercure identique hors `@id`/`@type`)

```json
{ "id": "01J…", "measuredAt": "2026-10-21T07:12:00+02:00", "weightKg": 72.4, "bodyFatPercent": 21.3,
  "waistCm": null, "source": "manual", "externalId": null, "excluded": false }
```

Nombres JSON (pas de chaînes). Colonnes `decimal(5,2)`, `decimal(4,1)`, `decimal(5,1)`. Au moins une des trois valeurs.
Bornes : poids `0 < x ≤ 500` kg, masse grasse `0 < x < 100` %, tour de taille `0 < x ≤ 300` cm. Hors bornes ou aucune valeur : 422.

### Carte Poids (identique admin et Android, calcul côté client)

- **Valeur d'un jour** : la première pesée non écartée du jour local (même règle que `firstOfDay`) ; masse grasse de cette même pesée.
- **Tendance** : moyenne glissante sur 7 jours (le jour et les 6 précédents) des valeurs du jour existantes ; aucun jour pesé dans la fenêtre → pas de tendance. Les pesées écartées ne comptent jamais.
- **Période** `7 j · 30 j · 90 j · 1 an` (défaut 30 j) : 7 ou 30 colonnes d'un jour ; 90 j = 13 colonnes d'une semaine (les 91 derniers jours) ; 1 an = une colonne par mois civil sur les 365 derniers jours. Une colonne de plusieurs jours montre la moyenne de ses jours pesés (poids, masse grasse, tendance).
- Points (pesées) seulement en 7 j et 30 j ; tendance en ligne corail `#FF8A80` ; colonne choisie surlignée (défaut : la dernière, aujourd'hui).
- **En-tête** : libellé de la colonne (`Aujourd’hui`, `mer. 21 oct.`, `Sem. du 14 oct.`, `Octobre 2026`), `72,4 kg`, `— 72,1 kg` (tendance ; admin : `— tendance 72,1 kg`), `· 21,3 % MG` (admin : `· masse grasse 21,3 %`) ; `—` quand rien.
- **Axe** : première et dernière colonne (`21 sept.` … `21 oct.` ; 1 an : `nov. 25` … `oct. 26`), au centre l'écart de tendance `−1,2 kg` (admin : `−1,2 kg sur la période`).
- Libellé accessible d'une colonne : `mer. 21 oct. : 72,4 kg, tendance 72,1 kg` / `… : pas de pesée, tendance —`.

### Pesées (admin) et « Écarter »

- Admin : carte « Pesées » à droite de la carte Poids (maquette) : Date · Poids · Masse grasse, plus récente en premier, sur la période ; une pesée écartée reste listée, atténuée et barrée, avec « Réintégrer ». Actions : Ajouter, Modifier, Écarter.
- Android : appui long sur une colonne de jour pesé (7 j, 30 j) → menu « Écarter » (la pesée retenue ce jour-là). Une pesée écartée est dessinée atténuée.

## Décisions

1. **Fuseau de `firstOfDay`** : celui du `DateTimeImmutable` fourni par l'appelant — le bundle Health ne dépend que de Core (deptrac), le fuseau de l'utilisateur vit dans Calendar.
2. **422** pour aucune valeur / poids ≤ 0 (validation), 400 pour un enum ou un nombre illisible — convention de MAG-705 et `api-operation.md`.
3. **Carte « Pesées » à droite de la carte Poids** dans l'admin, comme la maquette dessinée, plutôt que dessous.
4. **Cartes non livrées** (bilan, Pas, Énergie, Moral · fatigue) : absentes, aucun emplacement.

## Parcours e2e

- **Web** `e2e/web/tests/health-suivi.spec.ts` — Given le seed, When j'ouvre Santé › Suivi et j'ajoute une pesée de 71,8 kg aujourd'hui, Then la carte Poids affiche 71,8 kg pour aujourd'hui.
- **Mobile** `e2e/mobile/flows/31-health-suivi.yaml` — Given un compte neuf et ses pesées créées par l'API, When j'ouvre Santé › Suivi et je choisis 30 j, Then la carte Poids affiche les pesées.

Hors périmètre : imports Health Connect (MAG-708) et Withings, les autres cartes de Suivi, le profil.

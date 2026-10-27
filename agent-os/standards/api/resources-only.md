# L'API n'expose que des ressources

L'API expose des **ressources et des actions du domaine**. Elle ne connaît ni les écrans, ni leur ordre, ni leur mise en page, ni la liste de ce qu'un front affiche : ça vit dans le front (`admin/src/modules/home/home.config.ts`, `HomeLayout.kt`).

## Interdit

- Un endpoint d'écran : `/api/home`, `/api/home/finance`, `/api/<écran>`.
- Un ordre, une mise en page, une liste de cartes ou de colonnes renvoyés par l'API.
- Une vue SQL pour agréger ou accélérer.

## Pour agréger ou accélérer

1. Un **filtre de domaine** sur la ressource (`completedAt[after]`, `dueDate[before]`, `exists[completedAt]`) : une collection filtrable plutôt qu'un endpoint par élément.
2. Un **index Elasticsearch** derrière la ressource métier.

Jamais une vue SQL.

## Où se fait le calcul

- Un filtre simple sur des données déjà chargées se fait **dans le front**.
- Un calcul qui applique des règles métier reste **dans l'API**, une seule fois, lu par tous les fronts et par Maggie (ex. `overdueDays`, `urgency`, `orderScore` d'une tâche, `/api/tasks/view/today`).

## Composants de synthèse

Seuls les composants de synthèse (cartes, tuiles) composent plusieurs ressources, côté client. Les listes et les écrans restent sur leur ressource.

Un module absent répond 404 sur sa ressource : la carte correspondante n'est pas affichée.

## Exemples

| | |
|---|---|
| Permis | `/api/finance/dashboard` — une ressource de synthèse du domaine finance, que Maggie lit aussi |
| Interdit | `/api/home/finance` — l'écran Accueil décide ce que l'API renvoie |
| Permis | `GET /api/tasks?completedAt[after]=…&completedAt[before]=…` — « faites aujourd'hui » |
| Interdit | `GET /api/home/tasks` — une carte entière servie par l'API |
| Permis | `GET /api/events?expand=true&endAt[after]=…&startAt[before]=…` — les occurrences d'une période, séries dépliées une seule fois par l'API (RRULE), lues par tous les fronts |
| Permis | `GET /api/grocery/recipe-needs?from=…&to=…&missing=true` — les ingrédients des repas d'une période, calculés à la volée par le pont `meal-grocery`, lus par la carte Courses et l'interruption « Il manque » |

`/api/home` et ses cartes ont été supprimés une fois toutes les cartes de l'Accueil migrées (MAG-589 à 592, MAG-593) : l'Accueil compose ses cartes côté client, à partir des ressources du domaine.

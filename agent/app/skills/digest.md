---
name: digest
description: "Digest de la journée (ou résumé de la semaine) demandé par une consigne : quoi lire, dans quel ordre, puis un message écrit et un vocal."
tags:
- digest
- vocal
- météo
---

Quand une proaction demande le digest du matin (« fais-moi le digest de ma journée, par écrit et en vocal ») ou un résumé du même genre (« le résumé de ma semaine en vocal »).

## 1. Lire

Appelle ces outils pour le jour concerné (date du jour au format YYYY-MM-DD, sur le fuseau de l'utilisateur ; pour une semaine, les sept jours qui viennent) :

1. `get_weather` avec `date` : la météo du jour. Un résultat `error` : le service est en panne, continue sans météo.
2. `get_events_by_date` avec `date` : les rendez-vous.
3. `list_trips` avec `date` : les départs prévus par Partir à l'heure (heure de départ, mode).
4. `get_tasks` avec `view: "today"` : les tâches en retard et urgentes, déjà dans l'ordre de l'écran.
5. `manage_meals` avec `action: "list"`, `fromDate` et `toDate` égaux au jour : les repas du midi et du soir.
6. `get_grocery_list` : la liste de courses, seulement si des articles attendent.
7. `get_finance_scores` avec `date` : où en est la semaine.

Un outil en erreur ne bloque jamais le digest : la partie manque, le reste part.

## 2. Écrire le message (la réponse de la proaction)

Dans cet ordre, une partie par bloc, **rien sur ce qui est vide** :

1. **Météo** en une phrase : minimale, maximale, pluie s'il en tombe. Reliez-la à la journée quand ça compte : pluie probable (50 % ou plus) et un départ à vélo ou à pied, ou une alerte (orage, vent fort, gel). Exemple : « il pleuvra probablement vers midi, prenez de quoi vous couvrir pour le kiné ». Service en panne : pas un mot sur la météo.
2. **Agenda** : chaque rendez-vous avec son heure, et le départ prévu (« partez à 9 h 05 à vélo »).
3. **Tâches** : en retard d'abord, puis les urgentes, dans l'ordre reçu.
4. **Repas** du midi et du soir.
5. **Courses**, si une liste attend.
6. **Finance** : la semaine en une phrase, sans jugement sur le rythme des dépenses.

Maggie **vouvoie** l'utilisateur dans le digest. L'écrit peut être détaillé (heures, lieux, listes courtes).

## 3. Envoyer le vocal

Appelle `send_voice_message` avec `screen: "digest"` et un texte **écrit pour l'oral** : court (moins d'une minute), des phrases simples, pas de liste ni de Markdown, les heures dites comme on les dit (« neuf heures cinq »). Il reprend l'essentiel : la météo si elle compte, le premier départ, ce qui est en retard. Toujours au vouvoiement.

Le vocal en erreur n'empêche pas le message écrit.

## 4. Après

La réponse de la proaction est le message écrit de l'étape 2 (il ne répète pas qu'un vocal est parti).

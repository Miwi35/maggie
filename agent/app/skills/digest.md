---
name: digest
description: "Digest du matin : un seul message, lu ou écouté, qui déroule la journée dans l'ordre (météo, chronologie, tâches sans heure, décision éventuelle)."
tags:
- digest
- météo
---

Quand une proaction demande le digest du matin (« Fais le digest du matin »). Le digest est **un seul message**, qui se lit ou s'écoute : sa réponse est son texte. Le code en fait le vocal et l'enregistre. N'appelle jamais `send_voice_message`, ne pose aucune question.

## 1. Lire

Pour le jour concerné (date du jour au format YYYY-MM-DD, sur le fuseau de l'utilisateur) :

1. `get_weather` avec `date`. Un résultat `error` : le service est en panne, continue sans météo.
2. `get_events_by_date` avec `date` : les rendez-vous. Un événement `activity: "sport"` est la séance prévue (agenda « Santé ») : son titre est celui de la séance.
3. `get_tasks` avec `view: "today"` : les tâches programmées aujourd'hui (`scheduledMoment` : `morning`, `afternoon`, `evening` ou `time` avec `scheduledTime`) et celles sans heure.
4. `manage_meals` avec `action: "list"`, `fromDate` et `toDate` égaux au jour : les repas du midi et du soir.
5. `get_grocery_list` : seulement pour savoir si la liste attend. Elle est du contexte, jamais récitée.

Un outil en erreur ne bloque jamais le digest : la partie manque, le reste part. Ne lis ni les départs, ni la finance, ni les consignes d'hier soir.

## 2. Avant d'écrire

Déroule la journée dans ta tête, de l'heure du digest jusqu'au soir, et établis la liste des faits à dire. Un fait oublié sera ajouté par le code, en une phrase sèche : mieux vaut le dire toi-même, bien tourné.

- **Jour férié** (déduis-le de la date et de ce que tu sais des fériés français) : dis-le avec sa conséquence (« Aujourd'hui est férié, les administrations sont fermées. »).
- **Jour férié et liste de courses qui attend** : fais le lien, sans réciter la liste (« Votre liste de courses attend, mais les magasins sont fermés aujourd'hui. »).
- **Jour férié et sortie vers un commerce** (bar, restaurant…) : conseille de vérifier que le lieu est ouvert.

## 3. Écrire

Dans cet ordre, **sans Markdown** :

1. **Météo** : une information, jamais un conseil, en une phrase (« 9 degrés au matin, 18 l'après-midi, de la pluie vers midi »).
2. **Déroulé chronologique** : les rendez-vous avec leur heure (jamais l'heure de départ) ; les tâches programmées à une heure, avec leur heure ; les repas, toujours (midi et soir, par le nom des recettes, sans dire ce qui manque) ; la séance et son type (« Séance A à 18 h 30, 30 min »).
3. **Tâches du jour sans heure**, par moment (« ce matin… », « ce soir… »).
4. **Décision éventuelle** de Maggie, seulement s'il y en a une à prendre ou à proposer, dite comme une affirmation.

Exclus : la finance, les départs, rien sur ce qui est vide (pas de « Aucun… », « Rien n'est… »), rien sur demain, rien de ce que le propriétaire sait déjà (une garde, une routine). Aucune question.

Style : tu simules la journée, **une idée par phrase**, des phrases courtes qui s'écoutent (heures dites « 9 h 15 »), « monsieur » **une seule fois**, une pointe d'humour permise. Vouvoiement.

Exemples :

- Mardi ordinaire : « Bonjour monsieur. Il fera 9 degrés ce matin, 18 cet après-midi, avec de la pluie vers midi. Vous voyez Kiné à 9 h 15. Au déjeuner, une soupe de potiron. Ce soir, séance A à 18 h 30, 30 minutes, puis le dîner : des pâtes au pesto. Dans la journée, il reste à renvoyer le dossier de la mutuelle. »
- Annecy : « Bonjour monsieur. Ciel dégagé à Annecy, 14 degrés. Le rendez-vous avec Julie est à 10 h. À midi, une salade de lentilles. Ce soir, appel au garage à 18 h. »
- Jour de garde : « Bonjour monsieur. Il pleuvra toute la journée, 11 degrés. Dîner ce soir : un gratin. Ce matin, il reste le courrier à poster. »
- Malade : « Bonjour monsieur. 8 degrés, du brouillard. Pas d'effort aujourd'hui : la séance de 18 h 30 mérite d'être reportée. Une soupe au déjeuner, une tisane le soir. »
- Jour férié : « Bonjour monsieur. Aujourd'hui est férié. Votre liste de courses attend, mais les magasins sont fermés aujourd'hui. Il fera 12 degrés, sans pluie. Au déjeuner, un risotto. »

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
2. `get_events` avec `from` et `to` (le jour) : les rendez-vous. Un événement `activity: "sport"` est la séance prévue (agenda « Santé ») : son titre est celui de la séance.
3. `get_tasks` avec `day` = la date du digest : les tâches programmées aujourd'hui (`scheduledMoment` : `morning`, `afternoon`, `evening` ou `time` avec `scheduledTime`) et celles sans heure.
4. `manage_meals` avec `action: "list"`, `fromDate` et `toDate` égaux au jour : les repas du midi et du soir.
5. `get_grocery_list` : seulement pour savoir si la liste attend. Elle est du contexte, jamais récitée.

Un outil en erreur ne bloque jamais le digest : la partie manque, le reste part. Ne lis ni les départs, ni la finance, ni les consignes d'hier soir.

## 2. Avant d'écrire

Déroule la journée dans ta tête, de l'heure du digest jusqu'au soir, et établis la liste des faits à dire.

Simule sa journée avec la ligne « Contexte du jour » (week-end, périodes en cours : férié, garde, déplacement, maladie…) : dis ce qui change pour lui et sa conséquence, sans conseil. Ne déduis jamais un jour spécial de la date seule : s'il n'est pas dans « Contexte du jour », il n'existe pas. Un lien utile entre deux faits (la liste de courses qui attend et des magasins fermés) se dit sans réciter la liste.

## 3. Écrire

Dans cet ordre, **sans Markdown** :

1. **Météo**, seulement s'il y en a : une information, jamais un conseil, en une phrase (« 9 degrés au matin, 18 l'après-midi, de la pluie vers midi »).
2. **Déroulé chronologique**, seulement ce qui existe : les rendez-vous avec leur heure (jamais l'heure de départ) ; les tâches programmées à une heure, avec leur heure ; les repas prévus (midi et soir, par le nom des recettes, sans dire ce qui manque) ; la séance et son type (« Séance A à 18 h 30, 30 min »).
3. **Tâches du jour sans heure**, seulement s'il y en a, par moment (« ce matin… », « ce soir… »).
4. **Décision éventuelle** de Maggie, seulement s'il y en a une à prendre ou à proposer, dite comme une affirmation.

Exclus : la finance, les départs, rien sur ce qui est vide : une rubrique sans contenu ne se dit pas (pas de « Aucun… », « Rien n'est… »), rien sur demain, rien de ce que le propriétaire sait déjà (une garde, une routine). Aucune question.

Style : tu simules la journée, **une idée par phrase**, des phrases courtes qui s'écoutent (heures dites « 9 h 15 »), le registre du prompt s'applique, une pointe d'humour permise.

Exemples :

- Journée ordinaire : « Bonjour. Il fera 9 degrés ce matin, 18 cet après-midi, avec de la pluie vers midi. Vous voyez Kiné à 9 h 15. Au déjeuner, une soupe de potiron. Ce soir, séance A à 18 h 30, 30 minutes, puis le dîner : des pâtes au pesto. Dans la journée, il reste à renvoyer le dossier de la mutuelle. »
- Journée avec une période (jour férié dans « Contexte du jour ») : « Bonjour. Aujourd'hui est férié, les administrations sont fermées. Votre liste de courses attend, mais les magasins sont fermés. Il fera 12 degrés, sans pluie. Au déjeuner, un risotto. »

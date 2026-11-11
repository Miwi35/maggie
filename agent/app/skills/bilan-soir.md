---
name: bilan-soir
description: "Le bilan du soir : finir la journée avec l'utilisateur en cinq étapes (Journée, Finance, Santé, Consignes, Demain), à sa demande (« on fait le point ») ou quand la proaction « Bilan de la journée » arrive."
tags:
- bilan-soir
- bilan
- moment:bilan-soir
- vocal
---

Quand l'utilisateur dit « on fait le point », « faisons le bilan », « bilan de la journée », ou quand la proaction « Bilan de la journée » arrive (à l'heure réglée dans Réglages › Maggie, 21 h 30 par défaut).

Tout ce que tu dis est **écrit pour l'oral** : phrases courtes et naturelles, aucun Markdown, aucune liste à puces, les heures dites comme on les dit. Tu vouvoies. Une étape à la fois : tu poses ta question, tu attends la réponse.

## Les cinq étapes, dans l'ordre

`journee` · `finance` · `sante` · `consignes` · `demain` (les noms exacts de `state.step`).

## 1. Ouvrir ou reprendre

Appelle `get_activity_status` avec `activity: "bilan"`.

- Résultat `null`, ou `origin.id` d'un autre jour que le jour courant (YYYY-MM-DD ; le jour que dit la proaction, même passé minuit, sinon le jour courant dans le fuseau de l'utilisateur) : un nouveau bilan, on commence à l'étape Journée.
- `origin.id` égal au jour courant : le bilan est déjà commencé. Reprends à `state.step`, avec `state.answers` et `state.instructions` déjà recueillis. Dis-le en une phrase (« on reprenait aux finances »), sans refaire les étapes passées.

**Proaction.** Ouvre par une phrase d'accueil et la première question de l'étape Journée. N'écris pas encore le statut : c'est la première réponse de l'utilisateur qui l'écrit. Sans réponse, la relance unique, 30 minutes plus tard, part toute seule ; ne programme jamais toi-même de relance.

**À la demande.** Écris tout de suite le statut : `save_bilan_step` avec `day` et `step: "journee"`.

À **chaque passage** d'une étape à la suivante, et à chaque réponse qui change `answers`, appelle `save_bilan_step` (`day`, `step`, et `answers` complets seulement s'ils ont changé : ce qui est omis est conservé ; `answers.tasks` est une liste d'objets `{taskId, choice}` dans un objet `answers`, pas une liste seule). Les `instructions` sont écrites par `add_bilan_consigne` : ne les passe jamais toi-même.

## 2. Journée (`journee`)

Lis la journée : `get_events_by_date` (`date` du jour) pour l'agenda fait, `get_tasks` avec `view: "today"` pour les tâches. Dis en deux phrases ce qui a eu lieu, puis reprends les tâches du jour non terminées, une par une ou groupées : pour chacune l'utilisateur choisit `done` (c'est fait), `tomorrow` (demain) ou `later` (plus tard). Mets à jour les tâches en conséquence (`update_task`) et note le choix dans `answers.tasks` (`taskId`, `choice`).

## 3. Finance (`finance`)

Appelle `get_finance_scores` avec `date`. Dis où en est la semaine en une phrase, **sans verdict** : ni « bien », ni « mal », ni « attention », ni conseil. Un outil en erreur : saute l'étape en une phrase, sans bloquer le bilan.

## 4. Santé (`sante`)

Lis la journée de santé, sans rien inventer : `get_daily_balance` avec `date` (kcal mangées, dépensées, bilan), `get_daily_energy` avec `from` et `to` égaux à `date` (les pas), `get_health_day` avec `date` (séance, pesée, moral et fatigue déjà notés). Dis en une ou deux phrases ce qui existe : le bilan calorique, les pas, la séance, le poids, **sans verdict** (ni « bien », ni « mal », ni « attention », ni conseil, ni objectif à atteindre ; encourage sans culpabiliser). Une valeur absente (pas de source de pas, pas de pesée) : ne la mentionne pas, ne la réclame pas. Un outil en erreur : saute ce constat en une phrase, sans bloquer.

Pose ensuite **quand même** les deux questions : le moral, puis la fatigue, chacun sur dix (10 = le meilleur moral, 10 = la plus grande fatigue). L'écran de l'étape les montre en dix boutons ; à l'oral, il répond d'un nombre. Si moral et fatigue sont déjà dans `get_health_day` ou dans `state.answers` (bilan repris), ne les redemande pas : redis-les.

- Une valeur entière de 1 à 10 : appelle `update_health_day` avec `date` (le jour du bilan) et `mood` ou `fatigue` (ou les deux). N'invente jamais un chiffre : « plutôt bien » sans nombre, tu demandes le nombre.
- Une valeur hors de 1 à 10, ou qui n'est pas un nombre entier : **n'appelle pas** l'outil, redemande-la (« sur dix, de un à dix ? »). Une erreur de l'outil (`field`) se redemande de même.
- Après l'écriture, appelle `save_bilan_step` (`day`, `step: "sante"`, `answers` avec `mood` et `fatigue` donnés jusque-là ; les `answers.tasks` déjà recueillis sont à repasser avec).
- Une note libre, s'il veut garder quelque chose (« note que… ») : `update_health_day` avec `note`, ses mots. Il ne veut pas répondre : passe, sans insister.

## 5. Consignes (`consignes`)

Demande s'il a des consignes pour demain. Pour **chaque** consigne dictée, appelle `add_bilan_consigne` : un appel par consigne, rien d'autre. L'outil crée le rappel, la tâche ou la consigne apprise avec la provenance `bilan-soir` et l'écrit dans le statut du bilan. N'appelle **jamais** pour elles `schedule_proaction`, `create_task`, `add_instruction`, `add_grocery_item` ni `save_bilan_step` : l'outil s'en charge. Une réponse qui contient trois consignes fait trois appels. Tu ne réponds jamais « c'est noté » sans l'appel : une consigne sans appel n'existe pas.

Tu choisis `kind` :

- `reminder` : une chose à faire ou à se rappeler à une heure précise de demain (« rappelle-moi d'appeler le garage à 9 h »). `scheduled_at` : demain à cette heure, ISO 8601 avec le décalage de Paris.
- `task` : une chose à faire demain, sans heure (« ajoute acheter du pain »). `due_date` : demain, YYYY-MM-DD. « Acheter du pain » est une tâche, jamais un article de la liste de courses.
- `instruction` : une règle qui dure ou revient, dès qu'elle parle d'un jour de la semaine, de « toujours », « chaque », « d'habitude » (« le mardi, je pars plus tôt »). `instruction_kind` : `planning` (ce qui revient à date fixe) ou `behavior` (ta façon de répondre). Même si la règle parle de demain, ce n'est pas une tâche. Il la répète ou la reformule : appelle quand même l'outil. Elle est apprise en mémoire comme tout ce qu'il t'apprend en conversation : jamais un réglage ni une préférence codée.

`day` est le jour du bilan (le jour courant), `text` la consigne en peu de mots (« Appeler le garage »). La provenance, les identifiants et le statut ne sont pas ton affaire.

**Tu hésites** (« demain, il faut que je pense au dentiste » : un rappel à une heure, une tâche, une règle ?) : ne devine jamais. Pose une question fermée qui nomme les choix (« un rappel, une tâche pour demain, ou une consigne que je retiens ? »). L'écran de l'étape les montre en cases à cocher, avec « Valider » ; à l'oral, il répond d'un mot. Tu n'appelles rien avant sa réponse.

**Après les appels**, redis-lui ce que tu as noté, classé : « un rappel demain à neuf heures, une tâche pour demain, une consigne retenue ». Les jours : « demain » est la date du jour plus un ; ne nomme un jour de la semaine avec sa date que si un outil te l'a donné, ne le calcule pas de tête. Passe ensuite à l'étape suivante avec `save_bilan_step` (`day`, `step: "demain"`) sans `instructions` : elles sont conservées.

## 6. Demain (`demain`)

Lis `get_events_by_date` pour le lendemain et `get_tasks` : dis en deux phrases à quoi ressemble demain (premier rendez-vous, ce qui a été reporté à demain). L'application montre le programme, les départs, les repas et la séance, et pose elle-même les questions de trajet sous forme de boutons.

**Les réponses de trajet.** Un message qui commence par « Bilan du soir, trajet de demain · événement <id> » est la réponse à l'une de ces questions. Enregistre-la avec `set_trip` (`eventId`, `decidedBy: "user"`) :

- origine : « de la maison » → `origin: "home"` ; « de ma position » → `position` ; « de l'événement <id> » → `event:<id>`. `decidedBy: "user"` fait garder l'origine par la planification du matin.
- mode : « en voiture », « en transports », « à vélo », « à pied » → `drive`, `transit`, `bicycle`, `walk`, avec l'origine déjà choisie (lis `list_trips` pour la reprendre).
- stationnement : « dans la rue » ne change rien ; « dans un parking » ou une adresse dite à voix haute : `save_place` met `parkingAddress` sur le lieu de l'événement, s'il est connu.

Confirme en une phrase, sans reposer la question. N'appelle pas `save_bilan_step` pour ces réponses : l'application note ce qu'elle a déjà demandé dans `state.answers.trips`. Quand tu écris `save_bilan_step`, omets `answers` : ils sont conservés.

Termine par une formule de bonne nuit, courte.

## Quitter

S'il arrête en route (« on reprendra », « je vais dormir »), le dernier `save_bilan_step` garde l'étape : ne dis pas que tout est perdu, dis qu'on reprendra où il en est.

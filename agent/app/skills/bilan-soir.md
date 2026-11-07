---
name: bilan-soir
description: "Le bilan du soir : finir la journée avec l'utilisateur en cinq étapes (Journée, Finance, Santé, Consignes, Demain), à sa demande (« on fait le point ») ou quand la proaction « Bilan de la journée » arrive."
tags:
- bilan-soir
- bilan
- moment:bilan-soir
- vocal
---

Quand l'utilisateur dit « on fait le point », « faisons le bilan », « bilan de la journée », ou quand la proaction « Bilan de la journée » arrive (l'heure de coucher apprise, 21 h 30 à défaut).

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

Demande comment s'est passée la journée : moral et fatigue sur dix, et une note libre s'il veut. Enregistre-les dans `answers` (`mood`, `fatigue`, `note`). S'il ne veut pas répondre, passe.

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

Lis `get_events_by_date` pour le lendemain et `get_tasks` : dis en deux phrases à quoi ressemble demain (premier rendez-vous, ce qui a été reporté à demain). Les questions de trajet ne sont pas de ce rendez-vous. Termine par une formule de bonne nuit, courte.

## Quitter

S'il arrête en route (« on reprendra », « je vais dormir »), le dernier `save_bilan_step` garde l'étape : ne dis pas que tout est perdu, dis qu'on reprendra où il en est.

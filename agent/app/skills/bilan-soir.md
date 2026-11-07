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

À **chaque passage** d'une étape à la suivante, et à chaque réponse qui change `answers` ou `instructions`, appelle `save_bilan_step` (`day`, `step`, et `answers` / `instructions` complets seulement s'ils ont changé : ce qui est omis est conservé).

## 2. Journée (`journee`)

Lis la journée : `get_events_by_date` (`date` du jour) pour l'agenda fait, `get_tasks` avec `view: "today"` pour les tâches. Dis en deux phrases ce qui a eu lieu, puis reprends les tâches du jour non terminées, une par une ou groupées : pour chacune l'utilisateur choisit `done` (c'est fait), `tomorrow` (demain) ou `later` (plus tard). Mets à jour les tâches en conséquence (`update_task`) et note le choix dans `answers.tasks` (`taskId`, `choice`).

## 3. Finance (`finance`)

Appelle `get_finance_scores` avec `date`. Dis où en est la semaine en une phrase, **sans verdict** : ni « bien », ni « mal », ni « attention », ni conseil. Un outil en erreur : saute l'étape en une phrase, sans bloquer le bilan.

## 4. Santé (`sante`)

Demande comment s'est passée la journée : moral et fatigue sur dix, et une note libre s'il veut. Enregistre-les dans `answers` (`mood`, `fatigue`, `note`). S'il ne veut pas répondre, passe.

## 5. Consignes (`consignes`)

Demande s'il a des consignes pour demain. Chacune est classée **par toi** dans l'une des trois sortes, puis créée avec l'outil qui convient :

- **Rappel** (`reminder`) : une chose à faire ou à se rappeler à une heure précise de demain (« rappelle-moi d'appeler le garage à 9 h ») → `schedule_proaction` à cette heure, demain.
- **Tâche** (`task`) : une chose à faire demain, sans heure (« ajoute acheter du pain ») → `create_task` avec `dueDate` de demain. À cette étape, « acheter du pain » est une tâche, jamais `add_grocery_item` : la liste de courses n'est pas une consigne.
- **Consigne** (`instruction`) : une règle qui dure ou revient (« le mardi, je pars plus tôt ») → `add_instruction` (jamais `create_task`, même si la règle parle de demain), apprise en mémoire comme tout ce qu'il t'apprend en conversation : jamais un réglage ni une préférence codée. Le `kind` d'`add_instruction` est celui que la règle appelle (`planning` pour ce qui revient à date fixe, `behavior` pour ta façon de répondre).

**Provenance `bilan-soir`.** Ce qui naît de ce rendez-vous le dit, pour que le digest du lendemain le retrouve : la fin du `prompt` et du `summary` du rappel, de la `description` de la tâche (« Provenance : bilan-soir ») et du `content` de la consigne porte « (bilan-soir) ».

**Tu hésites** (« demain, il faut que je pense au dentiste » : un rappel à une heure, une tâche, une règle ?) : ne devine jamais. Pose une question fermée qui nomme les choix (« un rappel, une tâche pour demain, ou une consigne que je retiens ? »). L'écran de l'étape les montre en cases à cocher, avec « Valider » ; à l'oral, il répond d'un mot. Tu ne crées rien avant sa réponse.

Chaque consigne créée entre dans `instructions` (`id`, `text`, `kind` parmi `reminder`, `task`, `instruction`, `targetId` = l'id du rappel, de la tâche ou de la consigne créé) : appelle `save_bilan_step` avec la liste **complète**. Redis-lui ce que tu as noté, classé : « un rappel demain à neuf heures, une tâche pour demain, une consigne retenue ».

## 6. Demain (`demain`)

Lis `get_events_by_date` pour le lendemain et `get_tasks` : dis en deux phrases à quoi ressemble demain (premier rendez-vous, ce qui a été reporté à demain). Les questions de trajet ne sont pas de ce rendez-vous. Termine par une formule de bonne nuit, courte.

## Quitter

S'il arrête en route (« on reprendra », « je vais dormir »), le dernier `save_bilan_step` garde l'étape : ne dis pas que tout est perdu, dis qu'on reprendra où il en est.

---
name: taches
description: "Tâches : créer, lire, programmer, répéter ou chronométrer une tâche, un rappel de chose à faire, ou répondre à un « c'est fait »."
tags:
- taches
---

« rappelle-moi de / d'aller FAIRE X [moment] » (une chose que LUI doit faire, même avec « demain après-midi ») → TÂCHE via create_task avec dueDate = le jour visé : le digest du matin la porte. Ne promets une heure précise que si tu l'as programmée (TÂCHES, PROGRAMMÉE OU ÉCHÉANCE), et ne passe pas par schedule_proaction. Un « demain » dit entre minuit et 5 h désigne le jour qui commence ; dans le doute, demande « ce matin ou vendredi ? ».

TÂCHES, LA JOURNÉE : quand on te parle de la journée (« qu'est-ce que j'ai aujourd'hui ? », « ma journée »), lis les tâches avec get_tasks et day (la date du jour), en plus de l'agenda. Elles arrivent déjà triées par orderScore : cite d'abord celles en retard (dis de combien de jours, d'après overdueDays), puis celles dont urgency est high ou critical, puis le reste, sans changer l'ordre reçu.

TÂCHES, UNE POUR UN JOUR : quand la journée est légère (peu d'événements et aucune tâche high ou critical dans celles du jour), lis get_tasks avec dated false et propose UNE seule tâche, la première renvoyée, en une phrase qui demande quand la faire. N'appelle update_task (dueDate) qu'après l'accord de l'utilisateur sur un jour.

TÂCHES, LISTE ET ÉCHÉANCE : une liste de choses à faire ou à ne pas oublier va dans la description de la tâche, en cases Markdown (`- [ ] chose`), jamais en sous-tâches. Un jour visé (« pour mon weekend », « pour lundi ») donne une dueDate : pour le weekend, le vendredi qui précède.

TÂCHES, PROGRAMMÉE OU ÉCHÉANCE : le jour où il compte la faire est la programmation (agenda et tâches de ce jour-là) ; la date limite, l'échéance. « mets-la vendredi soir » → update_task avec scheduledDate = ce vendredi et scheduledMoment evening (morning le matin, afternoon l'après-midi). « vendredi à 18 h » → scheduledMoment time et scheduledTime "18:00" : le rappel part à l'heure, ne passe reminders que s'il en nomme (« rappelle-moi 30 min avant » → [30]). « avant samedi », « pour samedi au plus tard » → dueDate, pas la programmation. Pour déprogrammer : clear ["scheduledDate"].

TÂCHES, CHRONOMÉTRÉE : une tâche au minuteur, dont la durée est la tâche elle-même (« méditer 20 min »), se crée avec timed true et estimatedMinutes = cette durée. Une durée qui n'est qu'une estimation (« appeler le garage, 10 min ») remplit estimatedMinutes seul, jamais timed.

TÂCHES, RÉPÉTÉE : UNE seule create_task avec rrule (sans « RRULE: » ; « un mardi sur deux » = FREQ=WEEKLY;INTERVAL=2;BYDAY=TU ; une fin = UNTIL, jamais COUNT) et scheduledDate = sa première date ; heure et rappels comme une tâche programmée. Jamais une tâche par date, ni routine, ni directive. Pour arrêter : update_task, clear ["rrule"].

TÂCHES, LA CARTE : après create_task, sa carte (titre, jour, liste) s'affiche sous ta réponse : n'en recopie rien, ni dans le chat ni dans une interruption ; dis seulement ce que tu as fait.

Un « c'est fait » sans objet clair : lis les rappels et tâches du jour et agis sur l'identifiant qu'ils renvoient.

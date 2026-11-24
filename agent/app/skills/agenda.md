---
name: agenda
description: "Agenda : toute question sur un rendez-vous, un événement, un repas planifié, ou limiter les lectures à certains agendas."
tags:
- agenda
---

Agenda : toute question sur l'agenda (« quand », « à quelle heure », « mon prochain rendez-vous ») passe par get_events (from, to), dans ce tour : jamais une date ou une heure de mémoire ni d'après l'historique. Dans l'historique, un jour précédé d'une ligne « — le mardi 6 octobre — » se compte depuis ce jour-là ; aujourd'hui est le jour de la ligne de date à la fin de ce prompt.

Agenda, un événement par son nom : pour supprimer, déplacer ou dire quand a lieu un événement nommé (« le piano du jeudi »), appelle get_events avec query (le mot distinctif du titre ou du lieu) et sans to : la recherche couvre l'année qui vient et tous les agendas, « Repas » compris. Ne passe pas agendas, même si une règle « ne me parle que de … » est enregistrée, sauf si l'utilisateur limite les agendas dans CE message. Un nom dicté est souvent mal transcrit (« Lubus » pour « L'UBU ») : si rien ne remonte, réessaie avec une variante avant de poser la question ; ne conclus pas qu'il n'existe pas d'après une fenêtre de 7 jours, ne devine pas une date. Une série se désigne par son événement maître (l'id renvoyé). Un repas planifié (un plat, un menu) n'est pas un événement : cherche-le avec manage_meals, action list et query, sans dates, puis déplace-le avec update (mealId, date, slot lunch ou dinner : « midi » = lunch, « ce soir » = dinner). Si get_events renvoie un hint, suis-le avant de demander quoi que ce soit.

Agenda, se limiter à certains agendas : « ne me parle que de Défaut et Garde Tom » est une règle que tu te donnes : enregistre-la avec add_instruction (kind behavior), puis passe agendas à chaque lecture d'événements. Elle borne les lectures de l'agenda, jamais une recherche par nom d'un événement désigné. Sans règle de ce genre, ne filtre rien.

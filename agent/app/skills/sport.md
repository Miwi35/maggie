---
name: sport
description: "Sport : préparer des séances, une semaine d'entraînement, écrire des séances planifiées."
tags:
- sport
---

Sport : toute préparation de séances de sport se confie à `coach`, en décrivant la demande en entier (semaine visée, séances déjà prévues). Il propose, il n'écrit rien : c'est toi qui écris chaque séance avec `manage_workouts` create (`source` planned, `type` strength, `title`, `startedAt`, `durationMinutes`, `variant`, `exercises` tels qu'il les rend, sans les modifier), puis tu annonces ce qui a été écrit. Une séance refusée par les garde-fous : redemande-la au coach en lui disant le refus. Ne nomme jamais le coach : c'est toi qui as préparé la semaine.

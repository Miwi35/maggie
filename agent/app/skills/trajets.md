---
name: trajets
description: "Trajets du jour : origine, mode, stationnement, séjour et retour, à la planification du matin, au replan et en conversation."
tags:
- trajets
- moment:planification
---

Pour chaque événement du jour qui a un lieu et qui n'a pas encore de trajet (`list_trips` avec la date du jour), dans l'ordre des heures. Ne repose jamais un trajet qui existe déjà.

## Origine

Choisis l'origine dans cet ordre, la première règle qui répond l'emporte :

1. L'utilisateur l'a choisie (un trajet `originDecidedBy=user`) : garde-la, avec `decidedBy=user` dans `set_trip`.
2. Ses habitudes (`list_instructions`, ta mémoire).
3. Le premier trajet de la journée part de `position`, l'endroit où le téléphone était en dernier.
4. Pour les suivants, `place:<id>` de la maison (`list_places`) s'il reste au moins 30 minutes sur place après l'aller-retour.
5. Sinon `event:<id>` de l'événement précédent : tu enchaînes.

Ne mets `decidedBy=user` que pour une origine que l'utilisateur a lui-même choisie.

## Mode

Appelle `estimate_trip` avec l'origine et les modes plausibles (`drive`, `transit`, `walk`, `bicycle`). Écarte ceux que la météo, la distance ou ses habitudes rendent absurdes. Compare les durées et choisis. Enregistre le choix avec `set_trip` (`eventId`, `mode`, `origin`). Pour un événement répété, passe `date` (le jour planifié, `YYYY-MM-DD`) à `estimate_trip` et `set_trip` : chaque occurrence a son trajet.

En voiture depuis la maison ou un lieu, le calcul compte la voiture à l'origine. Si l'utilisateur dit que sa voiture est restée ailleurs, passe `carAt=parked` ; s'il l'a avec lui en partant de sa position, `carAt=origin`.

## Stationnement

En voiture, si le trajet répond `parkingKnown=false`, dis-le à l'utilisateur et demande-lui où il se gare. Pose une proaction non silencieuse (`schedule_proaction`) dans quelques minutes, au résumé « Où se garer · <titre> ». Enregistre sa réponse avec `save_place`.

## Doute

Le doute est réel quand deux choix se valent : rentrer à la maison ou enchaîner, deux modes à égalité. Estime chaque choix (`estimate_trip` pour chaque origine) pour connaître son `departAt`. Enregistre quand même le meilleur choix par défaut. Pose ensuite une question avec `schedule_proaction`, non silencieuse, dans quelques minutes, au résumé « Où partez-vous · <titre> » : « Je repasse à la maison ou j'enchaîne ? », avec pour chaque choix l'heure de départ qu'il donne. Il peut répondre « Plus tard ».

## En conversation

Quand l'utilisateur te parle des trajets ou des départs, sans que ce soit la planification :

1. Lis d'abord `list_trips` du jour concerné.
2. Un trajet en place reste tel quel, sauf demande explicite de changement (mode, origine, heure).
3. Une remarque sur l'affichage (« je ne vois pas les trajets ») n'est pas une demande de recalcul : dis ce qui est enregistré, sans rien écrire.
4. Avant tout `set_trip`, appelle `estimate_trip` et compare au trajet en place, parce que `set_trip` remplace le trajet de l'événement.
5. Un résultat incohérent (une marche de plus d'une heure, une durée très au-delà de celle du trajet en place, un `departAt` déjà passé) n'enregistre jamais : garde le trajet en place et dis-le.
6. `decidedBy=user` seulement pour une origine nommée par l'utilisateur dans la conversation (« je pars de chez… », « j'enchaîne ») ou pour sa réponse à une question d'origine ; sinon `maggie`.

## Séjour

Un événement en journée entière (`allDay`) qui a un lieu, qui commence le jour même ou le lendemain et n'a pas de trajet n'a pas d'heure d'arrivée : demande-la, ne l'invente pas. Sans lieu, ne demande rien.

- Si une proaction « Arrivée · <titre> » existe déjà pour lui (`list_proactions` sur toute la durée du séjour, faite, annulée ou à venir), ne la repose pas : l'utilisateur a déjà répondu, « Pas de trajet » compris.
- Sinon pose-la avec `schedule_proaction`, non silencieuse, dans quelques minutes (ce soir pour le lendemain), avec un prompt qui cite l'id de l'événement.
- La question est une interruption : « Vers quelle heure arrivez-vous à <lieu court> ? ». Les choix sont trois heures plausibles pour ce séjour, puis « Autre heure » et « Pas de trajet ».
- Réponse, une heure : `set_trip` avec `arriveAt` (ISO 8601, dans le fuseau de l'utilisateur donné par `date_time`, le jour d'arrivée) et `decidedBy=user`. Le mode et l'origine se choisissent comme pour un trajet ordinaire, avec `estimate_trip` et le même `arriveAt` d'abord.
- « Autre heure » : demande l'heure. « Pas de trajet » : n'enregistre rien.
- Pendant le séjour, les trajets des jours suivants partent du lieu du séjour : origine `event:<id>` du séjour plutôt que la maison, sauf si l'origine est `position`.

## Retour de séjour

Un événement en journée entière qui a un lieu et dont le dernier jour est le jour même ou le lendemain n'a pas de trajet `return` (`list_trips` ne le montre pas avec `leg=return`) : demande l'heure de départ du lieu, ne l'invente pas. Sans lieu, ne demande rien.

- Sans maison connue (`list_places` n'en donne pas), ne pose pas la question : dis-le en une ligne dans ton compte rendu (« Retour · <titre> : pas de maison connue »).
- Si une proaction « Retour · <titre> » existe déjà pour lui (`list_proactions` sur toute la durée du séjour, faite, annulée ou à venir), ne la repose pas : l'utilisateur a déjà répondu, « Pas de trajet » compris.
- Sinon pose-la avec `schedule_proaction`, non silencieuse, dans quelques minutes si le séjour finit le jour même, ce soir s'il finit le lendemain. Le prompt cite l'id de l'événement.
- La question est une interruption : « À quelle heure repartez-vous de <lieu court> ? ». Les choix sont trois heures plausibles pour ce départ, puis « Autre heure » et « Pas de trajet ».
- Réponse, une heure : `set_trip` avec `leg=return`, `departAt` (ISO 8601, dans le fuseau de l'utilisateur donné par `date_time`, le dernier jour) et `decidedBy=user`, sans origine : le trajet part du lieu et rentre à la maison.
- Le mode se choisit comme pour un aller, avec `estimate_trip` (`leg=return` et le même `departAt` d'abord).
- « Autre heure » : demande l'heure. « Pas de trajet » : n'enregistre rien.

## Les vérifications

N'en programme aucune : l'API vérifie avant chaque départ.

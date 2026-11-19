---
name: recette
description: "Une recette à structurer, lue (le Recipe de read_web_page), collée ou dictée : la proposer complète, puis la créer seulement après le « oui »."
tags:
- recette
- cuisine
---

Quand l'utilisateur te donne une recette à enregistrer : une page lue (le Recipe de `read_web_page`), un texte collé ou dicté. Elle devient une proposition complète, montrée **avant tout create_recipe**.

## 1. Construire la proposition

- **Nom et portions** : ceux de la source. Sans portions, demande-les.
- **Ingrédients** : cherche d'abord dans le catalogue (`search_ingredients` → `ingredientId`), sinon dans Ciqual (`search_ciqual_foods` → `ciqualAlimCode`).
- **Quantité et unité** : g, kg, ml, l, cl, piece, bunch, can, bottle, pack, sachet, jar (« 2 gousses d'ail » = 2 piece).
- **Ingrédient incertain** : si ni le catalogue ni Ciqual ne le donne avec certitude, signale-le « non rapproché ». Ne le devine jamais, ne le remplace pas par un voisin.
- **Étapes** : courtes, dans l'ordre. `durationMinutes` quand la source donne une durée, `background` true quand ça cuit ou repose seul, `waitsFor` = les positions des étapes à finir avant, `ingredientIds` = ceux qu'elle emploie. N'invente ni durée ni dépendance.
- **Ustensiles** : ceux que la source nomme, avec le nom du catalogue (`manage_utensils` list).
- **Notes** : « Source : … » et les astuces.

## 2. Proposer

Montre le tout dans le chat : ce détail est une exception à l'interdiction des listes. Puis demande « Je la crée ? ».

## 3. Créer

- Tu n'écris qu'après le « oui ». Sans « oui », rien n'est créé.
- `create_recipe` reçoit alors TOUT, explicitement : servings, ingredients, steps, utensils, notes.
- Un ingrédient cité par une étape se désigne par son `ingredientId`, ou par son nom exact dans Ciqual (`alimNameFr`) s'il vient d'être créé depuis Ciqual.
- Un ingrédient resté « non rapproché » au « oui » ne va pas dans ingredients : note-le dans notes et dis-le.

## 4. Corriger

Une correction (« plutôt 6 personnes », « sans l'ail ») refait la proposition entière, sans rien créer.

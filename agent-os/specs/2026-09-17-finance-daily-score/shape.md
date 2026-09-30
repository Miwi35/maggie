# Maggie Finance — Score quotidien — Shape

## Problem
La doc ([M4](../../product/finance-functional-spec.md)) veut un **signal quotidien sans jugement** : vert / neutre / orange / rouge, lisible d'un coup d'œil, qui dit où on en est sans faire la morale. C'est la pièce qui relie ce qui existe déjà — enveloppes, statuts, matelas — en une seule réponse à « est-ce que ça va ? ».

Tout est en place pour le calculer : la consommation par enveloppe (tranche 6), le matelas et son `blocksGreenScore` (tranche 7), l'historique des transactions depuis la tranche 3. Rien ne l'expose.

## Solution
Un score calculé à la lecture, sans stockage ni entité : `GET /api/finance/daily-score` et le tool MCP correspondant.

Quatre niveaux, dans cet ordre de priorité :

| Score | Quand |
|-------|-------|
| 🔴 Rouge | le budget total du mois est dépassé, ou une catégorie **obligatoire** l'est |
| 🟠 Orange | une catégorie **non-obligatoire** est dépassée, ou les plans engagés feraient passer au-dessus |
| 🔵 Neutre | dans les clous |
| 🟢 Vert | rien de dépassé, **matelas complet**, et on dépense moins que le même mois l'an dernier |

Le score **ne vient jamais seul** : il est accompagné des raisons qui l'expliquent, en clair (« Loisirs dépassé de 45 € », « matelas en constitution »). Un signal sans sa cause est un jugement ; avec sa cause, c'est une information.

## Boundaries (hors périmètre)
- **Pas d'impact sur la date N ni sur les projets** — ni les rentes (M9) ni les projets (M10) n'existent ; les colonnes « N +1 à 3 mois » de la doc attendront ces specs.
- **Pas de notification contextuelle de dépense** — elle demande un seuil paramétrable et le canal de notification ; c'est une tranche à part.
- **Pas d'historisation du score** — il se recalcule ; une courbe de scores n'a d'intérêt qu'avec la revue mensuelle (M5).
- **Pas de réglage utilisateur des seuils** — les seuils sont posés en constantes documentées ; les rendre configurables sans retour d'usage serait prématuré.

## Key Decisions
1. **Le matelas ne peut que bloquer le vert, jamais dégrader en orange ou rouge.** C'est la lettre de la doc : un matelas en construction est un état normal, pas une faute.
2. **Obligatoire vs non-obligatoire décide de rouge ou orange.** Dépasser sur l'alimentation n'a pas le même sens que dépasser sur les loisirs ; le flag `obligation` de la catégorie porte déjà cette distinction.
3. **La comparaison J-365 est une condition du vert, pas un critère de dégradation.** Dépenser plus que l'an dernier peut être parfaitement légitime (un déménagement, un enfant) : cela empêche de se féliciter, cela n'accuse pas.
4. **Aucune entité, aucune migration** : le score est une lecture des données existantes. Le stocker créerait un état à maintenir cohérent sans bénéfice.
5. **Les raisons sont des données structurées** (code + libellé + montant), pas des phrases figées : l'admin, le mobile et l'agent les rendent chacun à leur façon.

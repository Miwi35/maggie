# Maggie Finance — Revue mensuelle — Shape

## Problem
Le score quotidien dit où on en est ; il ne dit pas **ce qu'on aurait fait autrement**. La doc ([M5](../../product/finance-functional-spec.md)) prévoit un rendez-vous mensuel : reprendre les dépenses non-obligatoires du mois écoulé, dire pour chacune « à conserver » ou « j'aurais pu m'en passer », et en tirer un score d'optimisation suivi dans le temps, comparé aux trois derniers mois et à l'an dernier.

C'est la seule donnée du module qu'aucun calcul ne peut produire : elle demande un jugement humain, après coup. Et c'est elle qui transforme un budget en apprentissage.

## Solution
Un champ **`retrospect`** sur `Transaction` — `unrated` / `keep` / `avoidable` — posé après coup, et une revue qui se calcule à partir de lui.

La revue d'un mois rend trois choses :
1. **Ce qu'il reste à qualifier** : les dépenses non-obligatoires du mois, les plus grosses d'abord, parce que c'est là que le jugement rapporte.
2. **Le score d'optimisation** : la part du montant non-obligatoire jugée évitable — et donc ce qu'un mois identique pourrait rendre.
3. **Les comparaisons** : mois précédent, moyenne des trois derniers, même mois l'an dernier.

Seules les dépenses **non-obligatoires** sont soumises au jugement : demander à quelqu'un s'il aurait pu se passer de son loyer ou de ses courses n'apporte rien et donne le sentiment d'être jugé, ce que la doc refuse explicitement.

## Boundaries (hors périmètre)
- **Pas de notification le 1er du mois** — elle demande un cron et le canal de notification ; le module `notification` existe, mais l'y brancher est une tranche à part.
- **Pas d'impact sur la capacité d'investissement** : la capacité d'épargne existe (M8), l'investissement non (M11).
- **Pas de relance du moteur de scénarios** — il n'existe pas.
- **Pas d'historique de scores stocké** : chaque revue se recalcule depuis les qualifications, qui sont la donnée durable.

## Key Decisions
1. **La qualification vit sur la transaction**, pas dans une entité de revue : c'est un attribut de la dépense, qui survit à n'importe quel découpage de période.
2. **Trois états dont `unrated`**, pas un booléen nullable : « pas encore regardé » est un état de la revue, pas une absence de valeur.
3. **Seules les non-obligatoires sont proposées** au jugement (flag `obligation` de la catégorie), et les transactions sans catégorie le sont aussi — une dépense non classée est précisément celle qu'on veut regarder.
4. **Le score est une part de montant, pas de nombre de lignes** : dix cafés évitables ne pèsent pas un voyage.
5. **Le score ne descend jamais d'un piédestal** : un mois sans rien d'évitable vaut 100 %, un mois non qualifié n'a pas de score du tout (`null`), il n'est pas noté zéro.

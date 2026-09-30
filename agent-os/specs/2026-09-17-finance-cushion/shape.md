# Maggie Finance — Matelas de sécurité — Shape

## Problem
La doc ([M7](../../product/finance-functional-spec.md)) en fait le **principe n°1** du module : aucun investissement, aucun projet ne démarre tant que le filet de sécurité n'est pas constitué, et tant qu'il est incomplet le score global ne peut pas être vert.

Aujourd'hui, rien. Le flag `Account.isCushion` existe depuis la tranche 1 et ne sert à rien : personne ne sait si le matelas est constitué, entamé, ni combien de temps il faudrait pour le recharger. C'est aussi la dépendance qui bloque le score quotidien (M4).

## Solution
Un **objet de configuration unique par utilisateur** — `SafetyCushion` — sur le pattern `/api/user_preferences/me` déjà en place dans le repo : cible en mois de salaire net, plafond de recharge mensuel, horizon de recharge souhaité.

Le **montant courant n'est pas saisi** : c'est la somme des soldes des comptes marqués `isCushion`. Le flag prend enfin son sens, et le matelas suit automatiquement les comptes sans double saisie.

Trois états, déduits :

| État | Condition |
|------|-----------|
| Constitution en cours | la cible n'a jamais été atteinte |
| Complet | le montant courant couvre la cible |
| En recharge | la cible a déjà été atteinte, puis entamée |

Le **plan de recharge** découle du plafond : mensualité théorique = déficit / horizon souhaité ; si elle dépasse le plafond, c'est le plafond qui s'applique et **la durée s'allonge** — jamais l'inverse. C'est le point de la doc : ne pas étrangler le budget pour rattraper un matelas.

## Boundaries (hors périmètre)
- **Pas de calcul de score** — cette spec fournit le drapeau `blocksGreenScore` que `finance-daily-score` consommera ; elle ne juge rien.
- **Pas de blocage effectif des projets ni de la capacité d'investissement** — ces objets n'existent pas encore (M10/M11).
- **Pas de détection d'entame en temps réel par notification** — l'état est calculé à la lecture ; la notification appartient à la couche score.
- **Pas de recalcul automatique du salaire net** depuis les transactions — le revenu de référence est saisi ; le déduire du réel est un travail de train de vie (M9).

## Key Decisions
1. **Le montant courant est dérivé des comptes `isCushion`**, jamais saisi. Une seule source de vérité, et le flag existant trouve son usage.
2. **Singleton par utilisateur** sur le pattern `UserPreference` (`/api/safety_cushions/me`, provider qui crée à la volée) plutôt qu'une collection : il n'y a qu'un matelas.
3. **`completedAt` marque la première fois que la cible est atteinte** — c'est la seule façon de distinguer « en constitution » de « en recharge », et cela survit à une entame.
4. **Le plafond l'emporte sur la durée.** Si le déficit ne rentre pas dans l'horizon souhaité sans dépasser le plafond, on allonge la durée. Le plafond est une protection du budget, pas une suggestion.
5. **Cible = mois × revenu net de référence**, recalculée à chaque lecture plutôt que stockée : elle suit un changement de salaire sans migration de données.

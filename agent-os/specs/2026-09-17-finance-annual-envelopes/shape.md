# Maggie Finance — Enveloppes annuelles & statuts — Shape

## Problem
`Transaction.status` existe depuis la tranche 3 (dépensée / engagée / planifiée / à arbitrer) mais **ne sert à rien** : le calcul de budget somme tous les débits sans distinction. Or c'est précisément ce qui fait vivre une enveloppe annuelle ([doc M3](../../product/finance-functional-spec.md)) :

- un billet de concert acheté en novembre pour un événement en juin est **engagé** — l'argent est sorti, l'enveloppe doit être amputée tout de suite ;
- un voyage décidé mais pas payé est **planifié** — il ne réduit pas le consommé, mais il réduit ce qui reste disponible ;
- un projet encore en débat est **à arbitrer** — il ne compte pas, mais on veut voir son impact avant de trancher.

Deuxième manque : chaque mois il faut recréer les enveloppes à la main, alors que la doc demande une **reconduction avec ajustement proposé** sur la base de la période écoulée.

## Solution
Le statut décide de la manière dont une transaction pèse sur l'enveloppe :

| Statut | Poids sur l'enveloppe |
|--------|----------------------|
| Dépensée | consommé |
| Engagée | consommé (dès l'achat) |
| Planifiée | réservé — pas consommé, mais retiré du disponible |
| À arbitrer | rien — reporté à part, pour voir l'impact avant décision |

`GetBudgetStatus` ventile donc chaque enveloppe en `spentCents`, `committedCents`, `plannedCents` et `toArbitrateCents`, puis en deux totaux qui répondent aux deux questions réelles : **consommé** (dépensé + engagé, ce qui est irréversible) et **disponible** (ce qui reste vraiment libre une fois le planifié réservé).

La **reconduction** crée les enveloppes d'une période à partir d'une autre : même montant, ou montant ajusté sur le consommé réel de la période source. Elle ne touche jamais une enveloppe déjà présente sur la période cible, donc elle est rejouable.

## Boundaries (hors périmètre)
- **Pas de session de planification annuelle** (pré-saisie guidée des événements de l'année) — c'est une UI à part entière, elle mérite sa spec.
- **Pas de reconduction automatique planifiée** (cron le 1er du mois) — la reconduction est déclenchée par l'utilisateur ; l'automatiser viendra avec la revue mensuelle, qui a déjà son créneau de notification.
- **Pas d'alerte de dépassement** — c'est la couche score/notification (`finance-daily-score`).
- **Pas de changement du modèle de données** : les statuts et les enveloppes existent déjà ; cette tranche est du calcul et de l'orchestration.

## Key Decisions
1. **Consommé = dépensé + engagé.** Un engagement est de l'argent déjà sorti ; le distinguer du dépensé sert à l'affichage, pas au calcul du reste.
2. **Planifié réservé, pas consommé.** Deux nombres plutôt qu'un : `remainingCents` (après consommé) et `availableCents` (après consommé et planifié). Fondre les deux ferait mentir l'un des deux usages.
3. **À arbitrer reporté mais jamais déduit** — c'est la valeur de ce statut : voir l'impact d'une dépense envisagée sans qu'elle pollue le budget.
4. **La reconduction n'écrase jamais** une enveloppe existante sur la période cible, et rapporte ce qu'elle a créé et ignoré — même logique que l'application des règles de catégorisation, rejouable sans dégât.
5. **Ajustement sur le consommé réel** (option `useActualSpending`), pas sur une moyenne glissante : une règle qu'on peut vérifier de tête à partir du mois affiché.

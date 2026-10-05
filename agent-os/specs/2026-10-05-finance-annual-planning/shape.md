# Maggie Finance — Session de planification annuelle — Shape

## Problem
Les enveloppes annuelles existent, et le statut d'une transaction pèse dessus comme il faut (`finance-annual-envelopes`, livré). Mais **rien ne remplit une année**. La reconduction copie des montants d'une période sur une autre ; elle ne sait rien des **événements** qui font une année : un festival en juillet, un voyage en août, un ordinateur en mars.

Or c'est par là que l'utilisateur raisonne. La doc ([§6.4](../../product/finance-functional-spec.md)) décrit un rendez-vous de novembre/décembre : on reprend les grosses dépenses de l'année écoulée, on reconduit ou on écarte, on ajoute les projets envisagés, et **les montants validés deviennent les enveloppes annuelles de l'année suivante**.

Aujourd'hui, faire cela demande de créer à la main une transaction planifiée par événement et une enveloppe par catégorie, en relisant soi-même l'année précédente dans la liste des transactions. Personne ne le fait.

## Solution
Un **plan annuel** qui se lit et s'applique, sur une année cible.

**Lire** (`GET /api/finance/annual-plan?year=2027`) rend, pour chaque catégorie que l'année précédente a coûtée :
- ce qu'elle a réellement consommé, et ce qu'elle avait budgété ;
- ses **grosses dépenses** — les débits au-dessus d'un seuil, ceux qu'on reconduit ou pas : c'est la matière de la session ;
- ce qui est **déjà décidé** sur l'année cible — planifié, engagé, à arbitrer à part — pour que la session se rouvre sans se répéter ;
- un **montant suggéré** et l'enveloppe annuelle déjà posée, s'il y en a une.

**Appliquer** (`POST /api/finance/annual-plan`) écrit ce que l'utilisateur a validé : des **transactions planifiées** (un événement = une transaction, dans le mois où il tombe) et des **enveloppes annuelles** (une catégorie = un montant). Rejouable : réappliquer le même montant d'enveloppe ne crée pas de doublon.

Côté conversation, l'outil MCP `plan_annual_budget` fait les trois gestes de la session — `review`, `schedule`, `budget` — un événement à la fois, comme on les dicte.

## Boundaries (hors périmètre)
- **Pas d'impact sur N ni sur les projets** (doc §6.4 point 4) : le compteur d'indépendance (M9) et les projets (M10) n'existent pas. Ce que la session rend, c'est un budget annuel, pas une date.
- **Pas de déclenchement automatique en novembre** : la notification demande un cron et le canal `notification`, comme la relance de la revue mensuelle — même créneau, autre tranche.
- **Pas d'écran** dans cette tranche : l'admin React et le mobile Kotlin sont deux tickets de suite, bloqués par celui-ci. La limite de taille du garde-fou (800 lignes hors tests) ne laisse pas passer la tranche verticale entière, et la découper par couche est ce que le repo demande.
- **Pas de détection des récurrences** : « les événements récurrents de l'année précédente » (doc §6.4 point 2) sont ici les **grosses dépenses** de l'année écoulée, pas un motif inféré. Une détection d'abonnements est déjà reportée par `finance-categorization-rules`.
- **Pas de dépense sans catégorie** dans le plan : la session produit des enveloppes, qui budgètent une catégorie. Un débit non catégorisé n'a nulle part où aller.
- **Pas de modèle de données nouveau** : le plan n'est pas stocké. Les transactions planifiées et les enveloppes **sont** le plan.

## Key Decisions
1. **Le plan n'est pas une entité.** Il se lit des transactions et des enveloppes, comme la revue mensuelle se lit des verdicts. Stocker un « plan validé » créerait une seconde vérité qui divergerait du premier achat.
2. **Une grosse dépense est un débit au-dessus d'un seuil** (`thresholdCents`, défaut 100 €), pas un rang dans un top N : un seuil se vérifie de tête sur la liste des transactions, un top N dépend de ce que les autres lignes valent. Le seuil décide **quelles dépenses sont listées une par une, jamais quelles catégories entrent dans la session** : une catégorie qui a coûté quelque chose l'an dernier est toujours là, sinon le total « consommé l'an dernier » bougerait avec un paramètre de présentation.
3. **Le montant suggéré est le décidé de l'année cible s'il y en a, sinon le consommé de l'année précédente.** Deux règles, aucune moyenne glissante — même choix que `useActualSpending` dans la reconduction, pour la même raison : l'utilisateur doit pouvoir refaire le calcul à la main.
4. **L'« à arbitrer » n'entre pas dans le suggéré**, il est rendu à part. C'est la règle déjà posée par `finance-annual-envelopes` : voir l'impact d'une dépense envisagée sans qu'elle pèse sur le budget. Les trois statuts restent séparés (`plannedCents`, `committedCents`, `toArbitrateCents`) et portent les noms que `GetBudgetStatus` leur donne déjà, pour qu'un même écran puisse lire les deux charges utiles sans qu'un mot y veuille dire deux choses ; leur somme utile est `decidedCents`.
5. **La session écrit le montant qu'elle a montré, la reconduction ne l'écrase jamais.** `RollOverEnvelopes` sert à ne pas retaper un budget ; la session sert à **décider** d'un budget. Deux use cases, pas un drapeau : fondre les deux ferait qu'un geste de la session se ferait silencieusement ignorer sur une catégorie déjà budgétée.
6. **Un événement se saisit avec un mois, pas une date.** On planifie « le festival, en juillet » ; le jour est inventé. La transaction est posée au 1er du mois, et reste modifiable comme n'importe quelle transaction.
7. **Le montant d'un événement est positif à l'entrée, stocké en débit.** Dans une session de planification, toute ligne est une dépense ; demander un nombre négatif est un piège, et un signe oublié budgéterait une recette.
8. **Un événement planifié n'est jamais `spent`.** Les trois statuts acceptés sont `planned`, `committed` et `to_arbitrate` ; `spent` est refusé — on ne planifie pas ce qui est déjà sorti.
9. **L'année cible par défaut est l'année suivante en novembre et décembre, l'année courante le reste du temps.** La session est un rendez-vous de fin d'année (doc §5.3) ; l'ouvrir en novembre sur l'année qui s'achève n'aurait aucun sens. La règle et les bornes (2000-2100, celles que `Envelope::$year` déclare) vivent dans `PlanningYear`, une seule fois pour les deux canaux : séparées, elles avaient déjà divergé.
10. **Un plan refusé n'écrit rien.** Les commandes `persist`/`flush` une par une, donc tout est lu et validé avant que quoi que ce soit ne soit écrit : sinon un plan rejeté sur sa dernière ligne laisse les premières derrière lui, et la reprise les planifie deux fois.
11. **Un événement planifié n'est pas « exceptionnel » par défaut.** `isExceptional` est accepté sur l'événement et vaut `false`, comme sur tous les autres chemins d'écriture : la session ne prétend rien sur le train de vie mesuré (`sumConsumedBetween` exclut l'exceptionnel, et le score quotidien comme la capacité d'épargne le lisent). Le passer à `true` est une décision de l'utilisateur sur une ligne, pas une conséquence silencieuse d'avoir planifié.
12. **Une catégorie obligatoire reste dans la session.** Contrairement à la revue mensuelle, qui écarte l'obligatoire parce que demander si on aurait pu se passer de son loyer n'apporte rien, la session décide des **budgets** : une assurance annuelle ou une taxe foncière mérite son enveloppe autant qu'un festival.

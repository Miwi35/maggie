# Maggie Finance — Dettes & charges fixes — Shape

## Problem
Le module sait ce qui sort chaque mois (transactions, enveloppes) mais ignore **ce qui est déjà engagé pour des années** : un crédit auto, un prêt immobilier, un prêt étudiant. Résultat, deux questions sans réponse ([M8](../../product/finance-functional-spec.md)) :

- **quand chaque charge se libère-t-elle**, et combien cela rendra-t-il par mois ;
- **quelle est la capacité d'épargne réelle** une fois les mensualités et le train de vie déduits du revenu.

Sans ça, la capacité d'investissement (M10) et la date d'indépendance (M9) reposent sur du vide.

## Solution
Une entité **`Loan`** saisie à la main : capital restant dû, mensualité, taux annuel, priorité. Pas de date de fin saisie — elle se **déduit de l'amortissement**, sinon elle se contredit avec les trois autres champs au premier remboursement anticipé.

L'amortissement se calcule mois par mois, comme le fait une banque : intérêt du mois sur le capital restant, le reste de la mensualité attaque le capital. On en tire la **timeline de libération** : à quelle date chaque prêt s'éteint, et combien de mensualité cela libère.

La **capacité d'épargne nette** = revenu de référence (celui du matelas, déjà saisi) − mensualités de prêts − train de vie estimé, ce dernier étant la moyenne mensuelle réellement consommée sur les trois derniers mois plutôt qu'une saisie de plus.

## Boundaries (hors périmètre)
- **Pas de proposition de réaffectation** à la libération d'une charge — elle vise des projets et des investissements (M10/M11) qui n'existent pas.
- **Pas de simulation de remboursement anticipé** : la doc la chiffre en « gain en mois sur N », et N n'existe pas encore (M9).
- **Pas de saisie détaillée des 7 types de prêts ni d'IRA** — c'est explicitement une spec v1.1 (`finance-loan-details`).
- **Pas d'échéancier stocké** : l'amortissement se recalcule ; le figer créerait un état à réconcilier à chaque paiement.

## Key Decisions
1. **Taux en points de base entiers** (`annualRateBasisPoints` : 350 = 3,50 %), jamais un float — même raison que les montants en centimes : un taux qui dérive fausse tout l'échéancier.
2. **La date de fin est calculée, jamais saisie.** Trois champs suffisent à la déterminer ; un quatrième champ redondant finit toujours par mentir.
3. **Garde sur les prêts qui ne s'amortissent pas** : si la mensualité ne couvre pas l'intérêt du mois, le capital monte au lieu de descendre — c'est refusé à la saisie plutôt que de produire une timeline absurde.
4. **Train de vie mesuré, pas déclaré** : moyenne consommée des trois derniers mois. C'est la seule façon d'avoir une capacité d'épargne qui suit la réalité sans une saisie de plus à maintenir.
5. **Horizon de timeline paramétrable, 60 mois par défaut**, comme la doc — au-delà, la projection vaut ce que vaut le train de vie d'aujourd'hui.

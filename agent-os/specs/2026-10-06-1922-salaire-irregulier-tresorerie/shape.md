# Salaire irrégulier et trésorerie — Shape

Projet Linear : [Salaire irrégulier et trésorerie](https://linear.app/meven/project/salaire-irregulier-et-tresorerie-bad46046e25d) · cadrage [MAG-269](https://linear.app/meven/issue/MAG-269)

## Problem

Le salaire du propriétaire est attendu le **25** et arrive en retard **tous les mois** — 30 jours le mois dernier — parfois **deux mois d'un coup, en un seul virement, le 26**. En attendant, le compte courant passe à découvert et il **puise dans ses économies**. Quand le salaire arrive, il remet l'argent dans chaque source, et le calcul se fait à la main.

Maggie Finance ne sait rien de tout cela, et s'en trouve fausse à trois endroits :

1. **Les mouvements entre ses propres comptes sont comptés comme des dépenses et des recettes.** La recette du 5 oct. mesurait un train de vie de 11 129 €/mois pour 4 950 € de revenus : l'écart, ce sont ses virements internes, comptés une fois au débit sur un compte et une fois au crédit sur l'autre. `sumConsumedBetween` est lu par le train de vie mesuré ([`MeasureMonthlyLifestyle`, MAG-46](https://linear.app/meven/issue/MAG-46)), par la capacité d'épargne, par la revue mensuelle et par la comparaison J-365 du score : une seule requête fausse quatre chiffres.
2. **Le mois est jugé sur la date de paie.** Un mois sans salaire reçu a des recettes nulles, un mois à double salaire en a le double. Aucun des deux ne dit quoi que ce soit des dépenses, qui sont la seule chose que le propriétaire pilote.
3. **Les avances d'épargne n'existent pas.** Ni le montant avancé par source, ni le remboursement, ni la distinction entre un contre-virement de remboursement et le virement d'approvisionnement récurrent de l'épargne — les deux vont du courant vers l'épargne.

## Solution

Quatre briques, livrées dans cet ordre, parce que les deux premières corrigent les chiffres tout de suite et que les deux suivantes s'appuient sur elles.

### 1. Virements internes neutres

Un mouvement entre deux comptes du propriétaire n'est **ni une dépense, ni une recette**. On le reconnaît, on le marque, on l'exclut de tous les agrégats, et on peut le corriger à la main.

Détection : même utilisateur, deux comptes différents, **montants exactement opposés**, même devise, à **4 jours ou moins** d'écart, statuts consommés (`spent` ou `committed`), aucune des deux lignes déjà appariée. Appariement déterministe : la date la plus proche d'abord, puis la plus ancienne, puis le plus petit ULID. La détection tourne à la création d'une transaction (comme la catégorisation par règle) et en **rattrapage** sur l'historique, par un endpoint, comme `POST /api/finance/categorization-rules/apply`.

### 2. Salaire de référence

Un repère stable, indépendant de la date de paie : le **salaire mensuel de référence**, déclaré ou mesuré sur 12 mois, **primes exclues**. La revue mensuelle, le score du jour et le matelas le lisent, plus jamais les recettes du mois.

### 3. Avances d'épargne

Un virement épargne → courant **pendant un retard de salaire** est une **avance**. Le solde à rembourser est connu **par source**. À l'arrivée du salaire, Maggie propose les **contre-virements exacts**, et les rapproche tout seuls quand ils passent sur les comptes. Le virement d'approvisionnement récurrent n'est jamais compté comme un remboursement.

### 4. Vue trésorerie

Le salaire attendu (jour et montant de référence), le **retard en jours**, le **solde projeté** jour par jour jusqu'à l'arrivée du salaire, le **découvert prévu** avec sa date, et l'**avance conseillée** avec sa source. Maggie prévient quand le salaire est en retard et qu'un découvert approche.

## Boundaries (hors périmètre)

- **Aucune action bancaire automatique.** Maggie *propose* un virement, chiffré et sourcé ; elle ne l'exécute pas. Rien dans ce projet n'écrit sur un compte bancaire.
- **Pas de prévision au-delà du mois suivant.** La projection s'arrête à l'arrivée du salaire attendu, ou à 60 jours si rien n'arrive — au-delà, c'est la simulation (M11, v1.1).
- **Pas de détection de récurrence inférée.** Le virement d'approvisionnement n'est pas déduit d'un motif : c'est une ligne que le propriétaire marque, ou une ligne qui reste un virement interne neutre parce qu'elle ne correspond exactement à aucune avance ouverte (décision 11). La détection d'abonnements récurrents est déjà reportée par `finance-categorization-rules`, et la réouvrir ici ferait du virement d'épargne un cas particulier d'un problème non résolu.
- **Pas de multi-devises.** Un virement interne apparie deux lignes de **même devise** ; deux comptes en devises différentes ne produisent jamais des montants exactement opposés. Le sujet appartient à `finance-bridge-sync` (M1), qui apporte la conversion et l'historique des taux.
- **Pas de nouvelle entité de pairage.** Les virements, les avances et les remboursements vivent **sur la transaction** (décision 1).
- **Pas de date N ni d'impact projets.** Le compteur d'indépendance (M9) consomme le train de vie corrigé par la brique 1, et c'est tout ; les projets (M10) n'existent pas.
- **Pas de changement de couleur du score.** Le salaire de référence ne peut que **retenir le vert**, jamais faire passer à l'orange ou au rouge (décision 8).

## Key Decisions

Chaque décision porte sa source : le projet Linear (besoins validés par le propriétaire le 6 oct.), la doc fonctionnelle du module, un standard du dépôt, ou le code existant.

1. **Les virements internes, les avances et les remboursements vivent sur `Transaction`, pas dans une entité de pairage.**
   *Pourquoi :* le module stocke sur la transaction les jugements qu'on porte sur elle — `retrospect` (revue mensuelle), `categorySource` + `categorizedAt` (provenance de la catégorie) — et dérive tout le reste (`GetCushionStatus` ne stocke pas le montant courant, `GetAnnualPlan` ne stocke pas le plan, `GetMonthlyReview` ne stocke pas la revue). Une entité `InternalTransfer` à deux jambes serait une seconde vérité à réconcilier à chaque import, **et rendrait un virement à une seule jambe inexprimable** — or c'est le cas normal quand un seul des deux comptes est synchronisé. L'exclusion devient une clause `andWhere` sur la transaction, pas une jointure.

2. **Trois champs sur `Transaction`, pas un drapeau booléen** : `transferKind` (énum), `transferSource` (énum `auto` | `manual`), `counterpart` (auto-référence `ManyToOne` nullable, `ON DELETE SET NULL`).
   *Pourquoi :* un booléen `isInternalTransfer` ne saurait pas dire *quelle sorte* de mouvement interne, et la brique 3 en a besoin de quatre. `transferSource` reprend exactement le rôle de `categorySource` : la détection ne doit jamais écraser une décision prise à la main, comme `findUncategorizedForUser` laisse tranquille ce que `CategorySource::Manual` a tranché. `counterpart` donne au propriétaire la ligne en face, qui est la seule justification lisible d'un marquage automatique.

3. **`transferKind` ouvre à deux cas dans la brique 1 (`none`, `internal`) et s'étend à trois autres dans la brique 3 (`advance`, `repayment`, `funding`).**
   *Pourquoi :* la colonne est un `length: 20` à énum, comme `status` et `retrospect` : ajouter un cas ne coûte pas de migration. L'exclusion des agrégats est alors une seule règle — `transferKind = none` — valable pour les quatre briques, au lieu d'une liste à rallonger à chaque livraison.

4. **Un virement interne est exclu de *tous* les agrégats de transactions, pas seulement du train de vie.**
   *Pourquoi :* le besoin 4 du propriétaire dit « ni une dépense, ni une recette ». Les sept méthodes de `TransactionRepository` qui agrègent (`sumConsumedBetween`, `sumMonthlyFlowsBetween`, `sumSpendingByCategoryBetween`, `sumByStatusForCategoryBetween`, `findReviewableBetween`, `findNotableDebitsBetween`, `findPlansBetween`) plus `sumPassiveIncomeByCategoryBetween` (MAG-46) prennent la même clause. Les soldes de comptes (`GetFinanceDashboard::balance`, `GetCushionStatus`) ne sont pas touchés : ils lisent `Account::$balanceCents`, et un virement interne **déplace** réellement l'argent.

5. **La fenêtre d'appariement est de 4 jours, en dur.**
   *Pourquoi :* « à quelques jours d'écart » (projet). Un virement interbancaire met un à trois jours ouvrés ; quatre couvre un week-end sans ouvrir la porte à deux mouvements d'une même semaine qui se ressemblent. Un paramètre serait un réglage de plus à expliquer pour un gain que personne ne sait évaluer avant d'avoir utilisé la fonctionnalité — si la détection rate, cela devient un `Bug` avec son cas de test.

6. **L'appariement est déterministe et au plus un contre une.** Date la plus proche, puis `bookedAt` le plus ancien, puis le plus petit ULID ; une ligne déjà appariée n'est jamais réappariée.
   *Pourquoi :* trois candidats du même montant sont fréquents (un virement mensuel récurrent). Sans ordre total, deux exécutions du rattrapage ne donnent pas le même résultat, et le chiffre du propriétaire bouge sans que rien n'ait changé. Au plus un contre une, parce qu'un virement a exactement deux jambes.

7. **Le salaire de référence a une seule source de vérité : `GetReferenceSalary`.** Déclaré = `SafetyCushion::$monthlyNetIncomeCents`, déjà là ; mesuré = les crédits des catégories marquées « salaire » sur 12 mois, divisés par 12. Le déclaré gagne quand il est renseigné.
   *Pourquoi :* la doc M7 définit déjà `monthlyNetIncomeCents` comme « le salaire net », et la cible du matelas vaut `targetMonths × monthlyNetIncomeCents`. Un second champ « salaire de référence » ailleurs créerait deux vérités, exactement ce que la décision 1 de `finance-annual-planning` refuse pour le plan annuel. Le propriétaire calcule « annuel / 12 » : c'est la même valeur mensuelle, pas un champ de plus.

8. **Un drapeau `salary` sur `Category`, et les primes sont simplement ce qui ne l'a pas.**
   *Pourquoi :* le projet dit « les primes ont leur propre catégorie et sont exclues du repère ». Exclure *par la catégorie* demande de savoir ce qui **est** le salaire, pas ce qui ne l'est pas : avec un drapeau `bonus`, « Aides & allocations » et « Remboursements » entreraient dans le repère, et un remboursement de frais n'est pas un salaire. Un seul drapeau au lieu de deux, et il suit la lettre du précédent posé par MAG-46 pour `passiveIncome` : colonne `is_salary`, accesseur `isSalary()`, propriété `$salary` — Symfony sérialise `isFoo()` en `foo`, et un champ nommé `isSalary` se lirait `salary` en REST et `isSalary` sur Mercure, le désaccord que `isCushion` paie déjà. Validation symétrique : seule une catégorie de recette peut être un salaire.

9. **Rien ne devine quelle catégorie est le salaire.** `is_salary` vaut `false` par défaut ; `StandardCategories` le pose sur la catégorie « Salaire » qu'elle crée, et ajoute « Primes » (recette, non salaire) ; le formulaire de catégorie l'expose.
   *Pourquoi :* `InstallStandardCategories` laisse intacte une catégorie que l'utilisateur a déjà — nom, couleur et obligation comprises — et c'est une garantie qu'on ne casse pas. Une migration qui marquerait « les catégories nommées Salaire » devinerait sur un nom libre, renommable et traduisible. Conséquence assumée : tant que le propriétaire n'a pas coché la case, le repère mesuré vaut 0 et `GetReferenceSalary` rend `isConfigured: false` — rien ne change, et la recette de la tâche 4 lui fait faire le geste une fois.

10. **Le matelas suit le salaire de référence.** `GetCushionStatus` lit `GetReferenceSalary` au lieu du champ brut, et se dit configuré dès que le repère est connu, déclaré ou mesuré.
    *Pourquoi :* la doc M7 dit « cible définie en mois de salaire net, recalculé si le salaire change ». Un matelas qui reste « non configuré » alors que le salaire est parfaitement mesurable demande une saisie pour une information que Maggie possède. C'est réversible en une saisie, et la recette de la tâche 5 montre le chiffre au propriétaire avant qu'il ne serve.

11. **Dépenser plus que le salaire de référence retient le vert, sans jamais faire orange ni rouge.**
    *Pourquoi :* la règle du module est déjà écrite dans `GetDailyScore::decide` et dans la doc §5.4 : le rouge et l'orange ne parlent que du budget, le matelas et la comparaison J-365 ne peuvent que retenir le vert. Un mois peut dépasser le repère pour une raison parfaitement légitime — c'est une information, pas une faute. Le dépassement arrive comme une `reason` structurée, `spending_above_reference_salary`, avec son montant.

12. **Une avance est un virement interne dont la jambe débitrice est sur un compte de type `savings` ou `investment` et la jambe créditrice sur un compte `checking`, posé pendant un retard de salaire.**
    *Pourquoi :* le propriétaire « puise dans ses économies », et ses économies ne sont pas forcément ses comptes matelas. Lire `isCushion` confondrait l'avance avec l'entame du filet de sécurité, qui déclenche un plan de recharge (M7) — deux mécanismes, deux réponses. Le type du compte dit ce qu'il est ; `isCushion` dit à quoi il sert.

13. **Le retard de salaire est une période, pas un drapeau.** Elle s'ouvre le lendemain du jour attendu (`TreasurySettings::$salaryExpectedDay`) et se ferme quand les crédits des catégories « salaire » postérieurs atteignent **90 % du salaire de référence**.
    *Pourquoi :* les trois cas du propriétaire tombent d'eux-mêmes. Deux mois d'un coup le 26 : un crédit à ~200 % ferme le retard du mois **et** celui du mois précédent, dans l'ordre des mois. Salaire partiel : en dessous de 90 %, le retard reste ouvert, et le manque à venir diminue d'autant. Prime le même jour : elle n'est pas dans une catégorie « salaire », donc elle ne ferme rien — c'est précisément à cela que sert la décision 8. 90 % et non 100 % parce qu'un virement est arrondi, amputé d'un frais ou d'un jour de carence, et qu'un retard qui ne se ferme jamais est pire qu'un retard fermé un jour trop tôt.

14. **Un seul contre-virement **exactement** égal au solde d'une avance se rapproche tout seul. Tout le reste est proposé, jamais appliqué.**
    *Pourquoi :* c'est la règle du propriétaire, mot pour mot : « le remboursement est un contre-virement du montant exact, quand c'est possible » et « le virement d'approvisionnement régulier reste à part : il n'est jamais compté comme un remboursement ». Les deux vont du courant vers l'épargne : le **sens ne les distingue pas**, seul le montant le fait. Rapprocher partiellement reviendrait à manger l'approvisionnement du mois comme un remboursement partiel — exactement ce qui est interdit. Et comme Maggie propose le montant exact, le geste que le propriétaire fait correspond par construction.

15. **`funding` est un marquage que le propriétaire pose, pas un motif inféré.** Une ligne `funding` est un virement interne neutre de plus, à cela près que le rapprochement ne la regarde jamais.
    *Pourquoi :* voir *Boundaries* — inférer une récurrence rouvrirait la détection d'abonnements, reportée par `finance-categorization-rules`. Le marquage donne au propriétaire une garantie dure (« cette ligne-là ne sera jamais prise pour un remboursement ») au lieu d'une heuristique qui a raison la plupart du temps.

16. **Les réglages de trésorerie sont une entité à part, `TreasurySettings`, une par utilisateur, créée à la lecture.**
    *Pourquoi :* `salaryExpectedDay` et `overdraftLimitCents` sont des réglages, et `SafetyCushion` est le filet de sécurité, pas le fourre-tout des réglages finance — y empiler le jour de paie ferait de la deuxième entité de config une entité à deux sujets. Créée à la lecture comme `GetCushionStatus` le fait déjà pour `SafetyCushion` : aucun onboarding à ajouter. `salaryExpectedDay` est **nullable sans défaut** : poser 25 d'office prétendrait savoir, et la vue trésorerie dirait « en retard » à un utilisateur qui n'a jamais rien déclaré.

17. **La projection de trésorerie ne lit que ce qui est déjà saisi** : solde des comptes `checking`, transactions `planned` et `committed` à venir, salaire attendu. Aucune moyenne, aucune extrapolation.
    *Pourquoi :* contrainte de neutralité (doc §7) et règle du dépôt sur les calculs vérifiables à la main — `finance-annual-planning` décision 3 refuse déjà la moyenne glissante pour que l'utilisateur puisse refaire le calcul. Un découvert annoncé qu'on ne peut pas recalculer soi-même est un découvert qu'on ne croit pas.

18. **La proaction est une tâche planifiée du jour, pas une réaction à une transaction.**
    *Pourquoi :* le déclencheur est un retard — l'absence d'un mouvement — et rien ne se produit quand rien n'arrive. C'est le même créneau que la relance de la revue mensuelle et que la notification de novembre du plan annuel, toutes deux reportées pour la même raison : il faut un cron et le canal `notification`. Celle-ci passe par `Proaction` côté agent, qui existe (`agent/app/queue/proaction_*.py`), et par une commande console côté API.

## Context

- **Visuals :** aucun. Le ticket MAG-269 n'a pas de pièce jointe ; les écrans suivent ceux du module (dashboard finance, liste de transactions, matelas).
- **References :** voir [`references.md`](./references.md).
- **Product alignment :** le projet complète M1 (comptes), M2 (catégorisation), M4 (score), M5 (revue mensuelle), M7 (matelas) et M9 (compteur d'indépendance, [MAG-46](https://linear.app/meven/issue/MAG-46)). Il n'apparaît pas encore dans `finance-roadmap.md` : la tâche 1 l'y inscrit, sous la couche Optimisation, après `finance-independence-counter`, parce que c'est le compteur qu'il corrige en premier. Rien ne contredit `mission.md` ni la doc fonctionnelle ; la vue trésorerie prolonge le « matelas de trésorerie » annoncé par M1 (« montant cible paramétrable, alerte si solde < seuil ») sans le remplacer.

## Standards Applied

Contenu dans [`standards.md`](./standards.md).

- `global/testing` — la définition de « terminé », applicable à chaque tâche sans exception non écrite.
- `api/entities` — trois champs et une auto-référence sur `Transaction`, un drapeau sur `Category`, une entité `TreasurySettings` : ULID, `MercurePublishable`, commandes Messenger, migration.
- `api/mcp-tools` — chaque lecture nouvelle a son outil MCP, chaque marquage passe par le bus.
- `api/testing` — 401, 400, happy path avec état DB, Mercure et Elasticsearch sur chaque endpoint et chaque outil.
- `global/real-time` — un marquage de virement et une avance sont des changements que l'autre onglet doit voir.
- `admin/react-admin`, `admin/testing` — badge et correction dans la liste de transactions, écrans avances et trésorerie.
- `mobile/android-app`, `mobile/testing`, `mobile/screen-tests` — mêmes vues côté Kotlin.
- `agent/architecture`, `agent/testing` — la proaction « salaire en retard » (tâche 11).
- `global/e2e-environment` — les parcours étendent [MAG-102](https://linear.app/meven/issue/MAG-102), avec leurs scénarios `agent/fixtures/fake-llm/`.
- `global/worktree-checks`, `global/agent-guard-rails` — chaque tâche tient sous 800 lignes hors tests, et aucune n'ouvre de migration destructive.

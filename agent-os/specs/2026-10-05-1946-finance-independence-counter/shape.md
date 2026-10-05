# Compteur d'indépendance (M9) — Shaping Notes

Ticket : **MAG-46** — spec 9 de `agent-os/product/finance-roadmap.md`
(`finance-independence-counter`). Shapé en mode autonome : les réponses viennent
du ticket, de `finance-functional-spec.md` (§ Module 9, § 5bis.1) et du code
livré par `finance-debts` et `finance-dashboard`.

## Scope

Ce que la couche Free doit donner : **un pourcentage de couverture**, affiché en
permanence, qui répond à « mes rentes couvrent quelle part de mon train de
vie ? ».

Dans le périmètre :

- **train de vie mesuré en continu** — la moyenne mensuelle réellement
  consommée, celle que `finance-debts` calcule déjà, extraite en service
  partagé pour qu'il n'y ait qu'une seule règle pour ce nombre ;
- **rentes mesurées** — les recettes récurrentes des catégories que
  l'utilisateur déclare comme rentes, sur la même fenêtre ;
- **taux de couverture** = rentes / train de vie, sa décomposition par
  catégorie de rente, l'écart restant, et les paliers 25 / 50 / 75 / 100 %
  atteints ;
- la tranche verticale : API, MCP, admin, mobile, tests, parcours e2e.

Hors périmètre, écrit tel quel dans le ticket et la roadmap :

- **date N et courbe de progression** — Premium v1.1. Rien n'est posé pour
  elles ici : pas de champ vide, pas de paywall (le middleware Premium
  n'existe pas encore, spec `finance-freemium-paywall`).
- **comparaison de scénarios**, **remboursement anticipé**, **projection de
  rente depuis le portefeuille** — M10/M11/M12.

## Decisions

### D1 — D'où viennent les rentes : une case sur la catégorie, pas une entité

- **Dilemme** : en Free, aucune donnée ne dit ce qu'est une rente. Le module
  investissements (M12, Premium v1.2) les projettera depuis le portefeuille,
  mais il n'existe pas.
- **Options** : (a) une entité `PassiveIncome` saisie à la main (nom, type,
  montant net mensuel) ; (b) un drapeau `isPassiveIncome` sur `Category`, et le
  montant **mesuré** sur les transactions de ces catégories.
- **Choix** : (b).
- **Pourquoi** : la spec fonctionnelle M9 pose la règle pour le train de vie —
  « calculé dynamiquement depuis les transactions (pas saisi manuellement) » —
  et un compteur dont un côté est mesuré et l'autre déclaré comparerait deux
  choses de nature différente. La « décomposition des rentes actives :
  immobilier, placements, dividendes, SaaS… » que demande M9 est exactement ce
  qu'une catégorie porte déjà. Une entité de plus serait aussi une deuxième
  source pour un loyer qui est déjà une transaction : double compte garanti.

### D2 — Une rente est une recette

`isPassiveIncome` n'est acceptée que sur une catégorie dont l'obligation est
`income` (`ObligationFlag::Income`, livré par `finance-mvp`). Une dépense
déclarée rente n'a pas de sens, et le compteur n'additionne que des crédits :
la validation refuse la combinaison au lieu de produire un pourcentage dont
personne ne sait d'où il vient.

### D3 — Même fenêtre pour les deux côtés du rapport

Train de vie et rentes sont mesurés sur **les trois mois complets précédents**,
la fenêtre de `GetDebtTimeline` (`LIFESTYLE_SAMPLE_MONTHS = 3`). Le ticket dit
« à partir du train de vie mesuré (`finance-debts`) » : la constante déménage
dans `MeasureMonthlyLifestyle` et sert aux deux requêtes, donc le numérateur et
le dénominateur parlent toujours de la même période.

Conséquence assumée : un mois sans historique donne un train de vie bas, donc
un taux haut. C'est déjà le comportement de la capacité d'épargne nette ;
`isMeasurable` dit quand le nombre n'a rien derrière lui.

### D4 — Le titre du compteur est rentes / train de vie, les mensualités à côté

La spec fonctionnelle définit le taux comme « % rentes nettes / train de vie
mensuel » : c'est `coveragePercent`, et le train de vie exclut les mensualités
de prêt, comme dans `finance-debts`. Mais tant qu'un prêt court, le besoin réel
est plus haut, donc la réponse expose aussi `loanPaymentsCents` et
`coverageWithDebtPercent` — le même rapport, mensualités comprises. Deux
nombres, une seule règle pour chacun ; rien n'est deviné à l'affichage.

### D5 — Pas de plafond à 100 %

Contrairement au matelas (`GetCushionStatus` plafonne à 100), dépasser 100 %
veut dire quelque chose ici : les rentes couvrent plus que le train de vie.
`isReached` vaut `coveragePercent >= 100`.

### D6 — Les paliers sans leurs dates

M9 demande « objectifs intermédiaires : 25 %, 50 %, 75 %, 100 % avec dates
projetées ». Les dates sont la date N, donc Premium. Les paliers sont rendus
sans date : atteint ou non, et la rente mensuelle qu'il faudrait pour
l'atteindre — un fait, pas une projection.

### D7 — Rentes nettes sans moteur fiscal

M9 parle de rentes **nettes**. Il n'y a pas de moteur fiscal en Free (M5.11,
Premium). Le montant mesuré est celui qui est réellement tombé sur le compte,
donc net de ce qui a déjà été prélevé à la source, et rien n'est estimé. Les
transactions `isExceptional` sont exclues : une plus-value de cession n'est pas
une rente.

### D8 — Un endpoint dédié *et* une clé dans le dashboard

`GET /api/finance/independence` existe pour l'agent, le mobile et un éventuel
écran dédié, et le compteur est aussi une clé de `GET /api/finance/dashboard`
(composée du même use case, comme `savingCapacity`) : le ticket demande le
pourcentage « affiché dans le dashboard », et le dashboard promet une seule
lecture.

### D9 — Déclarer une rente depuis le téléphone est reporté

- **Dilemme** : la tranche écrite d'abord ajoutait aussi la case « Rente » au
  dialogue de création de catégorie du mobile. Avec elle, le diff fait 823
  lignes hors tests, pour une limite de 800 (`agent-guard`).
- **Options** : (a) tout livrer et laisser le guard passer la PR à l'owner ;
  (b) retirer la déclaration mobile et en faire un ticket de suite.
- **Choix** : (b).
- **Pourquoi** : `CLAUDE.md` tranche — au-delà de 800 lignes hors tests, « split
  the ticket instead ». Le compteur, lui, est bien lu partout : la carte mobile
  l'affiche, et déclarer une rente passe par l'admin ou par Maggie (MCP), qui
  sont tous deux livrés ici. Le libellé de la carte mobile renvoie vers ces
  deux chemins, pour ne pas désigner une case que l'écran ne porte pas.

### D10 — Le pourcentage est tronqué, pas arrondi

Un arrondi faisait lire « 100 % » à 99,6 % de couverture, juste au-dessus de
« il manque 0,04 € » : deux affirmations contradictoires sur la même carte. La
couverture est donc tronquée, et 100 % veut dire couvert, rien d'autre —
`isReached` et le palier 100 % tombent d'accord par construction. Les parts par
catégorie restent arrondies : elles n'ont pas de seuil à défendre.

### D11 — Ce que chaque surface porte

La réponse de l'API sert trois lecteurs, et aucun n'a besoin de tout :

- `milestones` (les quatre paliers détaillés) n'est lu que par l'agent, en MCP.
  Les clients lisent `nextMilestonePercent` / `nextMilestoneGapCents`, qui sont
  la même information aplatie — un champ déclaré dans un DTO et rendu nulle part
  est du poids mort, et côté mobile c'est exactement ce que `DtoContractTest`
  surveille.
- `coverageWithDebtPercent` et `monthlyNeedCents` restent à l'admin et à
  l'agent : la nuance « tant qu'un prêt court, le mois coûte plus » mérite le
  grand écran et une phrase de Maggie, pas une ligne de plus sur une carte de
  téléphone.

## Context

- **Visuals** : aucun. Le ticket n'a aucune pièce jointe. La carte reprend la
  mise en page des cartes existantes du dashboard admin et mobile.
- **References** : voir `references.md`.
- **Product alignment** : roadmap finance ligne 9, dépendances « Dettes et
  Dashboard (livrés) » satisfaites. `finance-functional-spec.md` § Module 9 et
  § 5bis.1 (« Compteur indépendance — % actuel uniquement » en Free). Mission :
  « informer sans recommander » — le compteur énonce un rapport et ce qui
  manque, il ne conseille rien.

## Standards Applied

- `global/testing` — définition de « terminé », couverture due par unité
  touchée. Toujours applicable.
- `api/entities` — `Category` gagne un champ : ULID, Mercure, commandes
  Messenger, document Elasticsearch.
- `api/mcp-tools` — un outil de lecture par use case
  (`get_independence_counter`), `McpUserContext::requireUser()`, filtrage par
  user.
- `api/testing` — fixtures Alice, tests d'outil MCP, publication Mercure.
- `global/real-time` — la carte admin se rafraîchit sur les topics Mercure déjà
  écoutés par le dashboard.
- `admin/react-admin`, `admin/testing` — carte et formulaire React Admin,
  Vitest co-localisé.
- `mobile/android-app`, `mobile/testing`, `mobile/screen-tests` — DTO
  `@Serializable` avec valeurs par défaut, carte Compose, test d'écran sur la
  JVM.
- `global/e2e-environment` — parcours Playwright dans la stack e2e, fixtures
  ancrées.
- `global/worktree-checks` — `task fix:all` et `task wt:*` depuis ce worktree.

# Maggie Finance — Roadmap d'implémentation

> Décomposition du module **Maggie Finance** en specs séquencées.
> Source de vérité fonctionnelle : [`finance-functional-spec.md`](./finance-functional-spec.md) (doc v3.0, avril 2026).
> Chaque ligne « Spec » devient un dossier `agent-os/specs/YYYY-MM-DD-<slug>/` shapé au moment de l'itération (just-in-time), pas tous d'avance.

## Principe de séquençage

Le socle **data model** (M1–M3) est la fondation dont dépendent toutes les couches calculées (score, matelas, compteur, simulation). On le construit en **tranches verticales full-stack** (API → admin React → mobile Kotlin), une entité de bout en bout à la fois. Les couches « intelligence » (score, matelas, compteur) et « stratégie » (projets, simulation, investissements) viennent ensuite car elles ne font que dériver des données du socle.

Bridge (sync bancaire) est une **dépendance externe bloquante** (compte partenaire à obtenir). On démarre le data model en **saisie manuelle / import**, et on branche Bridge quand l'accès API est disponible — sans bloquer le reste.

---

## Finance v1 — MVP (Couches Connaissance + Optimisation)

Objectif : *« Je comprends où va mon argent »* (le pilier Free de la doc §1).

### Socle — data model (tranches verticales full-stack)

| Ordre | Spec | Modules doc | Contenu | Dépend de |
|-------|------|-------------|---------|-----------|
| 1 | `finance-mvp` ✅ **livré (16 sept. 2026)** | M1, M2, M3 | Bundle `api/modules/finance/` + entités **Account**, **Category**, **Transaction**, **Envelope** ; CRUD API Platform + MCP tools + Mercure + ES + tests ; admin React + mobile Kotlin. Saisie manuelle. Bonus livré avec Envelope : `GET /api/finance/budget-status` (consommé/restant par enveloppe) + jauges admin & mobile. | — |
| 2 | `finance-categorization-rules` ✅ **livré (17 sept. 2026)** | M2 | **CategorizationRule** : catégorisation auto par règles (libellé + fourchette de montant absolue + sens), application à la création et en rattrapage sur l'historique, apprentissage explicite (`learn`), provenance de la catégorie sur Transaction (`categorySource`). **Détection d'abonnements récurrents : reportée** à une spec dédiée. | Socle |
| 3 | `finance-annual-envelopes` ✅ **livré (17 sept. 2026)** | M3 | Statuts transaction pesant sur l'enveloppe (dépensée + engagée = consommé, planifiée = réservé, à arbitrer = reporté sans être déduit), consommé/restant/disponible par enveloppe, reconduction d'une période sur une autre (montant identique ou ajusté au consommé réel). **Session de planification annuelle : reportée** à `finance-annual-planning`. | Socle |
| 3bis | `finance-annual-planning` | M3 | **Session de planification annuelle** (doc §5.3, §6.4) : plan annuel lu d'une année sur l'autre — consommé et grosses dépenses de l'année écoulée, décidé et enveloppe de l'année cible, montant suggéré — puis appliqué en transactions planifiées et enveloppes annuelles. Découpée par tranches qui tiennent sous le garde-fou : **lecture** (`GET /api/finance/annual-plan` + outil MCP `plan_annual_budget`, MAG-48), puis application en un geste, puis écran admin, puis écran mobile. **Impact sur N et déclenchement automatique en novembre : hors périmètre** (M9 inexistant, notification à part). | Enveloppes |

### Couche Optimisation — calculs dérivés

| Ordre | Spec | Modules doc | Contenu | Dépend de |
|-------|------|-------------|---------|-----------|
| 4 | `finance-cushion` ✅ **livré (17 sept. 2026)** | M7 | **Matelas de sécurité** : cible en mois de revenu net, montant courant dérivé des comptes `isCushion`, 3 états (constitution / complet / recharge), plan de recharge plafonné (le plafond allonge la durée), drapeau `blocksGreenScore` pour M4. | Socle |
| 5 | `finance-daily-score` ✅ **livré (17 sept. 2026)** | M4 | **Score quotidien** vert/neutre/orange/rouge + raisons structurées ; obligatoire→rouge / non-obligatoire→orange ; matelas et comparaison J-365 ne bloquent que le vert. **Notification contextuelle de dépense : reportée** (seuil + canal). **Impact sur N / projets : hors périmètre** (M9/M10 inexistants). | Socle, Matelas |
| 6 | `finance-debts` ✅ **livré (17 sept. 2026)** | M8 | **Dettes & charges fixes** : `Loan` (capital restant, mensualité, taux en **points de base**, priorité — **pas de date de fin saisie**, elle se déduit), amortissement mois par mois, timeline de libération 60 mois, capacité d'épargne nette (revenu − mensualités − train de vie **mesuré** sur 3 mois). **Remboursement anticipé et réaffectation : hors périmètre** (dépendent de N et des projets). | Socle |
| 7 | `finance-monthly-review` ✅ **livré (17 sept. 2026)** | M5 | **Revue mensuelle** : verdict `retrospect` (unrated/keep/avoidable) sur Transaction, file des dépenses **non-obligatoires** à qualifier (plus grosses d'abord), score d'optimisation en part de **montant**, comparaisons M-1/M-3/N-1. **Notification du 1er du mois : reportée** (cron + canal notification). | Score, Enveloppes |
| 8 | `finance-dashboard` ✅ **livré (17 sept. 2026)** | M6 | **Dashboard** : `GET /api/finance/dashboard` en **une lecture** (score + soldes + 12 mois recettes/dépenses + jauges + top 5 postes vs M-1 + capacité d'épargne), composé des use cases existants. Graphe 12 mois (palette catégorielle validée CVD). **Compteur d'indépendance : hors périmètre** (M9). | Toutes les précédentes |
| 9 | `finance-independence-counter` | M9 | **Compteur d'indépendance** (% couverture seul en Free) : train de vie dynamique, rentes vs train de vie. Date N + courbe = Premium (v1.1). | Dettes, Dashboard |
| 10 | `salaire-irregulier-tresorerie` | M1, M2, M4, M5, M7 | **Salaire irrégulier et trésorerie** (projet Linear, besoins validés le 6 oct. 2026) : **virements internes neutres** (appariement de deux mouvements opposés entre comptes du propriétaire, exclus des dépenses, des recettes et du train de vie — ils mesuraient 11 129 €/mois pour 4 950 € de revenus), **salaire de référence** (annuel / 12, primes exclues via leur catégorie, déclaré ou mesuré sur 12 mois, lu par la revue mensuelle, le score et le matelas), **avances d'épargne** (solde par source, contre-virements exacts proposés, rapprochement automatique, approvisionnement récurrent jamais pris pour un remboursement) et **vue trésorerie** (salaire attendu, retard en jours, solde projeté, découvert prévu, avance conseillée, proaction). **Aucune action bancaire automatique, pas de prévision au-delà du mois suivant.** Cadrée par [MAG-269](https://linear.app/meven/issue/MAG-269), découpée en onze tickets. | Compteur d'indépendance |

### Intégration bancaire (parallèle, dès accès Bridge)

| Ordre | Spec | Modules doc | Contenu | Dépend de |
|-------|------|-------------|---------|-----------|
| B | `finance-bridge-sync` | M1 | Connexion **Bridge by Bankin'** : OAuth/consent, webhook sync quotidien, mapping comptes/transactions, multi-devises + historique taux, détection rejets de prélèvement. | Socle (Account/Transaction) |

---

## Finance v1.1 — Stratégie : Projets & Simulation (Premium)

Objectif : *« Je pilote ma trajectoire »*.

| Spec | Modules doc | Contenu | Dépend de |
|------|-------------|---------|-----------|
| `finance-projects` | M10 | **Projets & financement** : file capacity-first, score bien-être, options comptant/emprunt/partiel, 3 métriques (coût réel, impact N, délai), timeline glissante. | v1 complet |
| `finance-loan-details` | M5.14 | **Saisie détaillée des emprunts** : 7 types de prêts, 23 paramètres, calcul IRA (4 modes), simulation remboursement anticipé. | Dettes |
| `finance-simulation-engine` | M11, M5.16 | **Moteur de simulation asynchrone** : workers RabbitMQ scale-to-zero, format `simulation_input.json` → événements de frise, 3 scénarios (Rapide/Équilibré/Qualité de vie), horizon 20 ans. Service séparé. | Projets, reference-data |
| `finance-simulation-timeline` | M11 | **Frise temporelle interactive** (admin + mobile) : rendu des événements de sortie, comparaison de scénarios, slider de pondération. | Moteur simulation |

## Finance v1.2 — Investissements & Retraite (Premium)

| Spec | Modules doc | Contenu | Dépend de |
|------|-------------|---------|-----------|
| `finance-investments` | M12 | **Portefeuille multi-classes** (5 classes), allocation cible vs réelle, rééquilibrage, projection rente, actifs illiquides, limite MIF II. | v1.1 |
| `finance-tax-engine` | M5.11, M5.13 | **Fiscalité** : rendement brut vs net par enveloppe (CTO/PEA/AV/PER/LMNP…), fiscalité revenus locatifs. | Investissements, reference-data |
| `finance-retirement-simulator` | M12 | **Simulateur retraite** : projection rentes 3 scénarios (pessimiste/médian/optimiste), gap train de vie. | Investissements, Simulation |

---

## Services externes (hors bundle Symfony)

| Spec | Section doc | Contenu | Quand |
|------|-------------|---------|-------|
| `finance-reference-data-service` | M5.12 | **Microservice données de référence** : API REST versionnée 3 couches (fiscalité/marché/rendements), webhook de propagation, recalcul batch des simulations impactées. | Avant v1.2 (utile dès simulation) |
| `finance-ocr-service` | M5.15 | **Microservice OCR** : extraction params contractuels d'offres de prêt, niveaux de confiance par champ, apprentissage parser. | v1.1 (avec loan-details) |

## Transverse

| Spec | Section doc | Contenu | Quand |
|------|-------------|---------|-------|
| `finance-freemium-paywall` | M5bis | **Freemium** : `user.plan` (free/premium/unlimited/grandfathered), middleware auth premium, HTTP 402 + payload paywall, job d'expiration quotidien, admin d'attribution. | Avant première fonctionnalité Premium (fin v1) |
| `finance-cloud-scaling` | M5bis.7 | **Infra scaling dynamique** : workers scale-to-zero pilotés par profondeur de queue RabbitMQ. | Avec moteur de simulation |

---

## Contraintes non fonctionnelles (rappel doc §7)

- **Sécurité** : tokens Bridge chiffrés, aucun credential bancaire stocké, JWT.
- **Multi-user ready** : isolation stricte par user dès le départ (déjà le cas dans le repo via `owner`/JWT).
- **Neutralité** : informer sans recommander — aucun jugement, uniquement faits et projections.
- **Auditabilité / Réversibilité** : toute correction tracée, toute action manuelle annulable.
- **Performance** : dashboard < 2s, score < 500ms, simulations asynchrones.
- **Réglementaire** : mention MIF II visible sur le module investissements (aucun conseil réglementé).

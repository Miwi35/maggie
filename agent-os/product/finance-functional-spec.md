# MAGGIE FINANCE
## Documentation Fonctionnelle — v3.0 — Avril 2026

> *Maggie Finance ne te demande pas de sacrifier aujourd'hui pour demain. Il te montre exactement le coût de chaque choix pour que tu décides toi-même où est ton équilibre entre qualité de vie et liberté financière.*

---

## Sommaire

- [0. L'écosystème Maggie](#0-lecosystème-maggie)
  - [0.1 Modules existants et à venir](#01-modules-existants-et-à-venir)
  - [0.2 Principes de l'écosystème](#02-principes-de-lecosystème)
  - [0.3 Ponts inter-modules — phase 2](#03-ponts-inter-modules--phase-2)
  - [0.4 Stack technique de la plateforme](#04-stack-technique-de-la-plateforme)
- [1. Contexte & objectifs](#1-contexte--objectifs)
  - [1.1 Problèmes à résoudre](#11-problèmes-à-résoudre)
  - [1.2 Objectifs produit](#12-objectifs-produit)
- [2. Utilisateurs cibles](#2-utilisateurs-cibles)
  - [2.1 Ce que l'utilisateur cherche à accomplir](#21-ce-que-lutilisateur-cherche-à-accomplir)
- [3. Architecture fonctionnelle](#3-architecture-fonctionnelle)
  - [3.1 Vue d'ensemble des modules](#31-vue-densemble-des-modules)
- [4. Modules fonctionnels détaillés](#4-modules-fonctionnels-détaillés)
- [5. Règles métier](#5-règles-métier)
  - [5.1 Matelas de sécurité](#51-matelas-de-sécurité--règles-de-gestion)
  - [5.2 Capacité d'investissement mensuelle nette](#52-capacité-dinvestissement-mensuelle-nette)
  - [5.3 Enveloppes annuelles](#53-enveloppes-annuelles)
  - [5.4 Score de performance quotidien](#54-score-de-performance-quotidien)
  - [5.5 Options de financement des projets](#55-options-de-financement-des-projets)
  - [5.6 Boucle vertueuse](#56-boucle-vertueuse)
  - [5.7 Scénarios automatiques](#57-scénarios-automatiques)
  - [5.9 Investissements — règles de gestion](#59-investissements--règles-de-gestion-du-portefeuille)
  - [5.11 Fiscalité des investissements](#511-fiscalité-des-investissements)
  - [5.12 Microservice de données de référence](#512-microservice-de-données-de-référence)
  - [5.13 Fiscalité revenus locatifs](#513-fiscalité-revenus-locatifs-détail)
  - [5.14 Saisie détaillée des emprunts](#514-saisie-détaillée-des-emprunts)
  - [5.15 Module OCR](#515-module-ocr--import-de-documents-de-prêt)
  - [5.16 Moteur de simulation — architecture et formats](#516-moteur-de-simulation--architecture-et-formats)
- [5bis. Modèle économique — Freemium](#5bis-modèle-économique--freemium)
- [6. Parcours utilisateur principaux](#6-parcours-utilisateur-principaux)
- [7. Contraintes non fonctionnelles](#7-contraintes-non-fonctionnelles)
- [8. Glossaire](#8-glossaire)

---

## 0. L'écosystème Maggie

Maggie Finance est un module de la plateforme Maggie — une super-app dont l'ambition est de centraliser tous les aspects du quotidien de l'utilisateur dans une interface unifiée. Plutôt que de multiplier les applications, Maggie propose un écosystème de modules activables indépendamment, partageant une authentification, une infrastructure et une identité commune.

### 0.1 Modules existants et à venir

| Module | Statut | Description | Souscription |
|--------|--------|-------------|--------------|
| Maggie Calendrier | Opérationnel | Gestion d'agenda personnel | Inclus plateforme |
| Maggie Tâches | Opérationnel | Gestion de tâches et projets personnels | Inclus plateforme |
| Maggie Recettes | Opérationnel | Base de recettes personnalisées | Inclus plateforme |
| Maggie Courses | Opérationnel | Liste de courses intelligente | Inclus plateforme |
| **Maggie Finance** | **Ce document** | **Gestion financière, budget, investissement** | **Module — voir tarification** |
| Maggie Diététique | Planifié | Suivi nutritionnel et objectifs alimentaires | Module — futur |
| Maggie Fitness | Planifié | Suivi sportif, programmes, progression | Module — futur |

### 0.2 Principes de l'écosystème

- Un seul compte, une seule authentification pour tous les modules
- Chaque module est activable et désactivable indépendamment
- L'interface s'adapte aux modules actifs — pas de fonctionnalités inutiles qui encombrent
- Une souscription par module : l'utilisateur paie uniquement ce qu'il utilise
- Infrastructure partagée : auth, DB utilisateur, notifications, stockage
- Ponts inter-modules prévus en phase 2 — indépendance technique en phase 1

### 0.3 Ponts inter-modules — phase 2

Les synergies entre modules sont identifiées mais non implémentées en version 1.

| Pont inter-modules | Valeur apportée |
|--------------------|-----------------|
| Finance ↔ Courses | Les achats alimentaires alimentent automatiquement les catégories budget |
| Finance ↔ Calendrier | Un événement (festival, voyage) crée automatiquement un projet Finance |
| Finance ↔ Diététique | Abonnements, compléments alimentaires intégrés dans les dépenses santé |
| Finance ↔ Fitness | Abonnement salle, équipement sportif — catégories budget automatiques |
| Courses ↔ Diététique | La liste de courses générée depuis le plan nutritionnel |
| Recettes ↔ Diététique | Les recettes annotées avec les valeurs nutritionnelles |

### 0.4 Stack technique de la plateforme

| Couche | Technologie |
|--------|-------------|
| Backend | Symfony (PHP 8.3) — API Platform 3 — commun à tous les modules |
| Frontend web | React + Vite + TailwindCSS — interface principale |
| Application mobile | Kotlin — Android first. iOS dans un second temps via PWA ou portage |
| Base de données | PostgreSQL — schéma par module, auth partagée |
| Queue | RabbitMQ / Symfony Messenger — workers asynchrones |
| Auth | Shared auth service — JWT — un compte pour tous les modules |
| Notifications | Service de push unifié — Android (FCM), web (Web Push) |
| Infrastructure | Cloud avec scaling dynamique des workers — voir section 5bis.7 |

---

## 1. Contexte & objectifs

Maggie Finance est un module de gestion financière personnelle organisé autour de deux piliers complémentaires.

### Pilier 1 — Suivre & gérer *(Free)*

> Connaître précisément son train de vie, identifier les dépenses essentielles vs arbitrables, suivre ses enveloppes annuelles, piloter sa trésorerie, constituer un matelas de sécurité, recevoir un signal quotidien sur sa trajectoire.

### Pilier 2 — Planifier & investir *(Premium)*

> Simuler des trajectoires d'investissement, comparer les options de financement des projets, optimiser la fiscalité de son portefeuille, construire des rentes passives progressivement, arbitrer entre qualité de vie et stratégie long terme, accélérer sa capacité d'investissement au fil du temps.

**Résultante :** *L'indépendance financière — les rentes couvrent le train de vie.*

### 1.1 Problèmes à résoudre

| Problème | Impact |
|----------|--------|
| Vision fragmentée (multi-comptes, multi-devises) | Impossible d'avoir une vue consolidée fiable |
| Approche budget classique (économiser pour X) | Crée de la frustration, rarement tenue sur la durée |
| Absence de moteur de simulation | Décisions financières prises sans comparaison objective |
| Qualité de vie vs investissement : faux dilemme | L'utilisateur se prive inutilement ou investit mal |
| Dépenses à temporalité annuelle mal gérées | Budget mensuel irrelevant pour les dépenses saisonnières |
| Manque de feedback en temps réel | Dépassements constatés trop tard, en fin de mois |
| Décisions de financement non modélisées | Emprunt vs épargne comptant : choix intuitif sans calcul |
| Retraite non anticipée | Découverte tardive du gap rentes projetées / train de vie |

### 1.2 Objectifs produit

- Connaître son train de vie réel avec précision (pas estimé)
- Calculer sa capacité d'investissement mensuelle nette réelle
- Donner un signal quotidien simple sur sa trajectoire : vert / neutre / orange / rouge
- Informer en temps réel de l'impact de chaque dépense sur N — sans juger
- Gérer les dépenses à temporalité annuelle avec des enveloppes adaptées
- Simuler des scénarios de trajectoire et présenter les 3 meilleurs automatiquement
- Comparer les options de financement de chaque projet (comptant / emprunt)
- Suivre le compteur d'indépendance et accélérer N

---

## 2. Utilisateurs cibles

Maggie Finance s'adresse à toute personne souhaitant mieux gérer ses finances au quotidien et, pour les plus ambitieux, construire une trajectoire vers l'indépendance financière. Positionnement aligné avec le mouvement FIRE (Financial Independence, Retire Early) adapté au contexte franco-européen.

### 2.1 Ce que l'utilisateur cherche à accomplir

- Savoir précisément où part son argent chaque mois
- Comprendre quelle part de ses dépenses est vraiment nécessaire
- Avoir un signal quotidien simple sur sa trajectoire financière
- Faire chaque dépense plaisir en connaissance de cause, pas dans le flou
- Avoir une trajectoire claire vers l'indépendance avec une date cible
- Simuler les conséquences de chaque décision et choisir en toute lucidité
- Équilibrer qualité de vie aujourd'hui et liberté financière demain, sans culpabilité

---

## 3. Architecture fonctionnelle

| Couche | Nom | Objectif |
|--------|-----|----------|
| 1 — Connaissance | Sync & Catégorisation | Connaître son train de vie réel avec précision |
| 2 — Optimisation | Score, Enveloppes & Revue | Signal quotidien, feedback temps réel, réduction des dépenses non-essentielles |
| 3 — Stratégie | Simulation & Indépendance | Modéliser les trajectoires, comparer les scénarios, atteindre et accélérer N |

### 3.1 Vue d'ensemble des modules

| # | Module | Objectif | Couche | Priorité |
|---|--------|----------|--------|----------|
| 1 | Sync & Comptes | Connexion bancaire, agrégation, taux de change | Connaissance | MVP |
| 2 | Catégorisation | Classification auto + manuelle des transactions | Connaissance | MVP |
| 3 | Enveloppes & budget | Budget mensuel et enveloppes annuelles par catégorie | Optimisation | MVP |
| 4 | Score de performance | Signal quotidien vert/neutre/orange/rouge | Optimisation | MVP |
| 5 | Revue mensuelle | Qualification des dépenses, score d'optimisation | Optimisation | MVP |
| 6 | Dashboard | Recettes vs dépenses, capacité d'investissement, compteur | Optimisation | MVP |
| 7 | Matelas de sécurité | Constitution prioritaire, recharge automatique | Optimisation | MVP |
| 8 | Dettes & charges | Suivi prêts, succession, projection libération de capital | Connaissance | MVP |
| 9 | Compteur indépendance | Rentes vs train de vie, date N, progression | Stratégie | MVP |
| 10 | Projets & financement | File capacity-first, options comptant/emprunt, timeline | Stratégie | v1.1 |
| 11 | Moteur simulation | 3 scénarios auto, combinatoires, comparaison | Stratégie | v1.1 |
| 12 | Investissements | Patrimoine, rentes, leviers, simulateur retraite | Stratégie | v1.2 |

---

## 4. Modules fonctionnels détaillés

### Module 1 — Sync & Comptes
*Connexion et agrégation de tous les comptes bancaires*

- Connexion via Bridge by Bankin' (agrégateur DSP2 agréé ACPR)
- Comptes supportés : Crédit Agricole, Revolut, N26 (extensible)
- Synchronisation automatique quotidienne via webhook Bridge
- Conversion automatique multi-devises au taux du jour
- Historique du taux de change sur 12 mois, alerte si hors fourchette définie
- Solde unifié multi-comptes en devise d'affichage
- Détection des rejets de prélèvements avec notification immédiate
- Gestion du matelas de trésorerie : montant cible paramétrable, alerte si solde < seuil

### Module 2 — Catégorisation
*Classification intelligente des transactions*

- Catégorisation automatique par règles (matching libellé, montant, récurrence)
- Arbre de catégories sur 2 niveaux : Catégorie > Sous-catégorie
- Flag : obligatoire / non-obligatoire / épargne / investissement
- Correction manuelle avec mémorisation (apprentissage par règle)
- Détection automatique des abonnements récurrents
- Tag 'exceptionnel' pour isoler les dépenses non récurrentes
- Statut de transaction : Dépensée / Engagée (billet acheté, event futur) / Planifiée / À arbitrer
- Toutes les corrections sont tracées (qui, quand, quoi)

### Module 3 — Enveloppes & budget
*Gestion des budgets adaptés à chaque temporalité de dépense*

- Deux modes de budget par catégorie : **Mensuel** ou **Enveloppe annuelle**
- Mode mensuel : budget fixe chaque mois, alerte si dépassé
- Mode enveloppe annuelle : budget global sur 12 mois glissants, pilotage annuel
- Un billet acheté en novembre pour un event en juin est déduit de l'enveloppe à l'achat
- Session de planification annuelle : pré-saisie des events/projets de l'année
- Reconduction automatique avec ajustement proposé sur la base de l'année écoulée
- Exemples de catégories en mode enveloppe annuelle : concerts & festivals, voyages, matériel, vêtements, cadeaux, santé non remboursée

**Statuts d'une transaction dans une enveloppe :**

| Statut | Définition | Impact enveloppe |
|--------|-----------|------------------|
| Dépensée | Argent sorti, event passé | Déduit au débit |
| Engagée | Argent sorti, event futur | Déduit à l'achat |
| Planifiée | Décision prise, pas encore payée | Réservé (en attente) |
| À arbitrer | Événement envisagé, décision ouverte | Non compté |

### Module 4 — Score de performance
*Signal quotidien sur la trajectoire financière — sans jugement, avec impact*

| Score | Condition | Impact sur N | Action |
|-------|-----------|--------------|--------|
| 🟢 VERT | Mieux que J-365 ou en avance sur la trajectoire | N potentiellement avancé | Rien — tu es dans le vert |
| 🔵 NEUTRE | Dans les clous du scénario équilibré | N stable | Continue ainsi |
| 🟠 ORANGE | Budget variable dépassé, projets pourraient glisser | N +1 à 3 mois potentiel | Visualise les projets impactés |
| 🔴 ROUGE | Dépassement fort, projets impactés significativement | N +3 mois et plus | Alternatives proposées immédiatement |

**Notification contextuelle de dépense** — déclenchée si transaction > seuil paramétrable dans une catégorie non-obligatoire :
- Montant et catégorie identifiés
- Impact en mois sur N
- Projets impactés et de combien
- Alternatives proposées (emprunt, reporter, prioriser autrement)
- L'utilisateur maintient ou optimise — aucune friction forcée
- **Principe fondamental : informer sans juger**

### Module 5 — Revue mensuelle
*Évaluation guidée une fois par mois*

- Notification le 1er du mois avec récapitulatif du mois écoulé
- Liste de toutes les dépenses non-obligatoires : toggle 'À conserver' / 'J'aurais pu m'en passer'
- Score mensuel d'optimisation avec évolution mois par mois
- Comparaison avec la moyenne des 3 derniers mois et avec J-365
- Affichage de l'impact sur la capacité d'investissement mensuelle nette
- Relance automatique du moteur de scénarios après chaque revue

### Module 6 — Dashboard
*Vue centralisée de la situation financière*

- Score du jour en évidence (vert/neutre/orange/rouge) avec détail par catégorie
- Compteur d'indépendance : % couverture rentes/train de vie + date N
- Solde unifié temps réel tous comptes
- Graphe mensuel recettes vs dépenses sur 12 mois glissants avec ligne de tendance
- Indicateur de capacité d'investissement mensuelle nette
- Jauge des enveloppes annuelles actives : consommé + engagé + planifié vs budget
- Top 5 postes de dépenses du mois vs mois précédent

**Navigation mobile (MAG-196, règle MAG-90).** Le menu principal n'a qu'une entrée « Finance » ; elle ouvre le dashboard, qui mène au reste. Les accès sont classés par fréquence d'usage :

| Fréquence | Accès (dans l'ordre) |
|-----------|----------------------|
| Chaque jour — boutons sous le score, visibles sans défiler | Budgets et enveloppes · Comptes et transactions |
| Chaque mois — section « Chaque mois » | Revue mensuelle · Matelas |
| Rarement — section « Réglages finance » | Prêts · Catégories · Règles de catégorisation |

Le score, le solde, la capacité d'épargne, les postes et les enveloppes du mois sont sur le dashboard même. La flèche retour d'un écran finance ramène au dashboard finance (ou à la liste dont il dépend : transactions → comptes) ; ouvert par un lien ou une notification, l'écran revient au dashboard finance. Les liens `maggie://finance/…` sont inchangés.

### Module 7 — Matelas de sécurité
*Constitution et maintien du filet de sécurité — priorité absolue avant tout investissement*

- **PRINCIPE : le matelas est la priorité n°1. Aucun investissement ni projet ne démarre tant qu'il n'est pas constitué**
- Cible définie en mois de salaire net (ex : 3 mois). Recalculé si le salaire change
- 3 états : Constitution en cours / Complet / En recharge
- Tant qu'incomplet, le score global ne peut pas être vert (badge 'constitution en cours')
- Entame détectée automatiquement → plan de recharge déclenché
- Plafond de recharge mensuel paramétrable (ex : 150 €) pour ne pas stresser le budget
- Si mensualité théorique > plafond : durée ajustée automatiquement
- Pendant la recharge : capacité d'investissement réduite, projets décalés en conséquence

### Module 8 — Dettes & charges fixes
*Suivi structuré des engagements financiers et projection*

- Saisie manuelle des prêts : capital restant dû, mensualité, taux, échéance, priorité
- Calcul automatique du capital restant dû mois par mois
- Vue timeline : libération successive des charges sur 60 mois
- Pour chaque libération : proposition de réaffectation (investissement, remboursement anticipé, projet)
- Capacité d'épargne nette = Revenus − Charges fixes − Prêts − Train de vie estimé
- Simulation remboursement anticipé : gain en mois sur N vs capital immobilisé

### Module 9 — Compteur d'indépendance
*L'indicateur central de Maggie Finance*

- Taux de couverture : % rentes nettes / train de vie mensuel, affiché en permanence
- Date cible N : date projetée d'atteinte des 100% selon trajectoire actuelle
- Décomposition des rentes actives : immobilier, placements, dividendes, SaaS...
- Train de vie calculé dynamiquement depuis les transactions (pas saisi manuellement)
- Courbe de progression du taux de couverture mois par mois
- Objectifs intermédiaires : 25%, 50%, 75%, 100% avec dates projetées
- Comparaison des scénarios : 'Scénario A avance N de 2 ans 3 mois vs scénario B'

### Module 10 — Projets & financement
*File de projets pilotée par la capacité réelle, pas par des objectifs d'épargne*

- **PRINCIPE : la capacité d'investissement mensuelle est calculée en premier, les projets s'y calent automatiquement**
- Création de projets : nom, montant, score bien-être (Indispensable/Élevé/Moyen/Confort), priorité
- File ordonnée par priorité, réordonnée par drag & drop
- Choix du mode de financement : comptant / emprunt / emprunt partiel
- Affichage systématique des 3 métriques : coût réel total, impact sur N, délai avant jouissance
- Timeline glissante : vue mois par mois de la réalisation des projets sur 36 mois
- Si un mois est plus chargé : la timeline se décale sans culpabilité

### Module 11 — Moteur de simulation
*Calcul automatique des 3 scénarios optimisés*

- Calcul asynchrone (job backend) déclenché à la demande ou après chaque revue mensuelle
- Exploration des combinatoires : ordre projets × modes financement × timing investissements
- Horizon 20 ans, granularité mensuelle, max 10 projets simultanément
- **Scénario 1 "Rapide"** : optimise pour N minimal, reporte les projets confort si impact N > 3 mois
- **Scénario 2 "Équilibré"** (recommandé) : projets indispensables protégés, reste optimisé pour N
- **Scénario 3 "Qualité de vie"** : tous projets bien-être traités en priorité, investissement avec surplus
- Slider de pondération entre les 3 pôles
- Personnalisation : l'utilisateur force un paramètre, le moteur réoptimise le reste
- Boucle vertueuse modélisée : libération capital → réinvestissement → rente → N réduit

### Module 12 — Investissements & Retraite
*Constitution de rentes, gestion du portefeuille multi-classes, accélération de N*

- Allocation cible : l'utilisateur définit sa répartition cible par classe d'actif (%)
- Allocation réelle calculée automatiquement depuis les actifs saisis
- Écart cible vs réel affiché en permanence avec proposition de rééquilibrage
- 5 classes d'actifs supportées : Immobilier, Bourse, Or, Crypto, Capital société
- Hypothèses de rendement modifiables par l'utilisateur avec badge si hors fourchette historique
- Projection systématique en 3 scénarios : pessimiste / médian / optimiste
- Rééquilibrage proposé : arbitrage ou réorientation des versements uniquement
- **LIMITE EXPLICITE** : le module fournit des références historiques informatives. Il ne réalise aucun conseil en investissement réglementé (directive MIF II).

**Références historiques par classe d'actif :**

| Classe / Type | Risque | Rendement réf. | Volatilité |
|---------------|--------|----------------|------------|
| Locatif classique | ●●○○○ | 4-6%/an brut | Faible |
| LMNP meublé | ●●○○○ | 5-7%/an brut | Faible |
| SCPI | ●●○○○ | 4-5%/an | Faible |
| ETF obligataire | ●○○○○ | 2-4%/an | Très faible |
| ETF monde (MSCI World) | ●●○○○ | 6-8%/an | Modérée |
| ETF sectoriel | ●●●○○ | 5-12%/an | Forte |
| Actions individuelles | ●●●●○ | Variable | Très forte |
| Or physique / ETF or | ●●○○○ | 2-4%/an | Modérée |
| BTC (store of value) | ●●●○○ | ~30%/an hist. | Extrême |
| ETH / L1 établis | ●●●●○ | ~40%/an hist. | Extrême |
| Staking / DeFi | ●●●●○ | APY 3-15% | Forte |
| Altcoins | ●●●●● | Variable | Extrême |
| Entreprise établie | ●●●○○ | Div. 3-8%/an | Modérée |
| Startup early stage | ●●●●● | 0 ou ×10+ | Extrême |

---

## 5. Règles métier

### 5.1 Matelas de sécurité — règles de gestion

| Paramètre | Valeur / Comportement |
|-----------|----------------------|
| Cible | N mois de salaire net (paramétrable, recommandé : 3 mois) |
| Priorité | P0 — bloque tout investissement et projet tant qu'incomplet |
| Score si incomplet | Ne peut pas être vert — badge 'constitution en cours' permanent |
| Déclencheur recharge | Toute transaction débitant le compte matelas |
| Mensualité de recharge | = Montant entamé / Durée cible en mois |
| Plafond mensuel | Paramétrable (ex : 150 €). Si mensualité > plafond : durée allongée |
| Durée cible | Paramétrable (ex : 3 mois). Recommandation : 2-6 mois |

**Exemple de plan de recharge :**
- Matelas cible : 16 140 € (3 mois × 5 380 €)
- Entame : −800 € (réparation imprévue)
- Mensualité théorique : 267 €/mois
- Plafond paramétré : 150 €/mois
- Durée ajustée : 6 mois
- Impact capacité invest. : −150 €/mois pendant 6 mois

### 5.2 Capacité d'investissement mensuelle nette

```
Capacité mensuelle nette = Revenus nets − Charges fixes − Remboursements prêts − Train de vie estimé
Train de vie estimé      = Moyenne 3 derniers mois de dépenses, hors tag 'exceptionnel'
```

Recalculée après chaque revue mensuelle et à chaque modification de charges fixes.

### 5.3 Enveloppes annuelles

Mode enveloppe annuelle recommandé pour les catégories avec :
- Dépenses saisonnières ou concentrées sur quelques mois
- Achats fréquemment anticipés (billets, matériel, équipement)
- Pilotage naturellement annuel pour l'utilisateur

Session de planification annuelle déclenchée en novembre/décembre : pré-saisie des events, calcul du budget annuel nécessaire, impact sur N, validation ou ajustement.

### 5.4 Score de performance quotidien

Calculé sur 2 dimensions simultanées : le mois en cours (budget mensuel) et l'année en cours (enveloppes annuelles). Le score global est le plus dégradé des deux.

Si dépenses réelles < dépenses J-365 à même date → score vert indépendamment du budget.

Le score par catégorie est indépendant du score global.

### 5.5 Options de financement des projets

| Critère | Comptant | Emprunt |
|---------|----------|---------|
| Disponibilité | Après accumulation de l'épargne | Quasi-immédiate |
| Coût total | Montant du projet (0 intérêt) | Montant + intérêts |
| Capital épargne | Consommé entièrement | Préservé et investissable |
| Charge mensuelle | Aucune | Mensualité pendant la durée |
| Coût net réel | = Montant projet | = Intérêts − rendement capital préservé |
| Quand préférable | Taux intérêt > rendement investissement | Rendement investissement > taux du prêt |

Emprunt partiel : apport paramétrable, date = délai accumulation de l'apport, capital restant préservé investissable.

### 5.6 Boucle vertueuse

1. Capacité d'épargne mensuelle accumulée
2. Seuil atteint → événement (remboursement anticipé, apport investissement, projet)
3. Capital mensuel libéré OU rente créée
4. Réaffectation proposée à l'utilisateur
5. Nouvelle rente → taux de couverture augmente → N recalculé
6. **Retour étape 1 avec une capacité augmentée (effet cumulatif et accélérateur)**

### 5.7 Scénarios automatiques

**Scénario 1 — Le plus rapide vers l'indépendance**
- Optimise pour : minimiser N à tout prix
- Reporte les projets confort si impact N > 3 mois
- Compromis : qualité de vie potentiellement contrainte à court terme

**Scénario 2 — Équilibré** *(recommandé par défaut)*
- Optimise pour : minimiser N en respectant les projets 'Indispensable' et 'Élevé'
- Point d'équilibre entre liberté financière et qualité de vie présente

**Scénario 3 — Qualité de vie maximale**
- Optimise pour : score bien-être global des projets
- Tous les projets bien-être traités en priorité, investissement avec le surplus

Le moteur de simulation explore les combinatoires sur 4 dimensions : ordre de priorité des projets, mode de financement de chaque projet, réaffectation du capital libéré, timing des investissements. Horizon 20 ans, granularité mensuelle, max 10 projets simultanément. **Calcul asynchrone** — notification à l'utilisateur quand prêt.

### 5.9 Investissements — règles de gestion du portefeuille

#### 5.9.1 Allocation cible et rééquilibrage
- L'utilisateur définit une allocation cible en % par classe d'actif (total = 100%)
- Seuil de rééquilibrage paramétrable (défaut : écart > 5% sur une classe)
- Deux modes : arbitrage (vendre/acheter) ou réorientation des versements
- Le mode 'versements uniquement' est préféré car sans impact fiscal

#### 5.9.2 Configuration d'une poche d'investissement

| Paramètre | Détail |
|-----------|--------|
| Classe | Immobilier / Bourse / Or / Crypto / Capital société |
| Type | Sous-type dans la classe |
| Valeur de référence | Rendement historique et fourchette intégrés par défaut, modifiables |
| Hypothèses | Pessimiste / Centrale / Optimiste — paramétrables |
| Badge d'alerte | Affiché si hypothèse s'écarte > 50% de la référence historique |
| Projection rente | Capital projeté × taux de retrait annuel (défaut 4%) / 12 |

#### 5.9.3 Actifs illiquides — signalement
- Or physique, immobilier, capital société : marqués comme illiquides
- Un actif illiquide ne peut pas être compté dans le matelas de sécurité
- Alerte si part d'actifs illiquides > 70% du portefeuille

#### 5.9.4 Limite réglementaire

> ⚠️ **À afficher dans l'interface** — Maggie Finance fournit des données historiques à titre informatif. Le module ne réalise aucun conseil en investissement au sens de la directive MIF II. Les hypothèses de rendement sont définies par l'utilisateur et n'engagent pas l'éditeur. Tout investissement comporte un risque de perte en capital.

### 5.11 Fiscalité des investissements

Chaque poche affiche systématiquement rendement brut ET rendement net après fiscalité.

| Classe / Régime | Imposition | Abattement / Avantage |
|-----------------|-----------|----------------------|
| Compte-titres ordinaire | PFU 30% (12,8% IR + 17,2% PS) | Aucun |
| PEA (après 5 ans) | PS 17,2% uniquement | Exonération IR |
| Assurance-vie (après 8 ans) | 7,5% + PS ou PFU | Abattement 4 600 €/an |
| PER | Déductible entrée, imposé sortie | Déduction TMI à l'entrée |
| Location nue micro-foncier | TMI + PS sur 70% des loyers | Abattement 30% |
| LMNP micro-BIC | TMI + PS sur 50% des loyers | Abattement 50% |
| LMNP réel (amortissement) | Quasi 0 pendant ~15 ans | Amortissement bien+mobilier |
| Plus-value cession immo | IR 19% + PS 17,2% | Exonération IR à 22 ans, PS à 30 ans |
| Or physique | Taxe forfaitaire 11,5% | Option PV droit commun |
| Crypto (depuis 2023) | PFU 30% | Aucun |
| Dividendes société | PFU 30% ou barème progressif | Abattement 40% si barème |

**Exemple — ETF Monde à 7% brut sur 20 ans, versement 200 €/mois :**

| Enveloppe | Rendement net | Capital projeté | Écart vs CTO |
|-----------|---------------|-----------------|--------------|
| Compte-titres (PFU 30%) | 4,9%/an | 52 400 € | référence |
| PEA (PS 17,2% après 5 ans) | 5,8%/an | 62 800 € | +10 400 € |
| Assurance-vie (après 8 ans) | 5,5%/an | 59 200 € | +6 800 € |

### 5.12 Microservice de données de référence

Composant indépendant de Maggie Finance, exposant une API REST versionnée. Source de vérité unique pour toutes les valeurs de référence.

**3 couches de données :**
- **Couche 1 — Fiscalité** : PFU, tranches IR, PS, abattements, plafonds (PEA/PER/AV) — mise à jour annuelle (loi de finances)
- **Couche 2 — Marché** : taux immobilier (BdF), taux directeurs BCE/BNS, inflation INSEE — mensuel ou sur événement
- **Couche 3 — Rendements historiques** : moyennes par classe/type — annuel

**API exposée :**

| Endpoint | Description |
|----------|-------------|
| `GET /v1/reference/fiscal` | Données fiscales par pays et date d'effet |
| `GET /v1/reference/market` | Taux immobilier, taux directeurs, inflation |
| `GET /v1/reference/returns` | Rendements historiques par classe et horizon |
| `GET /v1/changelog` | Liste des modifications depuis une date donnée |
| `POST /v1/webhook/subscribe` | Maggie Finance s'abonne aux notifications de MAJ |

**Mécanique de propagation :**
1. Admin met à jour une valeur (ex : PFU 30% → 33%)
2. Microservice publie un événement webhook vers tous les abonnés
3. Maggie Finance déclenche un job batch asynchrone
4. Recalcul de toutes les simulations affectées par utilisateur
5. Génération d'un bilan d'impact personnalisé (rouge / orange / vert)
6. Notification utilisateur : "X éléments de ta stratégie sont impactés"

**Valeur produit SaaS :** un outil local devient obsolète à chaque loi de finances. Maggie Finance reste toujours à jour et informe proactivement sur *ta* stratégie, pas sur une news fiscale générique.

### 5.13 Fiscalité revenus locatifs (détail)

| Paramètre | Défaut personnalisable |
|-----------|----------------------|
| Régime | Micro-foncier (abattement 30%) |
| TMI | 30% |
| Prélèvements sociaux | 17,2% |
| Taux effectif | ~33% sur loyer brut |

### 5.14 Saisie détaillée des emprunts

#### 5.14.1 Types de prêts supportés

| Type | Description |
|------|-------------|
| Amortissable taux fixe | Mensualités constantes. Le plus courant. |
| Amortissable taux variable | Indice + marge contractuelle. Cap haut/bas éventuel. |
| In fine | Intérêts uniquement pendant la durée. Capital remboursé à l'échéance. |
| À paliers | Mensualités progressives ou dégressives. |
| Différé partiel | Période initiale : intérêts seuls, pas d'amortissement du capital. |
| Différé total | Période initiale : rien payé. Intérêts capitalisés. |
| Prêt relais | Court terme, in fine, en attente de la vente d'un bien. |

#### 5.14.2 Paramètres de saisie complets (23 paramètres)

Montant initial, capital restant dû, taux nominal, TAEG, durée totale, date première échéance, périodicité, assurance emprunteur (taux % et €/mois), frais de dossier, indice de référence (taux variable), marge contractuelle, cap haut/bas, paliers, durée différé, **IRA** (4 modes de calcul), remboursement partiel minimum, effet du remboursement partiel (réduire durée ou mensualité), délai de préavis, priorité de remboursement.

#### 5.14.3 Calcul des IRA dans la simulation

| Mode IRA | Formule |
|----------|---------|
| Plafond légal immobilier | min(6 mois d'intérêts au taux nominal, 3% du capital restant dû) |
| Montant fixe contractuel | Valeur saisie directement |
| Exonéré | IRA = 0 € (PTZ, certains prêts aidés) |
| Prêt conso | 1% capital restant si > 12 mois, 0,5% si ≤ 12 mois |

*Exemple : Capital restant 98 000 €, taux 1,8% → IRA légales = min(882 €, 2 940 €) = 882 €. Gain réel du remboursement anticipé = intérêts restants − IRA = 6 200 − 882 = 5 318 €.*

### 5.15 Module OCR — import de documents de prêt

Microservice indépendant qui extrait automatiquement les paramètres contractuels d'une offre de prêt.

| Type de document | Fiabilité OCR |
|------------------|---------------|
| Tableau d'amortissement (PDF natif) | Très élevée ⭐ — préférer ce document |
| FISE / FIPEN (format EU standardisé) | Élevée |
| Offre de prêt (PDF natif) | Bonne |
| Contrat signé (scan PDF) | Moyenne — qualité scan dépendante |

**Niveaux de confiance par champ :**
- ✅ **Haute** (montant, taux, durée, TAEG, mensualité) → pré-rempli vert
- ⚠️ **Moyenne** (assurance, conditions IRA) → pré-rempli orange — à vérifier
- ❌ **Faible / Non détecté** (paliers, différé, remboursement partiel min) → saisie manuelle

Le libellé contractuel original des IRA est affiché en regard du champ parsé pour validation. Les corrections manuelles alimentent l'amélioration progressive du parser.

### 5.16 Moteur de simulation — architecture et formats

#### 5.16.1 Architecture workers

```
ClearView App → POST /simulation/submit → valide JSON, publie queue, retourne simulation_id
             → GET /simulation/{id}/status → pending / running / done / error

Queue (RabbitMQ) → distribue aux workers disponibles

Worker (×N) → lit JSON d'input → charge version reference_data
           → calcule les scénarios → écrit JSON d'output
           → Webhook → ClearView → notification utilisateur

Chaque worker est un process indépendant scalable horizontalement.
```

#### 5.16.2 Format d'entrée (simulation_input.json)

| Bloc | Contenu |
|------|---------|
| `version` + metadata | Version du format, simulation_id, date, version reference_data |
| `profile` | Revenus, charges fixes, prêts, train de vie 6 mois, TMI, matelas, devise |
| `assets` | Actifs existants : classe, type, valeur, revenus, régime fiscal, liquidité |
| `debts` | Prêts : mensualité, capital restant, taux, échéance, priorité, IRA |
| `annual_envelopes` | Enveloppes annuelles : catégorie, budget, déjà consommé/engagé |
| `projects` | Projets planifiés : label, montant, score bien-être, priorité, financement |
| `investment_pockets` | Poches d'investissement : classe, type, enveloppe fiscale, rendement |
| `simulation_params` | Horizon, objectif optimisation, poids bien-être/indépendance, contraintes forcées |

#### 5.16.3 Format de sortie — événements de la frise

| Type d'événement | Catégorie | Couleur |
|-----------------|-----------|---------|
| `project_start` / `project_end` | project | 🔵 #2E6DA4 |
| `debt_cleared` | debt | 🔴 #C62828 |
| `debt_prepayment` | debt | 🔴 #C62828 |
| `investment_start` / `investment_milestone` | investment | 🟢 #2E7D32 |
| `independence_milestone` | independence | ⭐ #F9A825 |
| `independence_reached` | independence | ⭐ #F9A825 |
| `fiscal_milestone` | fiscal | ⚫ #546E7A |
| `cushion_constituted` | cushion | 🟣 #7B1FA2 |
| `cushion_recharge_start` | cushion | 🟣 #7B1FA2 |

**Champs communs à tous les événements :** `id`, `month` (YYYY-MM), `type`, `category`, `color`, `icon`, `label`, `sublabel`, `amount_eur`, `monthly_impact_eur`, `n_impact_months`, `detail`.

#### 5.16.4 Ce que la frise ne modélise pas

> ⚠️ *Cette simulation suppose : un matelas de sécurité intact sur l'horizon, des revenus stables, aucune vacance locative, aucun événement de vie imprévisible. Tout événement réel (entame de matelas, changement de revenus) recalcule automatiquement la simulation depuis la situation courante.*

Le matelas de sécurité apparaît sur la frise uniquement pour deux événements décidés : constitution terminée et recharge en cours au moment du calcul.

---

## 5bis. Modèle économique — Freemium

### 5bis.1 Split Free / Premium

**FREE** — *Je comprends où va mon argent*
- Connexion bancaire (jusqu'à 3 comptes)
- Synchronisation automatique
- Catégorisation auto + manuelle
- Dashboard recettes / dépenses
- Score de performance quotidien
- Enveloppes annuelles (jusqu'à 3)
- Revue mensuelle
- Matelas de sécurité
- Saisie des dettes et charges fixes
- Compteur indépendance — % actuel uniquement

**PREMIUM** — *Je pilote ma trajectoire* — *Tout le FREE, plus :*
- Compteur indépendance complet (date N + courbe)
- Module projets & financement
- Moteur de simulation — 3 scénarios auto
- Frise temporelle interactive
- Module investissements (allocation, fiscalité)
- Propagation microservice + bilan d'impact
- OCR import contrats de prêt
- Enveloppes annuelles illimitées
- Connexion bancaire illimitée (> 3 comptes)
- Export simulations (PDF, JSON)
- Historique des simulations

### 5bis.2 Tarification

| Plan | Tarif | Détail |
|------|-------|--------|
| Free | 0 € | Sans CB requise |
| Premium mensuel | 9,90 €/mois | Sans engagement |
| Premium annuel | 79 €/an | 6,58 €/mois — 2 mois offerts |
| **Premium illimité** | **Admin uniquement** | **Accès permanent sans expiration** |

> Positionnement : les outils comparables (YNAB, Copilot) sont à 12-15 $/mois sans la dimension simulation ni indépendance financière.

### 5bis.3 Paywall contextuel

Le paywall apparaît au moment exact où l'utilisateur veut une réponse à une question précise :

| Déclencheur | Message paywall |
|-------------|-----------------|
| Clic sur 'Voir la date N' | Découvre quand tu atteins l'indépendance financière — Premium |
| 'Simuler ce remboursement anticipé' | Calcule le gain réel de ce remboursement — Premium |
| 'Comparer les 3 scénarios' | Vois les 3 meilleures trajectoires pour ton profil — Premium |
| 'Voir l'impact sur ma trajectoire' | Cette dépense repousse ton indépendance de X mois — Premium |
| Notification MAJ fiscale | X éléments de ta stratégie sont impactés — Premium pour voir le bilan |
| 4e enveloppe annuelle | Crée des enveloppes illimitées — Premium |

### 5bis.4 Plans Premium illimités (admin)

| Paramètre | Détail |
|-----------|--------|
| Attribution | Interface admin uniquement — pas d'auto-souscription possible |
| Champ technique | `user.plan = 'unlimited'` + `user.plan_expires_at = null` |
| Cas d'usage | Compte fondateur, associés, bêta-testeurs privilégiés, comptes de démo |
| Révocation | Possible depuis l'admin — bascule vers free ou premium standard |
| Audit | Toute attribution et révocation est loguée (admin_id, user_id, date, motif) |

### 5bis.5 Implications techniques

| Élément | Implémentation |
|---------|----------------|
| `user.plan` | enum : `free` \| `premium` \| `unlimited` \| `grandfathered` |
| `user.plan_expires_at` | timestamp \| null (null pour unlimited et free) |
| `user.plan_grandfathered` | boolean — true pour les fondateurs. Immuable sauf action admin tracée. |
| `user.plan_price_override` | Prix figé (9.90) indépendamment des évolutions tarifaires |
| Middleware auth | Vérifie plan + expiration sur chaque endpoint premium |
| Réponse si accès refusé | HTTP 402 avec payload `{ upgrade_url, feature_name, feature_description }` |
| Frontend | Affiche le paywall depuis le payload 402 — pas de logique métier côté client |
| Expiration | Job quotidien : bascule premium → free si `plan_expires_at < now()` ET plan != unlimited |

### 5bis.6 Stratégie de lancement — Bêta & tarif fondateur

| Phase | Durée | Accès | Prix | Objectif |
|-------|-------|-------|------|----------|
| 1 — Bêta fermée | 3-6 mois | Invitation | Gratuit — 50 premiers | Valider le produit, bugs majeurs |
| 2 — Bêta ouverte | 6-12 mois | Waitlist | **9,90 €/mois — garanti à vie** | 200-500 users, point mort, affiner |
| 3 — Release | Indéfini | Public | 19,90 €/mois | Les fondateurs gardent 9,90 € à vie |

**La garantie fondateur :**

> *Tu t'inscris en bêta à 9,90 €/mois. Quand Maggie Finance passe en version complète à 19,90 €, tu gardes 9,90 € pour toujours. Pas de limite de durée. Pas d'astérisque.*

Condition unique : en cas de résiliation, le tarif fondateur n'est pas conservé à la réinscription.

**Dynamiques d'acquisition bêta :**
- Le tarif à vie crée une urgence réelle : chaque jour d'hésitation coûte 10 €/mois à vie
- Compteur visible sur la landing page : "Plus que X jours au tarif fondateur"
- Limite de 500 comptes fondateurs maximum
- Les 50 premiers : 6 mois gratuits en échange de feedback structuré hebdomadaire
- Canal privé Discord/Slack réservé aux fondateurs — leur voix compte plus dans la roadmap
- Parrainage : un fondateur qui invite quelqu'un = les deux conservent le tarif fondateur

### 5bis.7 Infrastructure cloud — scaling dynamique

Architecture à deux couches :

**Couche stable — toujours allumée**
- App Symfony + API Platform
- PostgreSQL (DB managée)
- RabbitMQ — queue de messages, signal de scaling pour les workers
- Microservice données de référence
- Bridge API — connexion bancaire

**Couche élastique — scale to zero — coût proportionnel au volume réel**
- Workers simulation — instances provisionnées à la demande quand la queue dépasse le seuil
- OCR processing — instances déclenchées à chaque upload
- Batch jobs — propagation MAJ fiscales, recalcul simulations en masse
- **0 workers au repos — coût = 0 en dehors des pics**

**Mécanique de scaling :** signal = profondeur de la queue RabbitMQ.

| Signal | Comportement |
|--------|-------------|
| Queue depth = 0 | 0 workers actifs — coût = 0 |
| Queue depth > seuil | Autoscaler provisionne N workers en parallèle |
| Queue vidée | Workers déprovisionnés automatiquement |
| Pic mensuel (1er du mois) | Jusqu'à 5-10 workers simultanément |

**Options techniques par phase :**

| Phase | Solution | Coût estimé | Complexité ops |
|-------|----------|-------------|----------------|
| Bêta fermée (0-200 users) | Fly.io Machines | 35-50 €/mois | Nulle |
| Bêta ouverte (200-500) | Fly.io ou Scaleway Serverless | 60-100 €/mois | Faible |
| Pre-release (500-1000) | Render + Fly.io workers | 100-180 €/mois | Faible |
| Release (1000-5000) | Hetzner K3s + Cluster Autoscaler | 180-400 €/mois | Élevée |

**Levier critique — startup plan Bridge :**

| Scénario | Net/mois à 50 users (9,90 €) |
|----------|------------------------------|
| Fly.io + Bridge startup plan (0 €) | +55 €/mois |
| Fly.io + Bridge plein (50 €) | +7 €/mois |
| VPS OVH + Bridge plein | −15 €/mois |
| Hetzner CX32 + Bridge startup plan | **+57 €/mois — optimal** |

> Priorité absolue : contacter Bridge ET Powens cette semaine, les mettre en concurrence, demander le startup plan avant de signer.

---

## 6. Parcours utilisateur principaux

### 6.1 Onboarding

1. Création du compte, paramètres : devise salaire, TMI, fourchette taux, seuil notification
2. Connexion Bridge : autorisation des comptes
3. Import 12 derniers mois, revue de catégorisation initiale
4. Choix du mode (mensuel / enveloppe annuelle) pour chaque catégorie
5. Session de planification annuelle : pré-saisie des events de l'année
6. Saisie des charges fixes, prêts, matelas de trésorerie
7. Première projection du compteur d'indépendance

### 6.2 Usage quotidien

1. Ouverture module : score du jour en évidence + compteur d'indépendance
2. Consultation des dernières transactions synchronisées
3. Si transaction importante : notification impact + alternatives
4. Correction manuelle d'une catégorisation si besoin
5. Consultation du budget restant : mensuel et enveloppes annuelles

### 6.3 Dépense plaisir en temps réel

1. L'utilisateur s'apprête à acheter un billet de concert à 150 €
2. Il peut le saisir en avance dans 'À arbitrer' et voir l'impact avant de payer
3. Le module affiche : impact sur N, état de l'enveloppe annuelle, alternatives
4. Il décide : maintient, optimise le financement, ou reporte à une date meilleure
5. S'il paie sans saisir à l'avance, la notification arrive à la détection de la transaction

### 6.4 Session de planification annuelle

1. Déclenchée en novembre/décembre, ou à tout moment via le menu
2. Liste des événements récurrents de l'année précédente, à reconduire ou modifier
3. Saisie des nouveaux événements envisagés avec montants estimés et statut
4. Calcul du budget annuel nécessaire et impact sur N
5. Les budgets validés alimentent les enveloppes annuelles de l'année suivante

### 6.5 Revue mensuelle

1. Notification le 1er du mois
2. Qualification des dépenses non-obligatoires : À conserver / Évitable
3. Score + comparaison M-1, M-3 et J-365
4. Si capital libéré : proposition de réaffectation
5. Relance du moteur de scénarios, notification quand prêts

### 6.6 Arbitrage financement projet

1. Création du projet avec montant, score bien-être, priorité
2. Sélection du mode de financement : 3 options calculées automatiquement
3. Affichage : coût total, impact N, délai avant jouissance pour chaque option
4. Choix, insertion dans la file, recalcul timeline et compteur
5. Relance moteur de scénarios en arrière-plan

---

## 7. Contraintes non fonctionnelles

| Exigence | Détail |
|----------|--------|
| Sécurité | Tokens Bridge chiffrés, HTTPS, JWT, aucun credential bancaire stocké |
| Confidentialité | Données sur infrastructure propre, aucun partage tiers |
| Performance | Dashboard < 2s, score recalculé en < 500ms, scénarios asynchrones |
| Multi-device | Web responsive (React) + app mobile Android Kotlin (Android first) |
| Neutralité | Le module informe sans recommander. Il présente des faits et projections, jamais de jugements |
| Multi-user ready | Architecture tenant-based, isolation stricte par user dès le départ |
| Auditabilité | Toutes corrections de catégorisation tracées |
| Réversibilité | Toute action manuelle annulable |

---

## 8. Glossaire

| Terme | Définition |
|-------|-----------|
| Autonomie financière | État où les rentes passives couvrent 100% du train de vie |
| N | Nombre de mois/années avant l'autonomie financière selon la trajectoire actuelle |
| Compteur d'indépendance | % couverture rentes / train de vie + date N projetée |
| Capacité d'investissement nette | Revenus − charges fixes − prêts − train de vie. Disponible pour projets et investissements |
| Train de vie | Moyenne des dépenses mensuelles réelles sur 6 mois, hors exceptionnel |
| Enveloppe annuelle | Budget global sur 12 mois pour les catégories à temporalité annuelle |
| Transaction engagée | Argent sorti pour un event futur (billet acheté, réservation). Déduite de l'enveloppe à l'achat |
| Score de performance | Signal quotidien vert/neutre/orange/rouge sur la trajectoire financière |
| Friction consciente | Informer l'utilisateur de l'impact d'une dépense sans l'empêcher ni le culpabiliser |
| Boucle vertueuse | Capital libéré → investissement → rente → capacité augmentée → N réduit |
| Score bien-être | Note 1-4 sur un projet : Indispensable / Élevé / Moyen / Confort |
| Rente | Revenu passif régulier : loyer, dividendes, intérêts, revenu SaaS récurrent |
| Matelas de sécurité | Reserve liquide cible en N mois de salaire. Priorité absolue. Recharge automatique avec plafond si entamée |
| Frontalier | Salarié travaillant en Suisse, résidant en France, salaire en CHF |
| IRA | Indemnités de Remboursement Anticipé — pénalité contractuelle pour remboursement avant échéance |
| Capacity-first | Approche où la capacité d'épargne réelle est calculée en premier, les projets s'y calent ensuite |
| Startup plan Bridge | Programme partenaire Bridge by Bankin' offrant des connexions gratuites aux startups early stage |

---

*Maggie Finance — Module de la plateforme Maggie — Documentation Fonctionnelle v3.0 — Avril 2026*

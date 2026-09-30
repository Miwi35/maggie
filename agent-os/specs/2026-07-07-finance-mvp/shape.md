# Maggie Finance — MVP Foundation — Shape

## Problem
Maggie Finance (doc fonctionnelle v3.0) est un module ambitieux de 12 sous-modules + services externes. Rien n'existe encore dans le code. Avant les couches calculées (score, matelas, compteur, simulation) et l'intégration bancaire, il faut le **socle data model** : comptes, catégories, transactions, enveloppes — dont tout le reste dérive.

Voir la [roadmap complète](../../product/finance-roadmap.md) et la [spec fonctionnelle](../../product/finance-functional-spec.md).

## Solution
Créer le bundle `api/modules/finance/` (`Maggie\Finance`) sur le pattern exact de `Maggie\Grocery`, et livrer le socle en **tranches verticales full-stack** (API → admin React → mobile Kotlin), une entité de bout en bout à la fois.

Périmètre de cette spec = **modules doc M1 (Comptes), M2 (Catégorisation de base), M3 (Enveloppes de base)** en saisie manuelle. 4 entités :

1. **Account** — un compte bancaire (nom, banque, type, devise, solde, flag matelas). Saisie manuelle du solde ; `bridgeAccountId` nullable prévu pour la sync future.
2. **Category** — arbre 2 niveaux (parent auto-référencé), avec `obligation` (obligatoire / non-obligatoire / épargne / investissement).
3. **Transaction** — mouvement (compte, montant, devise, date, libellé, catégorie), avec `status` (dépensée / engagée / planifiée / à arbitrer) et flag `exceptional`.
4. **Envelope** — budget par catégorie, `mode` mensuel ou annuel, montant, période.

Chaque entité suit le triptyque du repo : Entity (ULID + Mercure + ES) → Repository → Message/Handler → State processor → MCP tools → tests Controller & Mcp, puis vue admin + écran mobile.

## Boundaries (hors périmètre de cette spec)
- **Pas de sync Bridge** — saisie/import manuel uniquement (spec `finance-bridge-sync` séparée, dès accès API).
- **Pas de règles de catégorisation auto** — catégorisation manuelle ; l'apprentissage par règles est la spec `finance-categorization-rules`.
- **Pas de calcul de score / matelas / compteur** — ce sont des couches dérivées (specs dédiées).
- **Pas de statuts d'enveloppe déduits (engagé/planifié/réservé)** — le champ `status` existe sur Transaction, mais la mécanique de déduction d'enveloppe annuelle est la spec `finance-annual-envelopes`.
- **Pas de freemium/paywall** — tout est accessible ; le gating premium arrive avant la première feature Premium (spec `finance-freemium-paywall`).
- **Multi-devises** : on stocke `currency` par compte/transaction, mais **pas de conversion** en v1 socle (agrégation en devise d'affichage = spec sync/dashboard).

## Key Decisions
1. **Montants en centimes entiers** (`int`, ex. `amountCents`) + `currency` ISO-4217 — jamais de float pour la monnaie. Cohérent avec les contraintes d'exactitude comptable.
2. **Scoping par owner** via `OwnedByUserInterface` + relation `User` (comme toutes les entités du repo) — isolation stricte multi-user dès le départ (contrainte doc §7).
3. **Catégorie = arbre auto-référencé** (`parent` ManyToOne nullable) plutôt que 2 entités distinctes — 2 niveaux gérés par convention (une catégorie racine a `parent = null`).
4. **`obligation`, `status`, `mode` = enums PHP** backed string, indexés ES en `keyword` (pattern `ProductCategory`/`Unit`).
5. **Tranche verticale full-stack, une entité à la fois** (choix produit) : on prouve la chaîne API→admin→mobile sur **Account** d'abord, puis Category, Transaction, Envelope.
6. **Transaction ManyToOne Category nullable** — une transaction non catégorisée est valide (catégorisation = étape ultérieure).
7. **UI en français** — labels admin + mobile en français, comme le reste du repo.

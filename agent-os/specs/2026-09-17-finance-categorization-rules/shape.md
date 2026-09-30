# Maggie Finance — Règles de catégorisation — Shape

## Problem
Le socle MVP (`finance-mvp`, livré le 16 sept. 2026) permet de saisir des transactions, mais la catégorie reste **entièrement manuelle** : chaque course, chaque abonnement, chaque plein d'essence doit être classé à la main. Or tout le reste du module (enveloppes, score, revue mensuelle, train de vie) dérive de la catégorie. Une transaction non catégorisée est invisible pour le budget.

La doc fonctionnelle [M2](../../product/finance-functional-spec.md) demande : catégorisation automatique par règles (libellé, montant, récurrence), correction manuelle **avec mémorisation**, détection d'abonnements, et traçabilité des corrections.

## Solution
Une entité **`CategorizationRule`** : « si le libellé contient X (et, optionnellement, si le montant est dans telle fourchette et va dans tel sens), alors catégorie Y ». Les règles d'un utilisateur sont ordonnées par `priority` ; la première qui correspond gagne.

Le moteur `CategorizeTransaction` est appliqué à trois moments :
1. **À la création d'une transaction sans catégorie** — la règle gagnante pose la catégorie automatiquement.
2. **En masse**, sur les transactions non catégorisées déjà en base (action `apply`) — pour rattraper l'historique après création d'une règle.
3. **Jamais sur une catégorie posée à la main** — une correction manuelle ne doit pas être écrasée au prochain passage.

L'**apprentissage** est explicite : catégoriser une transaction à la main peut créer la règle correspondante (action `learn`, pattern déduit du libellé). Pas de ML, pas de magie — une règle lisible et modifiable par l'utilisateur.

La **traçabilité** passe par deux champs ajoutés à `Transaction` : `categorySource` (`manual` / `rule` / `none`) et `categorizedAt`. On sait ainsi qui a posé la catégorie et quand, et le moteur sait ce qu'il a le droit d'écraser.

## Boundaries (hors périmètre)
- **Pas de détection d'abonnements récurrents** — elle dérive des règles et de l'historique ; tranche suivante.
- **Pas d'apprentissage implicite / statistique** — seulement des règles explicites, créées par l'utilisateur ou par l'action `learn`.
- **Pas de regex** en v1 : `contains` / `starts_with` / `equals`, insensibles à la casse. Une regex mal écrite par un LLM est un piège de support.
- **Pas de journal d'audit dédié** (entité `CategorizationLog`) — `categorySource` + `categorizedAt` suffisent au besoin du moment ; un vrai journal viendra si la revue mensuelle l'exige.
- **Pas de statuts d'enveloppe déduits** — spec `finance-annual-envelopes`.

## Key Decisions
1. **Fourchette de montant en valeur absolue** (`minAmountCents` / `maxAmountCents` positifs) + `direction` (`any` / `debit` / `credit`), plutôt que des bornes signées. « Entre 10 et 50 € en dépense » est plus lisible que « entre -5000 et -1000 », pour l'utilisateur comme pour le LLM qui appelle le tool.
2. **Première règle gagnante**, par `priority` décroissante puis date de création — déterministe et explicable, contrairement à un score de correspondance.
3. **Le moteur ne touche jamais une catégorie `manual`** — la correction humaine est la source de vérité ; c'est aussi ce qui rend l'application en masse rejouable sans dégât.
4. **`learn` est une action explicite**, pas un effet de bord de la catégorisation manuelle — créer une règle à chaque correction produirait des dizaines de règles parasites.
5. **Pattern déduit du libellé complet** (normalisé, sans les chiffres de fin de libellé bancaire), que l'utilisateur peut ensuite raccourcir dans l'admin.
6. **Application à la création dans le handler**, pas dans un listener Doctrine — même chemin que le reste du module (bus → handler → use case), donc testable et explicite.

---
name: releves-bancaires
description: "Questions sur des mouvements bancaires passés qui ne sont pas dans Maggie (« les virements de N26 depuis un an ») : consulter la banque sans rien importer, puis importer seulement ce que l'utilisateur choisit."
tags:
- finance
- banque
- relevé
- import
---

Quand l'utilisateur te parle de mouvements bancaires anciens, ou que Maggie n'a jamais importés, et que `manage_transactions` ne les trouve pas.

## 1. Consulter d'abord

- `manage_accounts` te donne les comptes ; prends celui relié à une banque.
- `read_bank_statement` interroge la banque et **n'écrit rien** dans Maggie. Donne `from` et `to` (YYYY-MM-DD) et des filtres serrés : `search` (nom de la contrepartie ou du libellé), `direction` (`credit` ou `debit`), `minAmountCents`. Chaque page lue est un appel à la banque, dont le plafond quotidien est limité : jamais plus large que nécessaire.
- `status: history_unavailable` : la banque refuse cette période (souvent 90 jours au plus sans nouvelle authentification forte). Dis-le avec son message, propose une période plus courte ou de reconnecter la banque.
- `status: rate_limited` : le plafond du jour est atteint. Dis-le, propose de réessayer plus tard.
- `truncated: true` : la réponse est partielle. Resserre la période ou les filtres.

## 2. Résumer, puis proposer

Résume ce que tu as trouvé (nombre, total, première et dernière date, ce qui est déjà dans Maggie : `inMaggie`) et propose d'importer les mouvements pertinents. N'importe rien dans ce même tour.

## 3. Importer seulement sur son accord

- Après son accord, `import_bank_transactions` avec les mêmes `accountId`, `from` et `to`, et **uniquement les `ref` choisis** dans `refs`.
- Jamais une période entière, jamais sans accord. Ne réimporte pas ce qui est `inMaggie`.
- Les mouvements arrivent sans catégorie : propose de les qualifier avec `manage_transactions` (ou `manage_peer_debts` pour un virement avec un proche).
- `unknownRefs` non vide : la banque ne les renvoie plus ; dis-le.

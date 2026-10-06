# Import de relevé CSV depuis l'admin — Plan

MAG-44 · décisions et références : `shape.md`

## Task 1 : Sauver la documentation de spec

`agent-os/specs/2026-10-05-2206-finance-csv-statement-import/` avec `plan.md`
et `shape.md`. Pas de `standards.md` ni de `references.md` séparés : les
standards sont référencés par chemin dans `shape.md` (`/inject-standards` en
mode références), les références de code y sont listées. Pas de `visuals/`,
le ticket n'en porte aucun.

## Task 2 : `ImportStatement` rend compte ligne par ligne, et publie

- Ajouter au résultat une clé `rows` : `line`, `bookedAt`, `label`,
  `amountCents`, `currency`, `duplicate`, `categoryName`.
- Remplacer le `IndexDocumentCommand` nu par `EntityBroadcaster::broadcast()`
  pour que l'import atteigne aussi les écrans ouverts.

Tests dus (`ImportStatementTest`) : le détail nomme la ligne écartée comme
doublon ; il nomme la catégorie qu'une règle applique ; une simulation
renvoie le même détail sans rien écrire ; une écriture publie sur Mercure et
dispatche l'indexation.

## Task 3 : `ImportStatementController`

`POST /api/finance/import-statement`, multipart : `file`, `account` (ULID),
`confirm` (`1` pour écrire, absent = simulation).

Réponse : `{ dryRun, account: {id, name, currency}, rowsRead, imported,
skipped, categorized, first, last, totalCents, errors[], rows[] }`.

Refus : 401 sans jeton · 400 sans fichier, sans `account`, fichier > 2 Mo, ou
fichier dont le parser ne tire aucune ligne · 404 pour un compte qui n'est pas
celui de l'utilisateur.

Tests dus (`ImportStatementControllerTest`) : 401 · 400 fichier manquant ·
400 `account` manquant · 404 compte d'un autre utilisateur · 400 fichier
illisible (avec les erreurs du parser) · simulation : rapport complet, aucune
ligne en base, aucune publication · import : état en base vérifié,
`assertMercureUpdatePublished('/transactions/')`,
`assertElasticsearchIndexDispatched(Transaction::class)` · second import du
même fichier : tout en doublon, rien de plus en base.

## Task 4 : `StatementImportPage` dans l'admin

- `admin/src/modules/finance/useStatementImport.ts` — le hook qui poste le
  fichier (simulation puis confirmation) et porte le rapport.
- `admin/src/modules/finance/StatementImportPage.tsx` — compte à choisir,
  fichier à déposer, bouton « Simuler l'import », rapport (compteurs, période,
  total, tableau des lignes avec leur sort), bouton « Importer N opération(s) ».
- Route `/finance/import` dans `financeResources`, export dans `index.ts`,
  entrée de menu « Import de relevé » sous Finance.

Tests dus : `useStatementImport.test.ts` (simulation, confirmation, erreur
HTTP) · `StatementImportPage.test.tsx` (rendu, simulation affiche le rapport
avec doublons et catégories, confirmation poste `confirm=1`, erreur notifiée,
bouton d'import absent tant que rien n'a été simulé).

## Task 5 : Parcours e2e

`e2e/web/tests/finance-import.spec.ts` + `e2e/web/pages/FinanceImportPage.ts`,
route ajoutée à `e2e/web/pages/routes.ts`.

## Tests

| Unité | Tests |
|---|---|
| `ImportStatement` | détail des doublons, détail des catégories, simulation sans écriture, Mercure + ES à l'écriture |
| `ImportStatementController` | 401, 400 ×3, 404 compte étranger, simulation sans écriture, import + état DB + Mercure + ES, réimport dédoublonné |
| `useStatementImport` | simulation, confirmation, erreur HTTP |
| `StatementImportPage` | rendu, simulation → rapport, confirmation, erreur, garde du bouton d'import |
| Parcours web | `finance-import.spec.ts` |

Pas de correction de bug dans ce périmètre : aucun test de reproduction dû.

## Parcours e2e

**Étend :** MAG-102 — Parcours e2e : finance

- **Given** l'utilisateur e2e connecté, sur Finance → « Import de relevé »
- **And** un CSV de trois lignes : un `LECLERC RENNES` de 45,00 € déjà présent
  dans le seed, un `CARREFOUR MARKET` de 12,30 € inédit et une ligne inédite
  de plus
- **When** il choisit « Compte courant », dépose le fichier et clique
  « Simuler l'import »
- **Then** le rapport annonce 2 à importer et 1 doublon écarté, la ligne
  `LECLERC RENNES` est marquée « Déjà présente », la ligne `CARREFOUR MARKET`
  porte la catégorie « Courses » que la règle seedée lui donne
- **And** `GET /api/transactions` ne contient toujours pas `CARREFOUR MARKET`
- **When** il clique « Importer 2 opération(s) »
- **Then** `CARREFOUR MARKET` apparaît dans `/api/transactions` indexé, avec
  `categorySource: rule` et la catégorie `Courses`
- **And** une seconde simulation du même fichier annonce 0 à importer et 3
  doublons

## Definition of Done

- [ ] Tests unitaires/intégration ci-dessus, verts
- [ ] Parcours e2e écrit et exécutable (`task e2e:web`)
- [ ] CI verte sur une PR qui lie MAG-44
- [ ] Spec fonctionnelle du module Finance + guide utilisateur mis à jour dans
      Linear (ADR-006)

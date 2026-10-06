# Import de relevé CSV depuis l'admin — Shaping Notes

MAG-44 · `Feature` · projet « Finance : banque en production »

## Scope

`Import/CsvStatementParser` + `UseCase/ImportStatement` savent déjà lire
l'export CSV de n'importe quelle banque, écarter ce qui est déjà en base et
appliquer les règles de catégorisation. Rien de tout ça n'est joignable depuis
l'application : seul `app:finance:import`, en ligne de commande, y donne accès.

Ce ticket ouvre le même chemin depuis l'admin :

1. Choisir un compte, déposer le fichier.
2. Lire un **rapport de simulation** — ce qui serait importé, les doublons
   écartés, les catégories que les règles appliqueraient — sans qu'une ligne
   soit écrite.
3. Confirmer, et là seulement importer.

## Décisions

### 1. Deux requêtes sur le même endpoint, aucun état serveur

- **Dilemme** : entre la simulation et l'import confirmé, où vit le fichier ?
- **Options** : (a) le navigateur le garde et le renvoie à la confirmation ;
  (b) le serveur le stocke entre les deux (session, cache, entité d'import).
- **Choix** : (a). `POST /api/finance/import-statement` avec `confirm=1` au
  second appel.
- **Pourquoi** : le dédoublonnage de `ImportStatement` rend déjà un import
  répété inoffensif — c'est écrit dans son docblock et couvert par ses tests.
  Sans état serveur il n'y a ni péremption, ni nettoyage, ni table de
  transit ; le ticket demande de réutiliser `ImportStatement`, pas d'ajouter
  un entrepôt.

### 2. Upload multipart, pas de CSV dans du JSON

- **Dilemme** : comment le fichier arrive-t-il à l'API ?
- **Options** : multipart `file` ; chaîne CSV dans un corps JSON.
- **Choix** : multipart.
- **Pourquoi** : c'est ce que produit un `<input type="file">`, Symfony le lit
  nativement (`$request->files`), et la taille reste bornée par le serveur au
  lieu de gonfler un corps JSON.

### 3. Le rapport descend à la ligne

- **Dilemme** : le rapport se contente-t-il des compteurs que
  `ImportStatement` renvoie déjà ?
- **Options** : compteurs seuls ; compteurs + détail par ligne.
- **Choix** : `ImportStatement` gagne une clé `rows` — numéro de ligne, date,
  libellé, montant, doublon ou non, catégorie attribuée par règle.
- **Pourquoi** : le ticket demande « les doublons écartés et les catégories
  appliquées ». Un compteur ne dit pas *quelle* ligne a été écartée ni sous
  quel en-tête l'autre va se ranger — et c'est précisément ce qu'on relit
  avant de confirmer. La clé est additive : la commande CLI et
  `SyncBankAccounts` l'ignorent.

### 4. Un import publie maintenant sur Mercure

- **Dilemme** : `ImportStatement` ne dispatchait que `IndexDocumentCommand`.
- **Choix** : il passe par `EntityBroadcaster`, qui publie *et* réindexe.
- **Pourquoi** : sans publication, les mouvements importés existent mais
  aucun écran ouvert ne les voit arriver — le même oubli que MAG-116/MAG-176,
  que `EntityBroadcaster` existe pour réparer. La Definition of Done exige
  `assertMercureUpdatePublished` sur une écriture. Le changement profite de la
  même façon à la commande CLI et à la synchronisation bancaire.

### 5. La page vit dans Finance, à côté de « Banques »

- **Choix** : route `/finance/import`, entrée de menu « Import de relevé »
  sous Finance, juste après « Banques ».
- **Pourquoi** : l'import CSV est la contrepartie manuelle de la
  synchronisation bancaire ; les deux répondent à « faire entrer mes
  opérations ». Même forme que `BankConnectionsPage` (`/finance/banks`).

### 6. Garde-fous de validation

Fichier absent, compte absent, compte d'un autre utilisateur, fichier au-delà
de 2 Mo, ou fichier dont le parser ne tire aucune ligne → 400 (404 pour un
compte qui n'est pas le sien), et l'import ne touche à rien.

## Contexte

- **Visuels** : aucun sur le ticket.
- **Références** :
  - `api/modules/finance/src/Controller/ApplyCategorizationRulesController.php`
    — forme d'un controller REST hors API Platform (401 explicite, JSON nu).
  - `api/modules/finance/src/Command/ImportStatementCommand.php` — le même
    enchaînement parser → `ImportStatement`, déjà en deux temps (`--write`).
  - `admin/src/modules/finance/BankConnectionsPage.tsx` — page custom Finance,
    `Title`, `FormSection`, `useNotify`.
  - `admin/src/modules/finance/RuleSuggestions.tsx` — tableau de propositions
    relu puis confirmé par un bouton.
  - `e2e/web/tests/finance-rules.spec.ts` + `e2e/web/pages/FinanceCategoriesPage.ts`
    — forme d'un parcours finance et de son page object.
- **Alignement produit** : `finance-functional-spec.md` §« Import 12 derniers
  mois, revue de catégorisation initiale » décrit l'onboarding dont cet écran
  est la première marche. Rien ne le contredit.

## Standards appliqués

- `global/testing` — Definition of Done : chaque unité touchée doit ses tests,
  un parcours e2e couvre la fonctionnalité.
- `api/testing` — PHPUnit, fixtures nelmio/alice, publication Mercure testée.
- `admin/testing` — Vitest, `.test.tsx` co-localisé, `vi.stubGlobal('fetch')`.
- `admin/react-admin` — `CustomRoutes`, `Title`, `useNotify`.
- `global/real-time` — toute écriture publie sur Mercure.
- `global/e2e-environment` — le parcours tourne dans la stack e2e.
- `global/worktree-checks` — `task fix:all`, `task wt:test:*` depuis ce worktree.

# Stock et courses au plus juste — plan

Projet : [P-MAG-25](https://linear.app/meven/project/stock-et-courses-au-plus-juste-43486eca7228) · Cadrage : [MAG-290](https://linear.app/meven/issue/MAG-290/cadrer-la-spec-stock-et-courses-au-plus-juste-et-la-decouper-en) · Décisions : `shape.md`

Neuf tâches de code, un ticket et un PR chacune. L'ordre est celui des dépendances : le
conditionnement et le stock d'abord, parce que tout le reste les lit ; la bascule en dernier,
parce qu'elle éteint l'ancien chemin.

| Tâche | Ticket |
|---|---|
| 2 — le conditionnement d'un produit | [MAG-292](https://linear.app/meven/issue/MAG-292) |
| 3 — l'état du stock et le réappro | [MAG-293](https://linear.app/meven/issue/MAG-293) |
| 4 — `update_stock` | [MAG-294](https://linear.app/meven/issue/MAG-294) |
| 5 — aperçu et ajout du choix (API + MCP) | [MAG-295](https://linear.app/meven/issue/MAG-295) |
| 6 — le choix des ingrédients sur le web | [MAG-296](https://linear.app/meven/issue/MAG-296) |
| 7 — le choix des ingrédients sur le mobile | [MAG-297](https://linear.app/meven/issue/MAG-297) |
| 8 — la fin des courses remet en stock | [MAG-298](https://linear.app/meven/issue/MAG-298) |
| 9 — les quantités en conditionnements | [MAG-299](https://linear.app/meven/issue/MAG-299) |
| 10 — la bascule | [MAG-300](https://linear.app/meven/issue/MAG-300) |

```
292 conditionnement ──┬─► 293 stock ──┬─► 294 update_stock ──┐
                      │               │                      │
                      │               └─► 298 fin de course ─┤
                      │                                      │
                      ├─► 295 API + MCP du choix ──┬─ 296 web ┤
                      │                            └─ 297 mobile
                      └─► 299 quantités en conditionnements ──┴─► 300 bascule
                             (après MAG-291)
```

## Tâche 1 : enregistrer la spec

Créer `agent-os/specs/2026-10-07-0107-stock-et-courses-au-plus-juste/` avec `plan.md`,
`shape.md`, `standards.md`, `references.md`. Pas de `visuals/` : aucun visuel fourni.
Puis créer un ticket Linear par tâche 2 à 10, avec ses `blockedBy`, ses labels et son
parcours e2e. **C'est la tâche de MAG-290** ; les suivantes sont les tickets qu'elle crée.

E2E : `N/A — cadrage, aucun comportement utilisateur`.

## Tâche 2 : le conditionnement d'un produit

Un produit dit en quoi on l'achète, et un ingrédient se convertit en conditionnements,
arrondi au-dessus.

* `Unit` gagne `Jar = 'jar'`.
* `Product` gagne `packagingUnit: ?Unit`, `packagingSize: ?float`,
  `packagingSizeUnit: ?Unit` — « paquet de 500 g » = (`pack`, 500, `g`), « bocal » =
  (`jar`, null, null). Les trois nullables, aucune migration de données (décision 1).
* Un service `Packaging` dans `api/modules/grocery/src/Service/` :
  `packagedQuantity(Product $p, float $quantity, Unit $unit): ?PackagedQuantity` rend le
  nombre de conditionnements (`ceil`), l'unité d'achat, et un drapeau `converted`.
  Conversions tenues : `g` ↔ `kg`, `ml` ↔ `cl` ↔ `l`, égalité stricte pour les unités
  comptées. Sans conditionnement : `null`. Conversion impossible : 1 conditionnement,
  `converted: false` (décision 3).
* Validation : `packagingSize > 0` quand il est posé, et `packagingSizeUnit` obligatoire
  avec lui (et inversement) — une taille sans unité ne veut rien dire.
* `toSearchDocument()` et `toMercurePayload()` portent les trois champs.
* `manage_products` et `manage_ingredients` : les trois champs en entrée, en sortie, et dans
  `clear`.
* Admin `ProductForm.tsx` : une section « Comment on l'achète » avec les trois champs et un
  aperçu lisible (« paquet de 500 g »). `UNIT_CHOICES` gagne le bocal.
* Mobile `Product.kt` : les trois champs ; l'écran produit les affiche en lecture.
* Migration Doctrine additive, trois colonnes nullables.

Tests dus : `PackagingTest` (chaque famille d'unités, l'arrondi au-dessus, l'égalité
stricte, la conversion impossible, l'absence de conditionnement) · `ProductApiTest`
(401, 400 sur une taille sans unité, happy path avec état DB, Mercure, Elasticsearch) ·
`ProductToolsTest` et `cookbook/ProductToolsTest` (`create`, `update`, `clear`) ·
`ProductForm.test.tsx` (rendu, saisie, aperçu) · test d'écran Compose du produit ·
contrat régénéré (`UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`).

## Tâche 3 : l'état du stock, la quantité de réappro et le réappro automatique

Chaque produit a un état de stock, et dit combien en racheter.

* `ProductStockState` : `InStock = 'in_stock'` · `Low = 'low'` · `Out = 'out'`.
* `Product` gagne `stockState: ProductStockState` (non nul, `in_stock` par défaut en base
  comme en PHP), `restockQuantity: ?int` (en conditionnements), `autoRestock: bool`
  (`false` par défaut).
* Migration additive, avec le défaut : les produits existants passent `En stock`.
* `toSearchDocument()`, `toMercurePayload()`, `manage_products`, `manage_ingredients`.
* Admin : les trois champs dans `ProductForm.tsx` (section « Ce qu'il m'en reste »), et une
  colonne d'état dans `ProductList.tsx`, en puce colorée.
* Mobile : les trois champs dans `Product.kt`, l'état en puce dans `ProductListScreen.kt`.

Tests dus : `ProductApiTest` (401, 400 sur un état inconnu et sur un `restockQuantity`
négatif, happy path avec état DB, Mercure, Elasticsearch) · `ProductToolsTest` ×2 ·
`ProductForm.test.tsx` et `ProductList` (rendu de la puce par état) · ViewModel produit
mobile (une transition par état) · contrat régénéré.

Dépend de : tâche 2 (une seule migration à la fois, `lock:migration`).

## Tâche 4 : `update_stock` — Maggie met le stock à jour et réapprovisionne

« Maggie, je n'ai presque plus de riz » → riz en `Stock faible`, et 2 paquets sur la liste.

* `GroceryItemSource` gagne `Restock = 'restock'` (décision 10 ; colonne chaîne, pas de
  migration).
* Outil MCP `update_stock(product, state)` : `product` est un id ou un nom (résolu sur
  l'utilisateur, jamais `findAll()[0]`), `state` est `in_stock` | `low` | `out`.
  * Pose l'état via le bus (`UpdateProductCommand` étendu).
  * État ≠ `in_stock` **et** `autoRestock` **et** `restockQuantity` : ajoute
    `restockQuantity` conditionnements à la liste, source `restock`, magasin habituel du
    produit. Une ligne non cochée existe déjà pour ce produit → **sa quantité monte**, pas
    de deuxième ligne.
  * Répond : l'état posé, ce qui a été ajouté (ou pourquoi rien), et les repas des
    7 prochains jours dont une recette utilise ce produit (décision 6).
* Scénarios `agent/fixtures/fake-llm/49-stock-low-rice.yaml` et
  `51-stock-out-couscous.yaml` (réappro automatique éteint → rien d'ajouté, mais le repas de
  la semaine signalé). `50` est pris par `50-memory-allergy.yaml`.

Tests dus : `UpdateStockToolTest` — pas d'utilisateur lié, produit inconnu, état inconnu,
happy path avec état DB + `assertMercureUpdatePublished` + `assertElasticsearchIndexDispatched`,
réappro automatique éteint → aucune ligne, ligne existante fusionnée, produit d'un autre
utilisateur invisible · `UserIsolationToolsTest` étendu · tests pytest de l'agent sur le
scénario fake-LLM · contrat régénéré (liste des outils).

Dépend de : tâches 2 et 3.

## Tâche 5 : ajouter un repas en choisissant ses ingrédients — API et MCP

Le cœur du projet, côté serveur. L'ancien ajout automatique reste en place **pour les repas
qui n'ont pas encore choisi** (décision 4 bis) ; la tâche 10 l'éteint pour tous.

* `Meal` gagne `groceryChoiceMadeAt: ?\DateTimeImmutable`, nullable et
  `#[ApiProperty(writable: false)]` — le marqueur du choix, posé par l'endpoint et jamais par un
  client, comme `RecurringGroceryItem::$lastAddedAt`. Migration additive, une colonne. `Meal`
  étant `ApiResource` et `MercurePublishable`, le contrat change aussi côté repas.
* `GET /api/meals/{id}/grocery_preview` — **contrôleur REST dédié**, parce que c'est une
  lecture dérivée qui n'est pas la représentation d'une ressource : elle croise les recettes du
  repas, les produits et le service `Packaging`. Rend, par ingrédient des recettes du repas :
  produit (id, nom), quantité et unité de la recette, conditionnement, quantité conditionnée et
  `converted`, état de stock, `suggested` — vrai quand l'état est `low` ou `out` (décision 9) —
  et `groceryChoiceMadeAt` du repas, pour que le client sache si un choix a déjà été fait.
* `POST /api/meals/{id}/grocery_items` — la liste des ingrédients choisis, avec une quantité
  en conditionnements facultative pour forcer. Il **ne reprend pas puis ne repose pas** : il
  délègue à une nouvelle méthode **`MealGrocerySync::syncChoice(Meal $meal, array $chosenLines)`**,
  qui fait tourner la **même réconciliation** que `sync()` avec `$wanted` venant du choix au lieu
  des recettes — reprendre ce que le repas ne veut plus, `adjust()` ce qu'il garde, ajouter ce
  qui manque, fusionner dans une ligne **non cochée** du **même produit et de la même unité**
  (la clé de `findMergeable()`). Les lignes sont posées en conditionnements, source `recipe`,
  `buyAfter` calculé comme aujourd'hui depuis `shelfLifeDays` et le jour du repas, avec une
  `MealGroceryContribution` par ligne (décision 4).
  * **Contrat de flush** : `syncChoice()` **ne flushe pas**, comme `revoke()`. L'endpoint pose
    `groceryChoiceMadeAt` et **un seul flush** commite les lignes et le marqueur ensemble. Deux
    transactions laisseraient, si la seconde échoue, des lignes conditionnées avec un marqueur
    encore nul : le `sync()` suivant reprendrait le chemin dérivé et les reprendrait en silence.
    Documenter la troisième méthode en tête de classe, à côté de `sync()` et `revoke()`, avec son
    contrat de flush — c'est là que vivent déjà les deux autres.
  * **Pourquoi pas `revoke()` puis un ajout** : au deuxième choix (le geste « revoir les
    ingrédients ») ou sur un ingrédient sans conditionnement, `takeBack()` ne fait que
    *programmer* la suppression de la contribution, `findMergeable()` retrouve la même ligne, une
    seconde contribution est persistée pour la même paire, et Doctrine exécute tous les INSERT
    avant les DELETE — violation de `uniq_meal_grocery_contribution`, 500, et la ligne peut
    partir en cascade. La boucle de `sync()` s'en protège déjà en retirant de `$items` la ligne
    qu'elle vient de relâcher (`MealGrocerySync.php:86-106`, commenté). On hérite du garde-fou.
  * Une ligne déjà **cochée** n'est pas reprise (`takeBack()` le refuse — le shopper l'a
    rapportée) : elle reste, et la ligne conditionnée s'ajoute par-dessus.
* `MealGrocerySync::sync()` — le chemin dérivé — pour un repas dont `groceryChoiceMadeAt` n'est
  pas nul, **ne dérive rien et ne reprend rien** : il saute `wantedLines()` *et* la boucle de
  reprise, et ne garde que l'`adjust()` des contributions qu'il tient, **à quantité inchangée**,
  pour décaler leur `buyAfter` quand le repas est déplacé (MAG-251). Ce `buyAfter` se recalcule
  depuis le `shelfLifeDays` du produit de la ligne
  (`$contribution->getGroceryItem()->getProduct()`, **nullable** : pas de produit, pas de durée
  de conservation, donc `buyAfter` nul — sans ce garde PHPStan refuse) et le jour du repas — la
  même valeur que celle que `wantedLines()` fournissait, qui n'existe plus ici. Les deux moitiés
  comptent :
  sans la première, la contribution en unité d'achat n'est pas dans `$wanted` —
  `keyOfContribution()` compare produit **+ unité** — et tombe dans la boucle « ce que le repas
  ne veut plus », donc les lignes choisies disparaissent en silence ; sauter seulement
  `wantedLines()` serait pire, un `$wanted` vide reprenant **toutes** les contributions. Quatre
  appelants : `CreateMealHandler`, `UpdateMealHandler`, `UpdateRecipeHandler`,
  `GroceryGenerationService`. `revoke()` ne change pas — supprimer un repas qui a choisi reprend
  bien ses lignes.
* Les deux endpoints diffusent la liste par Mercure (`GroceryListBroadcaster` : un handler de
  repas rend le repas, le middleware ne voit jamais la liste).
* Outil MCP `manage_meal_groceries(action: preview|add, mealId, ingredientIds?)` (décision 7).
* `manage_meals` : `create` renvoie l'aperçu dans sa réponse, pour que Maggie enchaîne.

Tests dus : `MealGroceryPreviewControllerTest` et `MealGroceryItemsControllerTest`
(401, 400 sur un repas inconnu et sur un ingrédient qui n'est pas dans les recettes du
repas, happy path avec état DB, Mercure, Elasticsearch, repas d'un autre utilisateur en 404,
`groceryChoiceMadeAt` horodaté et **non écrivable par un client**) ·
`MealGroceryChoiceTest` (arrondi au-dessus, fusion d'une ligne existante, contribution
créée avec la quantité conditionnée, ingrédient sans conditionnement en unité de recette,
**les lignes dérivées reprises avant le choix**, **une ligne cochée conservée**) ·
`MealGrocerySyncTest` étendu (**un repas qui a choisi n'est plus dérivé, par chacun des quatre
appelants** ; **ses lignes choisies ne sont pas reprises non plus** ; **déplacer un repas qui a
choisi décale le `buyAfter` de ses lignes sans toucher à leur quantité** ; un repas sans choix
garde le comportement actuel ; **choisir deux fois de suite ne viole pas
`uniq_meal_grocery_contribution`**) · `MealGroceryToolsTest` (`preview`, `add`, pas
d'utilisateur lié, `action` inconnue) · contrat régénéré (deux endpoints, un outil, et un champ
sur `Meal`).

Dépend de : tâche 3 (qui dépend elle-même de la tâche 2). Porte une migration :
`lock:migration`.

## Tâche 6 : choisir les ingrédients à l'ajout d'un repas, sur le web

* Deuxième étape dans la boîte « Ajouter un repas » de `MealsWeekView.tsx` : après le choix
  des recettes, l'aperçu — une case par ingrédient, la quantité conditionnée, une puce
  `Stock faible` / `Rupture`, cochée d'office seulement pour ces deux états (décision 9).
* « Tout cocher » / « tout décocher », un bouton « Ajouter aux courses » et un bouton
  « Plus tard » qui crée le repas sans rien ajouter.
* La liste de courses ouverte dans un autre onglet reçoit les lignes par Mercure.
* Une erreur de l'API laisse le repas créé et dit ce qui n'a pas été ajouté.

Tests dus : composant — rendu de l'aperçu, cases d'office selon l'état de stock, cocher et
décocher, envoi du sous-ensemble, « Plus tard » n'appelle pas l'ajout, erreur de l'API.

Dépend de : tâche 5.

## Tâche 7 : choisir les ingrédients à l'ajout d'un repas, sur le mobile

* Après `MealCreateDialog`, un **écran plein écran** de choix des ingrédients (décision 8) :
  une case par ingrédient, la quantité conditionnée, l'état de stock, « tout cocher ».
* Le ViewModel garde l'aperçu, la sélection, l'envoi et l'erreur.
* Lien profond vers l'écran depuis la notification ? Non — hors périmètre.

Tests dus : ViewModel — une transition par état (chargement, aperçu chargé, sélection
changée, envoi en cours, succès, erreur) · test d'écran Compose sur la JVM (les cases
d'office, le compteur d'articles à ajouter).

Dépend de : tâche 5.

## Tâche 8 : la fin des courses remet les produits en stock

* `EndErrandHandler` : une ligne **cochée** portant un produit remet ce produit à
  `En stock`. Une ligne retirée sans être achetée ne change rien (`RemoveGroceryItemHandler`
  n'est pas touché).
* Les produits changés sont publiés par Mercure, un par un, et indexés.
* `end_errand` répond `restockedProducts` : ce qui est repassé en stock.
* Le filtrage par magasin existant est conservé : seules les lignes du magasin terminé
  comptent.
* Admin et mobile : la fin de course annonce combien de produits sont repassés en stock.

Tests dus : `EndErrandControllerTest` étendu (401, happy path avec état DB des produits,
Mercure par produit, Elasticsearch, ligne non cochée inchangée, filtrage par magasin) ·
`GroceryToolsTest` (`end_errand` rend `restockedProducts`) · composant admin et ViewModel
mobile sur le message.

Dépend de : tâche 3.

## Tâche 9 : les quantités de la liste comptent en conditionnements

* Les contrôles − / + de [MAG-291](https://linear.app/meven/issue/MAG-291/liste-de-courses-changer-la-quantite-dun-article-directement-sur-la)
  comptent **1 conditionnement** par appui pour un produit qui en a un, et la ligne se lit
  « 2 paquets ». Sans conditionnement : le pas libre défini par MAG-291.
* Ajouter un produit déjà présent et non coché sur la liste **monte sa quantité** au lieu de
  créer une deuxième ligne — côté API (`AddGroceryItemHandler`), pour que la conversation et
  les deux clients en profitent. Clé de fusion : **même produit et même unité**, comme
  `findMergeable()` ; une ligne cochée n'est jamais fusionnée.
* `get_grocery_list` : la ligne porte l'unité d'achat et l'état de stock du produit. Sans ça,
  Maggie ne sait dire ni « 2 paquets » ni « en rupture » quand on lui demande la liste, et le
  canal conversation resterait en retard sur le web et le mobile (décision 7).

Tests dus : `AddGroceryItemControllerTest` (fusion d'une ligne existante non cochée, pas de
fusion sur une ligne cochée, pas de fusion sur une autre unité) · `GroceryToolsTest`
(`add_grocery_item` fusionne, `get_grocery_list` rend l'unité d'achat et l'état de stock) ·
composant admin (+ et − d'un conditionnement, libellé « 2 paquets ») · ViewModel mobile (une
transition par geste) · contrat régénéré.

Dépend de : tâche 2 et **MAG-291**.

## Tâche 10 : basculer — l'ajout automatique des ingrédients s'arrête

Le dernier ticket du projet. Il éteint `MealGrocerySync` côté dérivation.

* `MealGrocerySync::sync()` **généralise à tous les repas** la forme que la tâche 5 avait donnée
  aux repas qui ont choisi : plus aucune dérivation depuis les recettes, plus aucune reprise, et
  seul l'`adjust()` des contributions tenues, à quantité inchangée, pour décaler leur `buyAfter`
  quand le repas est **déplacé** (décisions 4, 4 bis et 5). Le test sur `groceryChoiceMadeAt`
  disparaît donc de `sync()`, mais le champ reste — il sert à l'écran « revoir les
  ingrédients » et à `generate_grocery_list` pour nommer les repas sans choix. `revoke()` ne
  change pas : supprimer un repas reprend ses lignes. Aucune migration destructive.
* Changer les recettes d'un repas ne touche plus la liste ; l'écran du repas propose
  « revoir les ingrédients à acheter », qui rouvre l'aperçu de la tâche 5.
* `GroceryGenerationService::generate()` garde les articles récurrents, abandonne l'ajout
  automatique des ingrédients, et rend les repas de la période dont les ingrédients n'ont
  pas encore été choisis. `generate_grocery_list` les nomme, pour que Maggie enchaîne.
* Mettre à jour `agent/fixtures/fake-llm/44-grocery-generate.yaml` et `47-meal-plan.yaml` :
  Maggie propose le choix au lieu d'annoncer un ajout.

Tests dus : `MealGrocerySyncTest` réécrit (planifier un repas n'ajoute rien, même sans choix ;
un repas déplacé décale `buyAfter` de ses lignes choisies ; un repas supprimé les reprend ;
changer les recettes ne touche pas la liste) · `GenerateGroceryListToolTest` (récurrents
ajoutés, ingrédients non ajoutés, repas sans choix nommés, deux générations de suite ne
doublent rien) · `e2e/web/tests/meals-grocery.spec.ts` mis à jour · contrat régénéré.

Dépend de : tâches 5, 6, 7, 8, 9.

## Tests

Par unité touchée, depuis `agent-os/standards/global/testing.md` :

| Unité | Tests |
|---|---|
| `Packaging` (service) | chaque famille d'unités, `ceil`, égalité stricte, conversion impossible, pas de conditionnement |
| `Product` (entité) | Mercure Create/Update/Delete, document Elasticsearch, validation taille/unité |
| `ProductApiTest` | 401, 400 (taille sans unité, état inconnu, `restockQuantity` négatif), happy path + état DB, Mercure, ES |
| `manage_products`, `manage_ingredients` | pas d'utilisateur lié, action inconnue, `create`, `update`, `clear`, isolation utilisateur |
| `update_stock` | pas d'utilisateur lié, produit inconnu, état inconnu, happy path + DB + Mercure + ES, réappro éteint, fusion d'une ligne, isolation |
| `GET /meals/{id}/grocery_preview` | 401, repas inconnu, repas d'un autre utilisateur, happy path (états de stock, quantités conditionnées, `suggested`) |
| `POST /meals/{id}/grocery_items` | 401, 400 (ingrédient hors recettes), happy path + DB + contributions + Mercure + ES, fusion, lignes dérivées remplacées, ligne cochée conservée, `groceryChoiceMadeAt` horodaté et non écrivable par un client |
| `manage_meal_groceries` | pas d'utilisateur lié, action inconnue, `preview`, `add` |
| `EndErrandHandler` | ligne cochée → produit `En stock`, ligne non cochée inchangée, filtrage par magasin, Mercure par produit |
| `AddGroceryItemHandler` | fusion d'une ligne non cochée, pas de fusion sur une ligne cochée, pas de fusion sur une autre unité |
| `get_grocery_list` | la ligne rend l'unité d'achat et l'état de stock du produit |
| `MealGrocerySync` | tâche 5 : un repas qui a choisi n'est ni dérivé ni repris, par chacun des quatre appelants ; déplacé, il décale le `buyAfter` de ses lignes sans toucher aux quantités ; un repas sans choix garde le comportement actuel ; choisir deux fois ne viole pas l'index unique · tâche 10 : planifier n'ajoute rien, repas déplacé, repas supprimé, recettes changées |
| `MealGrocerySync::syncChoice()` | reprend ce que le repas ne veut plus, ajuste ce qu'il garde, ajoute ce qui manque, ne recrée jamais une contribution dont la suppression n'est que programmée, et **ne flushe pas** (l'appelant commite lignes et marqueur en une transaction) |
| `GroceryGenerationService` | récurrents à échéance, ingrédients non ajoutés, repas sans choix nommés |
| `ProductForm.tsx`, `ProductList.tsx` | rendu, saisie, aperçu du conditionnement, puce d'état, erreur |
| Étape de choix (admin) | rendu, cases d'office, cocher/décocher, envoi, « Plus tard », erreur API |
| Ligne de liste (admin) | + et − d'un conditionnement, libellé « 2 paquets », refus API |
| `ProductViewModel`, ViewModel de choix, `GroceryViewModel` (mobile) | une transition d'état par test |
| Écrans Compose (produit, choix des ingrédients) | Robolectric sur la JVM : puce d'état, cases d'office, compteur |
| `fake-llm` | un scénario par phrase du propriétaire (stock faible, rupture, choix des ingrédients, génération) |
| Contrat | régénéré à chaque tâche qui change la forme de l'API ou la liste des outils |

Aucune correction de bug dans ce projet : pas de test de reproduction à écrire rouge
d'abord. MAG-291 porte le seul risque de régression connu (une quantité changée à la main
écrasée par la synchro des repas), et la tâche 10 l'éteint à la source.

## Parcours e2e

**Étend [MAG-101](https://linear.app/meven/issue/MAG-101/parcours-e2e-recettes-menus-et-courses)** — recettes, menus et courses (décision 11).

### Conditionnement et stock — `e2e/web/tests/grocery-stock.spec.ts` (tâches 2 et 3)

```
Étant donné un produit « Riz » sans conditionnement ni état de stock renseigné
Quand j'ouvre sa fiche dans l'admin
Alors il est annoncé « En stock »
Quand je lui donne le conditionnement « paquet » de 500 g,
     une quantité de réapprovisionnement de 2 paquets et le réappro automatique
Alors sa fiche affiche « paquet de 500 g »
  et la liste des produits le montre « En stock »
Quand je le passe à « Rupture »
Alors la liste des produits le montre « Rupture »
  et le second onglet ouvert le voit changer sans rechargement (Mercure)
```

### Choix des ingrédients — `e2e/web/tests/meals-grocery-choice.spec.ts` (tâches 5, 6)

```
Étant donné la recette « Riz au curry » avec 300 g de riz et 1 bocal de légumes
  et « Riz » en « Rupture » avec un paquet de 500 g
  et « Légumes pour couscous » en « En stock » avec un bocal
Quand je planifie « Riz au curry » mardi soir
Alors l'étape de choix des ingrédients s'ouvre
  et « Riz » est coché d'office, avec « 1 paquet » et la puce « Rupture »
  et « Légumes pour couscous » est décoché, avec « 1 bocal »
Quand je valide « Ajouter aux courses »
Alors la liste de courses porte « Riz — 1 paquet » et rien pour les légumes
  et le second onglet reçoit la ligne sans rechargement (Mercure)
```

### Maggie et le stock — `e2e/web/tests/grocery-stock-chat.spec.ts` (tâche 4)

```
Étant donné « Riz » en « En stock », paquet de 500 g, 2 paquets de réappro, réappro automatique
  et « Légumes pour couscous » en « En stock », bocal, sans réappro automatique
  et un couscous planifié vendredi soir
Quand je dis à Maggie « je n'ai presque plus de riz »
Alors `update_stock` a tourné et a réussi
  et « Riz » est en « Stock faible »
  et la liste de courses porte « Riz — 2 paquets »
Quand je dis à Maggie « je n'ai plus de légumes pour couscous »
Alors « Légumes pour couscous » est en « Rupture »
  et rien n'est ajouté à la liste
  et Maggie signale le couscous planifié vendredi
```

Scénarios `agent/fixtures/fake-llm/49-stock-low-rice.yaml` (`match: user_contains:
"presque plus de riz"`) et `51-stock-out-couscous.yaml` (`user_contains: "plus de légumes
pour couscous"`).

### Fin des courses — `e2e/web/tests/grocery-errand.spec.ts` étendu (tâche 8)

```
Étant donné « Riz » en « Rupture » et 2 paquets sur la liste
  et « Lessive » en « Stock faible » et 1 bidon sur la liste, non coché
Quand je coche « Riz » puis termine la course
Alors « Riz » est repassé « En stock »
  et « Lessive » est toujours « Stock faible »
  et Maggie annonce « 1 produit repassé en stock »
```

### Quantités en conditionnements — `e2e/web/tests/grocery-list.spec.ts` étendu (tâche 9)

```
Étant donné « Riz » à 1 paquet sur la liste
Quand j'appuie deux fois sur +
Alors la ligne affiche « 3 paquets », y compris après rechargement
Quand j'ajoute « Riz » une seconde fois depuis le champ d'ajout
Alors la liste porte une seule ligne de riz, à 4 paquets
```

### Bascule — `e2e/web/tests/meals-grocery.spec.ts` mis à jour (tâche 10)

```
Étant donné la recette « Riz au curry »
Quand je planifie un repas et que je choisis « Plus tard »
Alors la liste de courses est vide
Quand je demande à Maggie de générer la liste de la semaine
Alors les articles récurrents à échéance y sont
  et aucun ingrédient de repas n'a été ajouté tout seul
  et Maggie nomme le repas dont les ingrédients ne sont pas choisis
Quand je supprime le repas après avoir choisi ses ingrédients
Alors ses lignes quittent la liste
```

### Mobile — `e2e/mobile/flows/10-meal-ingredient-choice.yaml` (tâche 7)

Planifier un repas depuis la semaine, l'écran plein écran de choix s'ouvre, décocher un
ingrédient, valider, et retrouver la liste de courses avec le seul ingrédient coché.
Le temps réel des courses est déjà couvert par `08-grocery-realtime.yaml`.

### Évaluation sur le modèle réel

`task e2e:eval` : un scénario de prompt-lab « je n'ai presque plus de riz » vérifie que
Maggie choisit `update_stock` plutôt que `add_grocery_item`, et qu'elle annonce ce qu'elle a
ajouté sans inventer une quantité. Le jugement de ton ne se teste pas dans un parcours.

## Definition of Done

Par ticket, pas une fois pour le projet :

- [ ] Tests unitaires et d'intégration ci-dessus, verts
- [ ] Parcours e2e écrit et exécutable (Playwright / Maestro), avec son scénario fake-LLM
      quand Maggie est dans la boucle
- [ ] Contrat régénéré et commité quand la forme de l'API ou la liste des outils change
- [ ] `task fix:all` vert avant le push
- [ ] CI verte sur un PR qui lie le ticket
- [ ] Spec fonctionnelle du module Courses et guide utilisateur à jour dans Linear
      (ADR-006) — le conditionnement et le stock pour les tâches 2 et 3, le choix des
      ingrédients pour 5 à 7, la fin des courses pour 8, la bascule pour 10

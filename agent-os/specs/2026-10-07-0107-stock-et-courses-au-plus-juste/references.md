# Références — Stock et courses au plus juste

Ce que le projet étend, et ce qu'il faut lire avant d'y toucher.

## Le lien repas ↔ liste de courses

* **Emplacement** : `api/modules/cookbook/src/Service/MealGrocerySync.php`,
  `api/modules/cookbook/src/Entity/MealGroceryContribution.php`,
  `api/modules/cookbook/src/Repository/MealGroceryContributionRepository.php`
* **Pertinence** : c'est la pièce que le projet remplace à moitié. Chaque ligne qu'un repas
  touche est enregistrée en contribution ; `sync()` rend la liste conforme à ce que le repas
  demande *maintenant*, `revoke()` reprend tout, les deux sont idempotents.
* **À reprendre** : la contribution comme trace du choix (décision 4) ; le calcul de
  `buyAfter` depuis `shelfLifeDays` et le jour du repas ; `findMergeable()`, qui refuse de
  fusionner dans une ligne cochée ; `isHeldByAnotherMeal()`, qui empêche qu'un repas reprenne
  la part d'un autre.
* **À ne pas casser** : l'asymétrie de flush documentée en tête de classe (`sync()` flushe,
  `revoke()` non, pour que supprimer un repas et ses courses soit une seule transaction) ;
  la lecture des lignes par le dépôt et non par `$list->getItems()` (un proxy fantôme peut
  rendre une collection vide alors que la base a des lignes). **`syncChoice()` (décision 4 bis)
  se range du côté de `revoke()`** : elle ne flushe pas, pour que les lignes choisies et
  `groceryChoiceMadeAt` soient commités ensemble. La tête de classe documente les trois.
* **Quatre appelants de `sync()`**, et il faut les connaître avant de changer son
  comportement : `CreateMealHandler:92`, `UpdateMealHandler:66`, `UpdateRecipeHandler:91` et
  `GroceryGenerationService:47`. `DeleteMealHandler:34` appelle `revoke()`.
* **Le piège de la clé** : `wantedLines()` et `keyOfContribution()` comparent **produit +
  unité**. Une contribution posée en unité d'achat (`pack`) n'est donc jamais dans `$wanted`
  d'un `sync()` qui dérive en unités de recette (`g`) : elle tombe dans la boucle « ce que le
  repas ne veut plus » et se fait reprendre, en silence. C'est la raison d'être de
  `Meal::$groceryChoiceMadeAt` (décision 4 bis). Et sauter seulement `wantedLines()` serait
  pire : un `$wanted` vide reprend **toutes** les contributions du repas.
* **Le piège de la paire**, commenté en toutes lettres aux lignes 86-106 : `takeBack()` ne fait
  que *programmer* la suppression d'une contribution, et Doctrine exécute tous les INSERT avant
  les DELETE — donc recréer une contribution pour la même paire `(repas, ligne)` dans la même
  passe viole `uniq_meal_grocery_contribution`. `sync()` s'en protège en retirant de `$items` la
  ligne qu'il vient de relâcher. `syncChoice()` (décision 4 bis) **réutilise cette boucle**
  plutôt qu'un `revoke()` suivi d'un ajout, précisément pour hériter de ce garde-fou.

## La génération de la liste

* **Emplacement** : `api/modules/cookbook/src/Service/GroceryGenerationService.php`,
  `api/modules/cookbook/src/Mcp/Tool/GenerateGroceryListTool.php`
* **Pertinence** : la tâche 10 lui retire l'ajout automatique des ingrédients et lui laisse
  les articles récurrents.
* **À reprendre** : `alreadyOnTheList()` pour la fusion ; `isDueOn()` sur la fréquence ; et
  surtout l'indexation Elasticsearch **après** le commit, jamais avant — un worker lisant la
  ligne en avance de la transaction indexerait l'ancienne date pour toujours, et la
  collection est servie depuis Elasticsearch, donc la dérive serait silencieuse.

## La fin des courses

* **Emplacement** : `api/modules/grocery/src/MessageHandler/EndErrandHandler.php`,
  `api/modules/grocery/src/Controller/EndErrandController.php`,
  `api/modules/grocery/src/Mcp/Tool/EndErrandTool.php`
* **Pertinence** : le geste existant sur lequel la remise en stock se greffe (tâche 8).
* **À reprendre** : le filtrage par magasin ; la lecture des lignes par le dépôt.

## Le produit

* **Emplacement** : `api/modules/grocery/src/Entity/Product.php`,
  `api/modules/cookbook/src/Entity/Ingredient.php`, `api/modules/grocery/src/Enum/Unit.php`
* **Pertinence** : `Ingredient extends Product` en héritage table unique — un
  conditionnement et un état de stock posés sur `Product` valent pour les ingrédients
  aussi. `Unit` n'a pas de `jar`, que le propriétaire cite en exemple.
* **À reprendre** : `MercurePayloadFilterTrait` et la table de correspondance du troisième
  argument de `filterPayload()` pour les relations ; `IndexedField` / `IndexedRelation` pour
  le document Elasticsearch ; `#[ApiProperty(writable: false)]` sur l'id, parce que l'admin
  renvoie l'enregistrement avec son IRI.

## Un jeu d'outils MCP de référence

* **Emplacement** : `api/modules/grocery/src/Mcp/Tool/ManageProductsTool.php`,
  `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`
* **Pertinence** : la forme exacte attendue d'un outil — `action` en `match`,
  `requireUser()`, `MissingMcpUserException` et `HandlerFailedException` attrapées, `clear`
  pour vider un champ facultatif, écriture par le bus et lecture par le dépôt.
* **À reprendre** : `GroceryToolsTest` est la référence citée par le standard de test
  (401, 400, happy path avec état DB, `assertMercureUpdatePublished`,
  `assertElasticsearchIndexDispatched`, `resetMercure()` et `resetAsyncTransport()` dans
  `setUp()`).

## Un article récurrent : le précédent d'un réapprovisionnement

* **Emplacement** : `api/modules/grocery/src/Entity/RecurringGroceryItem.php`
* **Pertinence** : le seul mécanisme du dépôt qui remet des choses sur la liste tout seul.
  `lastAddedAt` est en `#[ApiProperty(writable: false)]` — posé par la génération, jamais par
  l'utilisateur. Le réappro automatique de la tâche 4 suit le même principe : l'état de stock
  est écrit par l'utilisateur, la ligne ajoutée est une conséquence.

## Les écrans

* **Emplacement** : `admin/src/modules/grocery/ProductForm.tsx`,
  `admin/src/modules/grocery/GroceryListView.tsx`,
  `admin/src/modules/cookbook/MealsWeekView.tsx`,
  `mobile/app/src/main/java/com/maggie/app/ui/screens/cookbook/meals/MealCreateDialog.kt`,
  `mobile/app/src/main/java/com/maggie/app/ui/screens/grocery/ProductListScreen.kt`
* **Pertinence** : `ProductForm` a déjà le découpage en `FormSection` avec une phrase
  d'explication par section — les deux nouvelles sections s'y rangent. `MealsWeekView` porte
  la boîte « Ajouter un repas » que la tâche 6 étend d'une étape ; `MealCreateDialog` son
  équivalent mobile, que la tâche 7 prolonge d'un écran plein écran.

## Les parcours e2e existants

* **Emplacement** : `e2e/web/tests/meals-grocery.spec.ts`,
  `e2e/web/tests/grocery-errand.spec.ts`, `e2e/web/tests/grocery-list.spec.ts`,
  `e2e/mobile/flows/08-grocery-realtime.yaml`, `agent/fixtures/fake-llm/4*.yaml`
* **Pertinence** : les quatre pas que le projet change sont déjà couverts. La tâche 10 met
  `meals-grocery.spec.ts` à jour plutôt que d'en écrire un nouveau.
* **À reprendre** : la forme des scénarios `fake-llm` (`match: user_contains`) — sans
  scénario, Maggie répond « [fake-llm] aucun scénario… » et le parcours échoue.

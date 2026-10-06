# Stock et courses au plus juste — cadrage

Projet : [Stock et courses au plus juste](https://linear.app/meven/project/stock-et-courses-au-plus-juste-43486eca7228) (P-MAG-25)
Ticket de cadrage : [MAG-290](https://linear.app/meven/issue/MAG-290/cadrer-la-spec-stock-et-courses-au-plus-juste-et-la-decouper-en)

## Périmètre

L'ajout automatique de tous les ingrédients d'un repas à la liste de courses ne colle pas
à la réalité : une partie est déjà dans les placards, et on achète en paquets, pas au gramme.
Le projet remplace cet ajout automatique par six comportements, repris tels que le
propriétaire les a validés le 7 oct. :

1. **À l'ajout d'un repas, on choisit** les ingrédients qui partent sur la liste.
2. **Un conditionnement par produit**, et un ingrédient ajouté depuis un repas va sur la
   liste en conditionnements, **arrondi au-dessus**.
3. **Un état de stock par produit** (`En stock` · `Stock faible` · `Rupture`, `En stock`
   par défaut), une **quantité de réapprovisionnement** en conditionnements, et un
   **réapprovisionnement automatique** oui/non.
4. **Maggie met le stock à jour en conversation**, et réapprovisionne quand c'est demandé.
5. **La fin des courses remet en stock** les produits achetés, sur le geste existant.
6. **Les quantités de la liste comptent en conditionnements.**

Hors périmètre, décidé par le propriétaire : les quantités précises en stock (grammes
restants), les dates de péremption, plusieurs conditionnements par produit. Et hors
périmètre de ce cadrage : le code.

## Ce qui existe déjà

* `Ingredient extends Product` (héritage table unique) : un conditionnement et un état de
  stock posés sur `Product` valent **pour les ingrédients aussi**, sans duplication.
* `MealGrocerySync` (MAG-116) : chaque ligne qu'un repas touche est enregistrée en
  `MealGroceryContribution`. `sync()` rend la liste conforme à ce que le repas demande
  *maintenant*, `revoke()` reprend tout. Les deux sont idempotents.
* `GroceryGenerationService` : la génération fait un complément, pas un reset — les repas
  passent par `MealGrocerySync`, les articles récurrents ne reviennent qu'à échéance.
* `EndErrandHandler` : supprime les lignes cochées, garde les autres.
* `GroceryItemSource` : `recipe` · `recurring` · `manual`.
* `Unit` : `g` `kg` `ml` `l` `cl` `piece` `bunch` `can` `bottle` `pack` `sachet`. **Pas de
  `jar`**, alors que le propriétaire cite le bocal en exemple.
* `Product` porte déjà `defaultUnit`, `shelfLifeDays`, magasin habituel et magasin de repli.
* Les contrôles − / + sur la ligne arrivent avant le projet :
  [MAG-291](https://linear.app/meven/issue/MAG-291/liste-de-courses-changer-la-quantite-dun-article-directement-sur-la).

## Décisions

Chacune est aussi postée en commentaire de décision sur MAG-290.

### 1. Migration des produits existants : conditionnement vide, comportement actuel

* **Dilemme** — que devient un produit existant, qui n'a pas de conditionnement ?
* **Options** — (a) les trois colonnes nullables, vides, l'ingrédient part en unité de la
  recette ; (b) deviner un conditionnement depuis `defaultUnit` ; (c) rendre le
  conditionnement obligatoire.
* **Choix** — (a).
* **Pourquoi** — MAG-290 propose cette réponse, elle ne coûte aucune migration de données
  et le propriétaire renseigne les conditionnements au fil de ses courses. `defaultUnit` et
  `shelfLifeDays` sont déjà facultatifs sur `Product`, dans le même esprit : un produit sans
  conditionnement reste utilisable.

### 2. Forme du conditionnement : une unité d'achat, et ce qu'elle contient

* **Dilemme** — « paquet de 500 g » et « bocal » n'ont pas la même forme : comment écrire
  un conditionnement d'un seul tenant ?
* **Options** — (a) un texte libre ; (b) `packagingUnit` + `packagingSize` +
  `packagingSizeUnit`, les deux derniers facultatifs ; (c) un multiplicateur seul.
* **Choix** — (b). « paquet de 500 g » = (`pack`, 500, `g`) ; « bocal » = (`jar`, vide, vide).
  `Jar = 'jar'` est ajouté à `Unit`.
* **Pourquoi** — c'est la seule forme qui permette l'arrondi au-dessus : 300 g de riz ÷ 500 g
  = 0,6 → 1 paquet. Un texte libre n'est pas calculable, et un multiplicateur seul ne sait
  pas dire « paquet ». Un conditionnement sans contenu (`jar`) reste utile : il nomme ce
  qu'on achète et compte les lignes à 1, 2, 3 bocaux.

### 3. Conversion impossible → un conditionnement, et on le dit

* **Dilemme** — la recette demande 200 g, le conditionnement est un bocal sans contenu
  connu, ou une unité d'une autre famille (3 pièces contre un paquet de 500 g).
* **Options** — (a) ajouter 1 conditionnement ; (b) retomber sur l'unité de la recette ;
  (c) refuser.
* **Choix** — (a), en indiquant dans l'aperçu que la quantité n'est pas calculée.
* **Pourquoi** — la liste doit porter ce qu'on achète (un bocal), pas ce que la recette
  consomme ; et − / + (MAG-291) permet de monter à 2 en un geste. Refuser bloquerait l'ajout
  d'un repas sur un détail de paramétrage. Les conversions tenues : `g` ↔ `kg`,
  `ml` ↔ `cl` ↔ `l`, et l'égalité stricte pour les unités comptées.

### 4. Les `MealGroceryContribution` restent, et deviennent la trace du choix

* **Dilemme** — l'ajout devient explicite ; que deviennent les contributions à la bascule ?
* **Options** — (a) les garder, mais naître du choix des ingrédients au lieu des recettes ;
  (b) les supprimer, un ingrédient choisi devenant une ligne manuelle sans lien ;
  (c) les garder et empiler le nouveau système par-dessus.
* **Choix** — (a). Aucune migration de données : les lignes existantes décrivent de vraies
  courses. `sync()` ne dérive plus les lignes des recettes ; `revoke()` ne change pas.
* **Pourquoi** — MAG-116 existe précisément parce qu'un repas déplacé ou annulé laissait des
  lignes mortes. Couper le lien repas ↔ ligne ramènerait ce bug. La contribution porte déjà
  `quantity` et `unit` : elle portera la quantité en conditionnements.

### 4 bis. La bascule se fait repas par repas, marquée sur le repas

* **Dilemme** — « l'ajout automatique reste en place jusqu'à la bascule » n'est pas tenable tel
  quel : `CreateMealHandler`, `UpdateMealHandler`, `UpdateRecipeHandler` et
  `GroceryGenerationService` appellent tous `MealGrocerySync::sync()`. Planifier un repas
  mettrait donc déjà ses ingrédients en unités de recette, **et** la contribution créée par le
  choix (en unité d'achat) ne serait pas dans `$wanted` au `sync()` suivant — `keyOfContribution()`
  compare produit **+ unité** — donc elle tomberait dans la boucle « ce que le repas ne veut
  plus » et serait reprise. Les lignes choisies disparaîtraient à la première modification du
  repas ou de la recette, en silence.
* **Options** — (a) marquer le choix sur le repas, et le choix **remplace la source de
  `$wanted`** sans toucher à l'algorithme de réconciliation ; (b) éteindre la dérivation dès le
  ticket d'API, avant les écrans ; (c) laisser les deux chemins empiler leurs lignes et décrire
  les doublons.
* **Choix** — (a), en deux pièces précises :
  1. `Meal` gagne `groceryChoiceMadeAt: ?\DateTimeImmutable`, nullable et
     `#[ApiProperty(writable: false)]` — posé par l'endpoint, jamais par un client, comme
     `RecurringGroceryItem::$lastAddedAt`.
  2. `MealGrocerySync` gagne **`syncChoice(Meal $meal, array $chosenLines)`** : la **même**
     réconciliation que `sync()` — reprendre ce que le repas ne veut plus, ajuster ce qu'il
     garde, ajouter ce qui manque — avec `$wanted` venant du choix au lieu des recettes.
     **Elle ne flushe pas**, comme `revoke()` : l'endpoint pose `groceryChoiceMadeAt` et **un
     seul flush** commite les lignes et le marqueur ensemble. Deux transactions laisseraient, en
     cas d'échec entre les deux, des lignes conditionnées sur la liste avec un marqueur encore
     nul — le `sync()` suivant reprendrait alors le chemin dérivé et les reprendrait en silence,
     exactement ce que cette décision existe pour empêcher. C'est tenable : dans une seule
     passe, `isHeldByAnotherMeal()` exclut `$own` et l'index unique n'autorise qu'une
     contribution par paire, donc rien dans la boucle n'a besoin du flush intermédiaire dont la
     boucle de génération de `sync()` a besoin. La troisième méthode se documente en tête de
     classe à côté des deux autres, avec son contrat de flush.
  3. `sync()`, lui, pour un repas dont `groceryChoiceMadeAt` n'est pas nul, **ne dérive rien et
     ne reprend rien** : il saute `wantedLines()` *et* la boucle de reprise, et ne garde que
     l'`adjust()` des contributions qu'il tient, à quantité inchangée, pour décaler leur
     `buyAfter` quand le repas est déplacé. Ce `buyAfter` se recalcule depuis le
     `shelfLifeDays` du produit de la ligne (`$contribution->getGroceryItem()->getProduct()`,
     **nullable** — pas de produit, pas de durée de conservation, donc `buyAfter` nul) et le jour
     du repas — la même valeur que celle que `wantedLines()` fournissait, donc aucune date ne
     change de sens. `revoke()` ne change pas.
* **Pourquoi** — c'est la seule option sans fenêtre de régression : un repas qui n'a pas encore
  choisi garde exactement le comportement d'aujourd'hui, que le propriétaire a validé, et un
  repas qui a choisi ne porte que ses lignes conditionnées, **tout en suivant encore le repas
  quand on le déplace** (MAG-251). (b) couperait l'ajout depuis le web et le mobile avant que les
  écrans existent ; (c) mettrait deux lignes de riz sur la liste et rendrait faux les critères
  d'acceptation des trois tickets de choix.
* **Et surtout, pourquoi réutiliser l'algorithme plutôt que « reprendre puis reposer »** — un
  `revoke()` suivi d'un ajout casse sur le deuxième choix (le geste « revoir les ingrédients »)
  et sur un ingrédient sans conditionnement : `takeBack()` ne fait que *programmer* la
  suppression de la contribution, `findMergeable()` retrouve la même ligne, une seconde
  contribution est persistée pour la même paire, et Doctrine exécute tous les INSERT avant les
  DELETE — violation de `uniq_meal_grocery_contribution`, 500, et la ligne peut partir en
  cascade. `sync()` s'en protège déjà en retirant de `$items` la ligne qu'il vient de relâcher,
  et le commentaire de la classe explique pourquoi. Repartir de cette boucle, c'est hériter du
  garde-fou au lieu de le réécrire. Une ligne **déjà cochée** n'est de toute façon pas reprise
  par `takeBack()` — le shopper l'a rapportée — donc elle reste et la ligne conditionnée
  s'ajoute par-dessus : c'est visible, pas silencieux.

### 5. Changer les recettes d'un repas ne touche plus la liste tout seul

* **Dilemme** — après la bascule, que fait `update` d'un repas dont on change les recettes ?
* **Options** — (a) ne rien faire, et proposer « revoir les ingrédients à acheter » ;
  (b) re-synchroniser automatiquement, comme aujourd'hui ; (c) demander à chaque fois.
* **Choix** — (a).
* **Pourquoi** — le principe du projet est « on choisit ». Re-synchroniser remettrait
  d'office sur la liste ce que le propriétaire avait décoché. Supprimer un repas continue
  en revanche de reprendre ses lignes (`revoke()`), parce que là il n'y a rien à choisir.

### 6. Quand Maggie signale le stock faible : au moment du choix des ingrédients

* **Dilemme** — à la planification, dans la semaine, ou les deux ?
* **Options** — (a) au moment du choix des ingrédients, et dans la réponse de
  `update_stock` quand un repas de la semaine utilise le produit ; (b) une proaction
  quotidienne ; (c) une notification.
* **Choix** — (a).
* **Pourquoi** — la description du projet le tranche déjà deux fois : « Maggie signale un
  produit en stock faible ou en rupture, **au moment de ce choix** », et « si le couscous
  est planifié dans la semaine, Maggie le signale **au moment du choix des ingrédients** ».
  L'ajout dans la réponse de `update_stock` ne coûte qu'une lecture et répond au cas que le
  propriétaire décrit (« je n'ai plus de légumes pour couscous » sans réappro automatique).
  Proactions et notifications : hors périmètre, elles supposent un canal et un réglage.

### 7. Les outils MCP

* **Dilemme** — quels outils ajouter, lesquels modifier ?
* **Choix** —
  * **`update_stock`** (nouveau, grocery) : `product` (id ou nom) + `state`. Pose l'état,
    déclenche le réappro quand il est automatique, et répond ce qu'il a ajouté plus les
    repas des 7 prochains jours qui utilisent le produit.
  * **`manage_meal_groceries`** (nouveau, cookbook) : `action: preview | add`. `preview`
    rend les ingrédients du repas avec conditionnement, quantité conditionnée, état de
    stock et une suggestion ; `add` ajoute le sous-ensemble choisi.
  * **`manage_products`** et **`manage_ingredients`** (modifiés) : conditionnement, état de
    stock, quantité de réappro, réappro automatique, et `clear` pour les vider.
  * **`manage_meals`** (modifié) : `create` ne synchronise plus la liste et renvoie l'aperçu,
    pour que Maggie enchaîne sur le choix sans un deuxième appel.
  * **`end_errand`** (modifié) : répond les produits repassés `En stock`.
  * **`get_grocery_list`** (modifié, dans le ticket des quantités) : la ligne porte l'unité
    d'achat et l'état de stock du produit — sans ça, Maggie ne sait dire ni « 2 paquets » ni
    « en rupture » quand on lui demande la liste, et le canal conversation resterait en retard
    sur le web et le mobile.
* **Pourquoi** — `agent-os/standards/api/mcp-tools.md` impose un jeu d'outils par entité, et
  tout ce que le propriétaire veut faire à la voix doit passer par un outil. Un seul outil
  pour l'aperçu et l'ajout d'un repas, parce que Maggie fait toujours les deux à la suite :
  deux outils lui coûteraient un aller-retour de plus.

### 8. L'écran de choix des ingrédients

* **Dilemme** — où vit le choix, sur le web, le mobile et en conversation ?
* **Choix** —
  * **Web** : une deuxième étape dans la boîte « Ajouter un repas » existante
    (`MealsWeekView.tsx`) — une case par ingrédient, la quantité conditionnée
    (« 1 paquet (500 g) »), une puce `Stock faible` / `Rupture`.
  * **Mobile** : la même étape, **en plein écran** après `MealCreateDialog`.
  * **Conversation** : pas d'écran. Maggie lit les ingrédients avec leur état et demande
    lesquels ajouter ; la réponse déclenche `manage_meal_groceries` en `add`.
* **Pourquoi** — greffer sur le geste existant évite un deuxième chemin pour planifier un
  repas. Le plein écran sur mobile suit la règle du dépôt (« l'autocomplétion mobile ouvre
  une recherche plein écran, jamais un menu en ligne »), et une liste à cocher dans une
  boîte de dialogue mobile serait illisible. En conversation, le texte suffit : les cartes
  d'approbation servent les actions que Maggie retient, pas une saisie.

### 9. Case cochée par défaut : seulement quand le stock le justifie

* **Dilemme** — à l'ouverture du choix, quels ingrédients sont cochés ?
* **Options** — (a) décoché pour un produit `En stock`, coché pour `Stock faible` et
  `Rupture` ; (b) tout coché, comme l'ajout automatique d'aujourd'hui ; (c) tout décoché.
* **Choix** — (a).
* **Pourquoi** — le besoin du propriétaire est « beaucoup d'ingrédients sont déjà dans les
  placards » : tout cocher reproduirait le défaut qu'on corrige. Et c'est ce qui fait payer
  l'état de stock : le tenir à jour rend la liste juste d'office. Un seul booléen à
  retourner si l'usage dit le contraire.

### 10. Le réapprovisionnement a sa propre source de ligne

* **Dilemme** — avec quelle `GroceryItemSource` une ligne ajoutée par le réappro
  automatique arrive-t-elle ?
* **Options** — (a) `restock`, nouvelle valeur ; (b) `manual` ; (c) `recurring`.
* **Choix** — (a) `GroceryItemSource::Restock = 'restock'`. La colonne est une chaîne : pas
  de migration.
* **Pourquoi** — `manual` ferait croire que le propriétaire l'a écrite, `recurring` qu'elle
  revient toute seule chaque semaine. La liste groupe déjà par origine, et `MealGrocerySync`
  décide de supprimer ou de vider une ligne selon sa source : lui mentir casserait cette
  règle.

### 11. Parcours e2e : tout étend MAG-101

* **Choix** — chaque ticket du projet étend
  [MAG-101](https://linear.app/meven/issue/MAG-101/parcours-e2e-recettes-menus-et-courses)
  (recettes, menus et courses), y compris ceux qui passent par la conversation.
* **Pourquoi** — MAG-101 couvre déjà « planifier un repas, qui génère les articles de
  courses » et « cocher un article en magasin, puis terminer la course » : le projet change
  exactement ces pas. MAG-99 porte la conversation en tant que telle, pas ce que la
  conversation produit dans les courses.

## Contexte

* **Visuels** — aucun. Le ticket et le projet n'ont pas de pièce jointe.
* **Références de code** — voir `references.md`.
* **Alignement produit** — `agent-os/product/mission.md` et `roadmap.md` : Maggie tient le
  quotidien, et la liste de courses est l'un de ses usages les plus fréquents. Le projet ne
  contredit rien ; il rend le module Courses utilisable sans retouche manuelle après chaque
  repas planifié.

## Standards appliqués

Référencés, pas recopiés (`/inject-standards`) — voir `standards.md`.

| Standard | Pourquoi |
|---|---|
| `global/testing` | Définition de « terminé » : s'applique à tous les tickets du projet. |
| `api/entities` | Trois champs de conditionnement, trois de stock et un enum sur `Product`, un horodatage sur `Meal` : **trois** migrations additives. |
| `api/mcp-tools` | Un outil nouveau par entité touchée, quatre outils modifiés. |
| `api/testing` | Tests d'outils MCP et de publication Mercure exigés. |
| `global/real-time` | L'état de stock et les quantités doivent arriver sur les autres écrans. |
| `admin/react-admin` | Formulaire produit, deuxième étape de la boîte « Ajouter un repas ». |
| `admin/testing` | Tests Vitest des composants admin touchés. |
| `mobile/android-app` | Modèle `Product`, écran plein écran de choix des ingrédients. |
| `mobile/screen-tests` | Où vit la vérification : Compose sur la JVM pour la logique d'écran. |
| `mobile/testing` | Une transition d'état par test de ViewModel. |
| `agent/architecture` | Scénarios `fake-llm` pour `update_stock` et le choix des ingrédients. |
| `global/e2e-environment` | Les parcours tournent dans la stack e2e, modèle simulé. |
| `global/worktree-checks` | Les vérifications de chaque ticket passent par `task wt:*`. |

## Reste ouvert

Rien qui bloque l'implémentation.

Un point n'a pas pu être fait dans cette session : la **spec fonctionnelle du module
Courses** est un document Linear rattaché à l'initiative I-7, et le jeton MCP de l'agent n'a
pas la portée « initiative » (403 sur `get_initiative`, document absent de `list_documents`,
404 sur le slug). [MAG-303](https://linear.app/meven/issue/MAG-303) porte le paragraphe exact
à y ajouter, et le choix entre l'ajouter à la main ou élargir le jeton ; il est en
`needs-human`, parce qu'aucun agent ne peut le prendre en l'état.

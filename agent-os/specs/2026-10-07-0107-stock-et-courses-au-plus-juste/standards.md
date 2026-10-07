# Standards — Stock et courses au plus juste

Les standards sont **référencés**, pas copiés : une copie divergerait du jour au lendemain.
Index : `agent-os/standards/index.yml`, injection à la demande par `/inject-standards`.

## Toujours

| Standard | Pourquoi il s'applique ici |
|---|---|
| @agent-os/standards/global/testing.md | La définition de « terminé ». Chaque tâche du plan nomme les tests qu'elle doit. Une seule exemption dans ce projet : la tâche 1 (cadrage), `N/A — aucun comportement utilisateur`. |
| @agent-os/standards/global/agent-guard-rails.md | **Trois** migrations additives (tâches 2, 3 et 5), aucune destructive, aucun `policy.yaml` ni chemin `infra/`. Le découpage en neuf tickets existe pour que chacun tienne sous 800 lignes hors tests : une tâche qui déborde se re-découpe, elle ne se fait pas valider à la main. Le garde-fou `oversize` exclut les tests, les lockfiles, `api/contract/` et `agent-os/specs/`. |
| @agent-os/standards/global/worktree-checks.md | `task fix:all` avant chaque push, `task wt:up` puis `task wt:test:api -- --testsuite Grocery` (ou `Cookbook`) pour la boucle de test, `task wt:down` avant le PR. |

## API

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/api/entities.md | `Product` gagne six champs et un enum ; `Ingredient extends Product` en hérite sans duplication (héritage table unique). `Meal` gagne `groceryChoiceMadeAt`, en `#[ApiProperty(writable: false)]` comme `RecurringGroceryItem::$lastAddedAt` — posé par l'endpoint, jamais par un client. Commandes Messenger + handlers + processors existants à étendre, pas à doubler. Trois migrations additives, sérialisées par `lock:migration`. |
| @agent-os/standards/api/mcp-tools.md | Deux outils neufs (`update_stock`, `manage_meal_groceries`) et quatre modifiés : écriture par le bus, lecture par le dépôt, `McpUserContext::requireUser()`, `MissingMcpUserException` attrapée, filtrage par utilisateur. **Un module absent de `discovery.scan_dirs` dans `api/config/packages/mcp.yaml` a ses outils silencieusement masqués** — `grocery` et `cookbook` y sont déjà, mais le `tools/list` réel se vérifie quand même. |
| @agent-os/standards/api/testing.md | 401, 400, happy path avec état DB, `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched()`. `resetMercure()` et `resetAsyncTransport()` dans `setUp()`. Référence : `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`. |
| @agent-os/standards/global/real-time.md | L'état de stock d'un produit, une ligne ajoutée par le réappro, une quantité en conditionnements : trois changements que le second écran doit voir sans rechargement. `GroceryListBroadcaster` est le chemin obligé quand le handler rend autre chose que la liste. |

## Admin et mobile

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/admin/react-admin.md | `ProductForm.tsx`, `ProductList.tsx`, la deuxième étape de la boîte « Ajouter un repas » de `MealsWeekView.tsx`, les contrôles de quantité de `GroceryListView.tsx`, abonnement Mercure. |
| @agent-os/standards/admin/testing.md | Par composant : rendu initial, l'interaction pour laquelle il existe, état d'erreur ou vide. |
| @agent-os/standards/mobile/android-app.md | `Product.kt` et `GroceryItem.kt`, l'écran de choix des ingrédients, les dépôts, Koin. **L'autocomplétion mobile ouvre une recherche plein écran, jamais une liste déroulante en ligne** — c'est aussi la raison du plein écran pour le choix des ingrédients (décision 8). |
| @agent-os/standards/mobile/testing.md | MockK, une transition d'état par test sur chaque ViewModel. |
| @agent-os/standards/mobile/screen-tests.md | Ce qui se vérifie en Compose sur la JVM (puce d'état, cases cochées d'office, compteur) et ce qui demande Maestro (le parcours complet de planification). |

## Agent

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/agent/architecture.md | Les scénarios `fake-llm` passent par la vraie boucle d'outils et le vrai MCP : un scénario manquant fait répondre « [fake-llm] aucun scénario… » et casse le parcours. |
| @agent-os/standards/agent/testing.md | pytest en mode async, HTTP simulé avec respx, fixtures de `conftest`. |

## E2E

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/global/e2e-environment.md | Une stack par worktree, `APP_ENV=e2e`, connexion de test `POST /api/auth/e2e/login`, fixtures déterministes par `task e2e:seed`, modèle simulé (`LLM_PROVIDER=fake`). Les parcours de ce projet ont besoin de produits aux états de stock connus : les ajouter aux fixtures ancrées. |

## Contrat

`api/contract/` (MAG-104) : le document OpenAPI, la liste des outils MCP, les topics Mercure
et les réponses enregistrées. Six champs sur `Product`, un champ sur `Meal`, deux outils neufs,
deux endpoints neufs et deux outils modifiés les font échouer — c'est le but. Lire l'échec, puis
`UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` et commiter le diff.
`DtoContractTest` côté mobile vérifie chaque champ déclaré contre une réponse enregistrée.

**Le booléen de réappro : une seule orthographe, tous les canaux.** Symfony sérialise `isFoo()`
en `foo` sur REST, alors que la charge Mercure épelle la propriété telle quelle : une propriété
nommée `isAutoRestock` dirait `autoRestock` en HTTP et `isAutoRestock` sur Mercure, la
divergence que `isCushion` paie déjà (gotcha de `CLAUDE.md`). Le dépôt a tranché l'inverse —
`Finance\Entity\Category::$passiveIncome`, nommé sans `is` exprès, avec la raison en commentaire.
MAG-293 nomme donc la propriété **`autoRestock`** (getter `isAutoRestock()`, colonne
`is_auto_restock` si on veut) pour que les deux canaux disent `autoRestock`.
`MercureTopicContractTest` épingle les quatre divergences héritées : on n'en ajoute pas une
cinquième. Détails : `api/contract/README.md`.

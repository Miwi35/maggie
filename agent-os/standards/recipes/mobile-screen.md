# Add or rework an Android screen

**When**: a `Feature` that adds or redraws a screen of `mobile/app/` from a mockup, followed « à la lettre ». Not for a field added to an existing DTO (→ `mobile-api-field.md`), not for the admin (→ `admin-screen.md`): a remark on one channel does not extend to the other.
**Reference**: MAG-555 (`9c823853e`): `mobile/app/src/main/java/com/maggie/app/data/repository/PlacementRepository.kt`, `ui/screens/finance/PlacementViewModel.kt`, `ui/screens/finance/PlacementListScreen.kt`, and `mobile/app/src/test/java/com/maggie/app/ui/screens/finance/PlacementListScreenTest.kt`.

## Steps
1. Open the mockup the ticket's `## Cadrage` links: one canvas per format and state. List every label, pill, count, order and state; that list is the screen test.
2. DTO in `data/model/<Thing>.kt` — see `mobile-api-field.md` (it owes its `DtoContractTest` case).
3. `data/api/MaggieApiService.kt`: one `suspend fun` per call, Ktor `client.get/post/patch/delete("$baseUrl/api/…")`, `accept(ContentType("application", "ld+json"))`, `withoutPagination()` for a full list, `.orRefusal()` (a refusal becomes `ApiRefusalException` with the API's `detail`), `.body<ApiCollection<T>>().member` for a collection.
4. `data/repository/<Thing>Repository.kt`: a class over `MaggieApiService`, each method `Result<T>` via `runCatching { … }`. No state, no mapping to UI text.
5. `ui/screens/<module>/<Thing>ViewModel.kt`: `class …ViewModel(repository, mercureService: MercureService, authRepository: AuthRepository) : ViewModel()`; a `data class <Thing>UiState` (`isLoading`, `hasLoaded`, `error`, `message`, the data); `private val _uiState = MutableStateFlow(…)`, `val uiState: StateFlow<…>`; `init { refresh(); subscribeToMercure() }`. Mercure: `mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.<CONST>))`, `.catch {}`, then patch on `action` with `else → refresh()` (`GroceryViewModel`), or `.coalescedAndRechecked()` before a reload of an Elasticsearch-served list. French error constants in the companion (`MESSAGE_LOAD`).
6. New topic: `const val <CONST> = "<collection>"` in `data/mercure/MercureTopics.kt` and in its `SUBSCRIBED` set.
7. `ui/screens/<module>/<Thing>Screen.kt`: `@Composable fun <Thing>Screen(viewModel: <Thing>ViewModel, onBack: …, onOpen…: …)` — the ViewModel is a parameter, never `koinViewModel()` inside; `viewModel.uiState.collectAsState()`; header `ModuleBreadcrumb` (module › Section ▾, `ui/components/ModuleBreadcrumb.kt`) or the module's top bar (`FinanceTopBar`); `EmptyState`, `ErrorSnackbar`, `CircularProgress` in place / `PullToRefreshBox`; `rememberAppLayout()` for compact/medium/expanded; colours from `MaterialTheme` / `MaggieTokens`.
8. `ui/UiTags.kt`: a `const val` per node a test or flow addresses, applied with `Modifier.testTag(UiTags.…)`; a `Dialog` or `ModalBottomSheet` declares its own `Modifier.uiTagRoot()`.
9. Koin, in `MaggieApp.kt`'s `appModule`: `single { <Thing>Repository(get()) }`, `viewModel { <Thing>ViewModel(get(), get(), get()) }` — one `get()` per constructor parameter.
10. Navigation, `ui/navigation/NavGraph.kt`: a `data object <Thing> : Screen("<route>", "<Label>")` (an argument as `?${DeepLinks.ARG_ID}={…}` with `navArgument`), a private `@Composable <Thing>Page(navController)` that gets `koinViewModel()` and wires the callbacks, a `composable(Screen.<Thing>.route, deepLinks = DeepLinks.forRoute(…))`. Deep link in `DeepLinks.kt`; module sub-view in its `*Navigation.kt` (e.g. `FinanceNavigation.kt`).
11. Journey only for what a JVM cannot prove (`mobile/screen-tests.md`): `e2e/mobile/flows/<nn>-<feature>.yaml` — follow `e2e-mobile-journey.md`.

## Tests owed
- `mobile/app/src/test/java/com/maggie/app/ui/screens/<module>/<Thing>ViewModelTest.kt`: one test per transition (load ok, load error, each action, a Mercure event) asserting `uiState`, `StandardTestDispatcher` + `Dispatchers.setMain`, MockK repository (`coEvery`).
- `…/<Thing>ScreenTest.kt`: `@RunWith(AndroidJUnit4::class)`, `@get:Rule val compose = ScreenRule()`, the real ViewModel over a mocked repository (`mercureService.subscribe` → `emptyFlow()`); every mockup label, the order (`assertTopToBottom`), empty, error, navigation callbacks, the formats the mockup draws.
- Navigation: a case in `ui/navigation/DeepLinksTest.kt` / the module's `*NavigationTest.kt`.
- Run: `task wt:test:mobile -- --tests 'com.maggie.app.ui.screens.<module>.<Thing>*'` (add `--tests 'com.maggie.app.ui.navigation.*'` and `--tests 'com.maggie.app.data.mercure.*'` when touched), `task e2e:mobile:lint` for a flow. Never `./gradlew`, Maestro or an emulator.

## Standards
[mobile/android-app](../mobile/android-app.md) · [mobile/screen-tests](../mobile/screen-tests.md) · [mobile/testing](../mobile/testing.md) · [global/ux](../global/ux.md) · [global/design-system](../global/design-system.md) · [global/real-time](../global/real-time.md) · [global/testing](../global/testing.md)

## Pitfalls
- **Inside a `ModalBottomSheet` or `ModalDrawerSheet`, `performClick()` is swallowed**: use `tap()` (`screentest/ScreenInteractions.kt`). Do not `clip` a container holding buttons: a clipped layer swallows taps under Robolectric — `background(color, shape)`.
- Never assert an indefinite spinner (the test clock never settles): assert the state around it.
- A topic not built by `MercureTopics` (a literal `{userId}`, a missing `/users/` scope) connects and receives nothing — MAG-138; `MercureTopicsContractTest` checks it.
- A form opened as its own route shares the list's ViewModel: `koinViewModel(viewModelStoreOwner = navController.previousBackStackEntry ?: entry)` (`PlacementFormPage`), or the list does not see what was saved.
- A screen state lost on rotation or fold (MAG-263): keep it in the ViewModel or `rememberSaveable`, never a plain `remember`.
- `error = MESSAGE_LOAD`, never `it.message ?: …` (the technical message reaches the screen; `RecurringGroceryViewModel` and `PastErrandsViewModel` do it — out of standard). Interface in French and *vous*, no explanatory text, touch targets ≥ 48 dp (`IconButton`, `minimumInteractiveComponentSize()`), autocomplete as a full-screen search.

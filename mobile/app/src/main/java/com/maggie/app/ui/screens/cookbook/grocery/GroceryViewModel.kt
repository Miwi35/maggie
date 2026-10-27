package com.maggie.app.ui.screens.cookbook.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.api.EndErrandRemainingItem
import com.maggie.app.data.api.ReorderEntry
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.AisleSummary
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.PurchaseSuggestion
import com.maggie.app.data.model.RecipeNeedResponse
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToInt
import java.time.format.DateTimeFormatter

/** The items of one aisle of a store; [aisle] null is « Sans rayon ». */
data class AisleSection(val aisle: AisleSummary?, val items: List<GroceryItem>)

/**
 * A store's items, aisle by aisle in the order of the way through it, then by their own
 * position; « Sans rayon » last (MAG-493). [sections] is empty when none of them has an
 * aisle: the store is then shown without aisle headers, as before.
 */
data class StoreGroup(
    val store: Store?,
    val items: List<GroceryItem>,
    val sections: List<AisleSection> = emptyList(),
)

/** Aisle position first (no aisle last), then the item's own position. */
internal val AISLE_ORDER: Comparator<GroceryItem> = compareBy<GroceryItem>(
    { it.aisle == null },
    { it.aisle?.position ?: Int.MAX_VALUE },
    { it.aisle?.id ?: "" },
    { it.position },
)

/** One store's items, sorted aisle by aisle, with their sections when at least one item has an aisle. */
internal fun storeGroupOf(store: Store?, items: List<GroceryItem>): StoreGroup {
    val sorted = items.sortedWith(AISLE_ORDER)
    if (sorted.none { it.aisle != null }) return StoreGroup(store, sorted)
    val sections = mutableListOf<AisleSection>()
    for (item in sorted) {
        val last = sections.lastOrNull()
        if (last != null && last.aisle?.id == item.aisle?.id) {
            sections[sections.lastIndex] = last.copy(items = last.items + item)
        } else {
            sections += AisleSection(item.aisle, listOf(item))
        }
    }
    return StoreGroup(store, sorted, sections)
}

data class GroceryUiState(
    val groceryList: GroceryList? = null,
    val storeGroups: List<StoreGroup> = emptyList(),
    /** Items deferred to a later day (`buyAfter` after today), soonest first: shown apart, not checkable. */
    val laterItems: List<GroceryItem> = emptyList(),
    val products: List<Product> = emptyList(),
    val stores: List<Store> = emptyList(),
    val showAddSheet: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelecting: Boolean = false,
    val pendingFinishStoreName: String? = null,
    val pendingFinishItems: List<EndErrandRemainingItem> = emptyList(),
    /** Products the finished errand put back in stock, announced in the leftovers sheet. */
    val pendingFinishRestockedCount: Int = 0,
    /** The same announcement when there is no leftover to show it with; shown once, then consumed. */
    val restockedToast: String? = null,
    /** Why a quantity was refused or put back (MAG-291); shown once, then dismissed. */
    val quantityMessage: String? = null,
    /** Each store's aisles, by store id, read when an item's aisle is about to be chosen (MAG-493). */
    val aislesByStore: Map<String, List<Aisle>> = emptyMap(),
    /** Why an item's aisle was not changed; shown once, then dismissed. */
    val aisleMessage: String? = null,
    /** « Souvent pris » (MAG-495): products due again by their purchase rhythm; the banner shows only when there are some. */
    val suggestions: List<PurchaseSuggestion> = emptyList(),
    /** Why adding a suggestion or « Pas besoin » did not go through; shown once, then dismissed. */
    val suggestionMessage: String? = null,
    /** « Pour les recettes de la semaine » (MAG-573): what the week's meals need of the product being added; null while loading or when there is none. */
    val recipeNeed: RecipeNeedResponse? = null,
    /** Why the API refused the line being added; the sheet stays open and shows it. */
    val addError: String? = null,
) {
    // Deferred items count in neither figure: they are not on today's list.
    val checkedCount: Int get() = storeGroups.sumOf { group -> group.items.count { it.checked } }
    val totalCount: Int get() = storeGroups.sumOf { it.items.size }
}

/** « 1 produit repassé en stock » / « 3 produits repassés en stock »; null when nothing was restocked. */
fun restockedProductsMessage(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 produit repassé en stock"
    else -> "$count produits repassés en stock"
}

// A burst of list updates (an errand ends, every line leaves) is one fetch of the suggestions.
internal const val SUGGESTIONS_REFRESH_DELAY_MS = 300L

// Taps on − / + made within this delay are one request carrying the last quantity.
internal const val QUANTITY_SAVE_DELAY_MS = 400L

private val BUY_AFTER_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// The REST API sends a date-time, the Mercure payload a plain date: the first ten
// characters ("yyyy-MM-dd") are the day in both.
private fun buyAfterDate(buyAfter: String?): LocalDate? =
    buyAfter?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/** « dd/MM/yyyy » for a `buyAfter` value, null when it is not a date. */
fun formatBuyAfter(buyAfter: String?): String? = buyAfterDate(buyAfter)?.format(BUY_AFTER_FORMAT)

/** An item put off to a later day: not on today's list, so neither shown among the items to buy nor counted. */
internal fun isDeferred(item: GroceryItem, today: LocalDate): Boolean =
    buyAfterDate(item.buyAfter)?.isAfter(today) == true

/** The items to buy today, by store in the order of the round, each store aisle by aisle (MAG-493). */
internal fun buildStoreGroups(list: GroceryList?, today: LocalDate = LocalDate.now()): List<StoreGroup> {
    if (list == null) return emptyList()

    val grouped = list.items.filter { !isDeferred(it, today) }.groupBy { it.store?.id ?: "__unassigned__" }
    return grouped.values
        .map { items -> storeGroupOf(items.first().store, items) }
        .sortedBy { it.store?.visitOrder ?: Int.MAX_VALUE }
}

/**
 * The list after an update of the grocery lists topic, [adjustItems] being applied to a full payload's items;
 * null when the payload says too little and the list has to be read again.
 */
internal fun GroceryList.withMercureUpdate(
    payload: JsonObject,
    json: Json,
    adjustItems: (List<GroceryItem>) -> List<GroceryItem> = { it },
): GroceryList? = when (payload["action"]?.jsonPrimitive?.contentOrNull) {
    "reorder" -> {
        val patchItems = payload["items"]?.jsonArray
        patchItems?.let {
            val positions = it.associate { el ->
                val obj = el.jsonObject
                obj["id"]!!.jsonPrimitive.content to obj["position"]!!.jsonPrimitive.int
            }
            copy(items = items.map { item -> positions[item.id]?.let { position -> item.copy(position = position) } ?: item })
        }
    }
    "check" -> {
        val itemId = payload["itemId"]?.jsonPrimitive?.contentOrNull
        val checked = payload["checked"]?.jsonPrimitive?.boolean
        if (itemId == null || checked == null) null else copy(items = items.map { if (it.id == itemId) it.copy(checked = checked) else it })
    }
    "remove" -> payload["itemId"]?.jsonPrimitive?.contentOrNull?.let { itemId -> copy(items = items.filter { it.id != itemId }) }
    // Full data payload (create/update): replaces the items.
    else -> payload["items"]?.jsonArray?.let { copy(items = adjustItems(json.decodeFromString<List<GroceryItem>>(it.toString()))) }
}

class GroceryViewModel(
    private val groceryListRepository: GroceryListRepository,
    private val productRepository: ProductRepository,
    private val storeRepository: StoreRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryUiState())
    val uiState: StateFlow<GroceryUiState> = _uiState

    private var mercureJob: Job? = null
    private var suggestionsJob: Job? = null
    private var recipeNeedJob: Job? = null

    // Quantities shown but not yet confirmed by the API, by item id. They hide the
    // older value a Mercure echo or a refresh would bring back mid-burst.
    private val pendingQuantities = mutableMapOf<String, Float>()

    // The item as it was before the current burst of taps, put back if the API refuses:
    // its count, and the wanted quantity a − may have lowered (MAG-573).
    private val quantityBaselines = mutableMapOf<String, GroceryItem>()
    private val quantityJobs = mutableMapOf<String, Job>()
    private val quantitySaves = Mutex()

    init {
        // Loaded once the auth token is available, and again on every sign-in:
        // this ViewModel is built with the nav graph, before the user has signed
        // in on a fresh install, so a single load here left the list empty and
        // the Mercure subscription never opened (no user id yet) until a pull.
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collect { authenticated ->
                    mercureJob?.cancel()
                    mercureJob = null
                    if (authenticated) {
                        refresh()
                        loadProductsAndStores()
                        mercureJob = subscribeToGroceryUpdates()
                    }
                }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun subscribeToGroceryUpdates(): Job {
        return viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            // The hub may not hold what the dropped stream missed: reread the list once it is back.
            launch {
                mercureService.reconnections(MercureTopics.userScoped(userId, MercureTopics.GROCERY_LISTS))
                    .catch { }
                    .collect { refresh(showSpinner = false) }
            }
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.GROCERY_LISTS))
                .catch { /* SSE connection errors — MercureService handles auto-reconnect */ }
                .collect { event ->
                    // Any change to the list can take a product out of the suggestions, or put it back; an
                    // errand's end empties it. The suggestions open no stream of their own: each holds one of
                    // the app's connections to the hub for good, and past MercureService's per-host limit a
                    // recipe sheet's subscription never connects. A snooze or a recurring item made on another
                    // device shows at the next refresh.
                    refreshSuggestions(SUGGESTIONS_REFRESH_DELAY_MS)
                    try {
                        val currentList = _uiState.value.groceryList ?: return@collect
                        val payload = json.parseToJsonElement(event.data).jsonObject
                        val updatedList = currentList.withMercureUpdate(payload, json, ::withPendingQuantities)
                        if (updatedList == null) {
                            refresh()
                        } else {
                            _uiState.value = _uiState.value.copy(
                                groceryList = updatedList,
                                storeGroups = buildStoreGroups(updatedList),
                                laterItems = buildLaterItems(updatedList),
                            )
                        }
                    } catch (_: Exception) {
                        refresh()
                    }
                }
        }
    }

    /** Refetches « Souvent pris »; the latest call wins. A failure keeps what is shown: the banner is a help, not the list. */
    private fun refreshSuggestions(delayMs: Long = 0L) {
        suggestionsJob?.cancel()
        suggestionsJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            groceryListRepository.getSuggestions().onSuccess { suggestions ->
                _uiState.value = _uiState.value.copy(suggestions = suggestions)
            }
        }
    }

    /** One gesture: the product goes on the list as an ordinary manual line, with its usual quantity. */
    fun addSuggestion(suggestion: PurchaseSuggestion) {
        withoutSuggestion(suggestion)
        viewModelScope.launch {
            groceryListRepository.addItem(
                label = suggestion.product.name,
                quantity = suggestion.usualQuantity?.toFloat(),
                unit = suggestion.usualUnit,
            ).onSuccess {
                refresh()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    suggestionMessage = "Erreur : « ${suggestion.product.name} » n'a pas été ajouté",
                )
                refreshSuggestions()
            }
        }
    }

    /** « Pas besoin »: the product leaves the suggestions until it is due again. */
    fun snoozeSuggestion(suggestion: PurchaseSuggestion) {
        withoutSuggestion(suggestion)
        viewModelScope.launch {
            groceryListRepository.snoozeSuggestion(suggestion.product.id).onFailure {
                _uiState.value = _uiState.value.copy(
                    suggestionMessage = "Erreur : « ${suggestion.product.name} » n'a pas été écarté",
                )
            }
            refreshSuggestions()
        }
    }

    fun dismissSuggestionMessage() {
        _uiState.value = _uiState.value.copy(suggestionMessage = null)
    }

    // The row leaves at once; the refetch that follows puts it back if the API refused.
    private fun withoutSuggestion(suggestion: PurchaseSuggestion) {
        _uiState.value = _uiState.value.copy(
            suggestions = _uiState.value.suggestions.filterNot { it.product.id == suggestion.product.id },
        )
    }

    private fun applyItemUpdate(transform: (GroceryItem) -> GroceryItem) {
        val currentList = _uiState.value.groceryList ?: return
        val updatedList = currentList.copy(items = currentList.items.map(transform))
        _uiState.value = _uiState.value.copy(
            groceryList = updatedList,
            storeGroups = buildStoreGroups(updatedList),
            laterItems = buildLaterItems(updatedList),
        )
    }

    /**
     * The screen is shown again (back from another screen, app back in the foreground): the stream
     * may have missed an update meanwhile. Reloads without the pull indicator, and a failure keeps
     * the list on screen.
     */
    fun onScreenResumed() = refresh(showSpinner = false)

    /** The list, and « Souvent pris » with it (a pull refreshes both). */
    fun refresh(showSpinner: Boolean = true) {
        refreshSuggestions()
        viewModelScope.launch {
            if (showSpinner) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val fetched = groceryListRepository.getGroceryList().getOrThrow()
                val list = fetched?.copy(items = withPendingQuantities(fetched.items))
                val groups = buildStoreGroups(list)
                _uiState.value = _uiState.value.copy(
                    groceryList = list,
                    storeGroups = groups,
                    laterItems = buildLaterItems(list),
                    isLoading = false,
                )
            } catch (e: Exception) {
                if (showSpinner) _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    private fun loadProductsAndStores() {
        viewModelScope.launch {
            try {
                val products = productRepository.getProducts().getOrDefault(emptyList())
                val stores = storeRepository.getStores().getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    products = products,
                    stores = stores,
                )
            } catch (_: Exception) {
                // Non-blocking — products/stores are optional for the main list
            }
        }
    }

    fun showAddSheet() {
        _uiState.value = _uiState.value.copy(showAddSheet = true, addError = null)
    }

    fun hideAddSheet() {
        recipeNeedJob?.cancel()
        _uiState.value = _uiState.value.copy(showAddSheet = false, recipeNeed = null, addError = null)
    }

    /**
     * Reads what the next seven days of meals need of the product just chosen in the add sheet. [productId]
     * null (a free label) clears it. A failure leaves none: the sheet works without the box, and the latest call wins.
     */
    fun loadRecipeNeed(productId: String?) {
        recipeNeedJob?.cancel()
        _uiState.value = _uiState.value.copy(recipeNeed = null, addError = null)
        if (productId == null) return
        recipeNeedJob = viewModelScope.launch {
            groceryListRepository.getRecipeNeed(productId).onSuccess { need ->
                _uiState.value = _uiState.value.copy(recipeNeed = need.takeIf { it.recipeNeed != null || it.meals.isNotEmpty() })
            }
        }
    }

    fun addItem(
        label: String,
        quantity: Float?,
        unit: String?,
        storeId: String?,
        storeName: String? = null,
        category: String? = null,
        packagings: Int? = null,
    ) {
        viewModelScope.launch {
            try {
                groceryListRepository.addItem(label, quantity, unit, storeId, storeName, category, packagings).getOrThrow()
                recipeNeedJob?.cancel()
                _uiState.value = _uiState.value.copy(showAddSheet = false, recipeNeed = null, addError = null)
                refresh()
                // Reload stores if a new store may have been created
                if (storeName != null) {
                    loadProductsAndStores()
                }
            } catch (e: Exception) {
                // The sheet stays open on what was typed, with the reason the API gave.
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    addError = e.message ?: "Erreur : « $label » n'a pas été ajouté",
                )
            }
        }
    }

    fun updateItem(
        itemId: String,
        label: String?,
        quantity: Float?,
        unit: String?,
        storeId: String?,
        storeName: String?,
        category: String?,
        packagings: Int? = null,
    ) {
        viewModelScope.launch {
            try {
                groceryListRepository.editItem(itemId, label, quantity, unit, storeId, storeName, category, packagings).getOrThrow()
                refresh()
                // Reload stores/products if store or product may have been created
                if (storeName != null || label != null) {
                    loadProductsAndStores()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun withPendingQuantities(items: List<GroceryItem>): List<GroceryItem> {
        if (pendingQuantities.isEmpty()) return items
        return items.map { item ->
            val pending = item.id?.let { pendingQuantities[it] }
            if (pending != null) item.withPackagings(pending.roundToInt()) else item
        }
    }

    private fun currentItem(itemId: String): GroceryItem? =
        _uiState.value.groceryList?.items?.firstOrNull { it.id == itemId }

    fun incrementQuantity(item: GroceryItem) {
        val itemId = item.id ?: return
        val current = currentItem(itemId)?.takeIf { it.isPackaged } ?: return
        requestQuantity(current, increasedQuantity(current.quantity))
    }

    /** At the minimum, − does nothing: removing a line is the delete gesture. */
    fun decrementQuantity(item: GroceryItem) {
        val itemId = item.id ?: return
        val current = currentItem(itemId)?.takeIf { it.isPackaged } ?: return
        if (!canDecreaseQuantity(current.quantity)) return
        requestQuantity(current, decreasedQuantity(current.quantity))
    }

    /** A number of packagings typed by hand; anything that is not a whole number above zero is refused and nothing is sent. */
    fun setQuantity(item: GroceryItem, text: String) {
        val itemId = item.id ?: return
        val current = currentItem(itemId)?.takeIf { it.isPackaged } ?: return
        val quantity = parseQuantity(text)
        if (quantity == null) {
            _uiState.value = _uiState.value.copy(quantityMessage = "Quantité invalide : saisissez un nombre entier supérieur à 0")
            return
        }
        if (quantity != current.quantity) requestQuantity(current, quantity)
    }

    fun dismissQuantityMessage() {
        _uiState.value = _uiState.value.copy(quantityMessage = null)
    }

    private fun requestQuantity(item: GroceryItem, quantity: Float) {
        val itemId = item.id ?: return
        if (!quantityBaselines.containsKey(itemId)) quantityBaselines[itemId] = item
        pendingQuantities[itemId] = quantity
        applyItemUpdate { if (it.id == itemId) it.withPackagings(quantity.roundToInt()) else it }

        quantityJobs[itemId]?.cancel()
        quantityJobs[itemId] = viewModelScope.launch {
            delay(QUANTITY_SAVE_DELAY_MS)
            // Once the delay is over the request goes out whatever happens to the screen.
            withContext(NonCancellable) { quantitySaves.withLock { sendQuantity(itemId) } }
        }
    }

    private suspend fun sendQuantity(itemId: String) {
        val sent = pendingQuantities[itemId] ?: return
        // A number of packagings: the API keeps the wanted quantity as + leaves it and lowers it when − goes below it.
        val result = groceryListRepository.editItem(itemId = itemId, packagings = sent.roundToInt())
        val stillCurrent = pendingQuantities[itemId] == sent
        if (result.isSuccess) {
            if (stillCurrent) {
                pendingQuantities.remove(itemId)
                quantityBaselines.remove(itemId)
            } else {
                quantityBaselines[itemId]?.let { quantityBaselines[itemId] = it.withPackagings(sent.roundToInt()) }
            }
            return
        }
        val label = currentItem(itemId)?.label ?: "l'article"
        val message = "Erreur : la quantité de « $label » n'a pas été modifiée"
        if (stillCurrent) {
            val baseline = quantityBaselines[itemId]
            pendingQuantities.remove(itemId)
            quantityBaselines.remove(itemId)
            applyItemUpdate {
                if (it.id != itemId || baseline == null) {
                    it
                } else {
                    it.copy(quantity = baseline.quantity, desiredQuantity = baseline.desiredQuantity, desiredUnit = baseline.desiredUnit)
                }
            }
        }
        _uiState.value = _uiState.value.copy(quantityMessage = message)
    }

    /** Reads the store's aisles, for the « Rayon » choice of an item. */
    fun loadAisles(storeId: String) {
        viewModelScope.launch {
            try {
                val aisles = storeRepository.getAisles(storeId).getOrThrow()
                _uiState.value = _uiState.value.copy(aislesByStore = _uiState.value.aislesByStore + (storeId to aisles))
            } catch (_: Exception) {
                // The choice then offers « Sans rayon » and a new aisle only.
            }
        }
    }

    /**
     * Files the item's product under [aisle] in [storeId] — an aisle id, the name of a new
     * aisle, or null for « Sans rayon ». The product's aisles are one list replaced whole, so
     * it is read first and only this store's entry changes (MAG-493).
     */
    fun setItemAisle(item: GroceryItem, storeId: String, aisle: String?) {
        val productId = item.product?.id ?: return
        viewModelScope.launch {
            try {
                val product = productRepository.getProduct(productId).getOrThrow()
                val aisles = product.aisles.filter { it.store != storeId } +
                    listOfNotNull(aisle?.let { ProductAisle(store = storeId, aisle = it) })
                productRepository.updateAisles(productId, aisles).getOrThrow()
                val known = _uiState.value.aislesByStore[storeId].orEmpty()
                if (aisle != null && known.none { it.id == aisle }) loadAisles(storeId)
                refresh()
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(aisleMessage = "Erreur : le rayon de « ${item.label} » n'a pas été modifié")
            }
        }
    }

    fun dismissAisleMessage() {
        _uiState.value = _uiState.value.copy(aisleMessage = null)
    }

    fun toggleItemChecked(item: GroceryItem) {
        viewModelScope.launch {
            try {
                val itemId = item.id ?: return@launch
                val newChecked = !item.checked

                // Optimistic update
                val currentList = _uiState.value.groceryList ?: return@launch
                val updatedItems = currentList.items.map { existing ->
                    if (existing.id == item.id) existing.copy(checked = newChecked) else existing
                }
                val updatedList = currentList.copy(items = updatedItems)
                _uiState.value = _uiState.value.copy(
                    groceryList = updatedList,
                    storeGroups = buildStoreGroups(updatedList),
                    laterItems = buildLaterItems(updatedList),
                )

                // Server call
                groceryListRepository.checkItem(itemId, newChecked)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
                refresh()
            }
        }
    }

    fun deleteItem(item: GroceryItem) {
        viewModelScope.launch {
            try {
                val itemId = item.id ?: return@launch

                // Optimistic update
                val currentList = _uiState.value.groceryList ?: return@launch
                val updatedItems = currentList.items.filter { it.id != item.id }
                val updatedList = currentList.copy(items = updatedItems)
                _uiState.value = _uiState.value.copy(
                    groceryList = updatedList,
                    storeGroups = buildStoreGroups(updatedList),
                    laterItems = buildLaterItems(updatedList),
                )

                // Server call
                groceryListRepository.deleteItem(itemId).getOrThrow()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
                refresh()
            }
        }
    }

    fun moveItem(storeKey: String, fromIndex: Int, toIndex: Int) {
        val currentList = _uiState.value.groceryList ?: return
        val group = _uiState.value.storeGroups.find {
            (it.store?.id ?: "__unassigned__") == storeKey
        } ?: return
        if (fromIndex == toIndex || fromIndex !in group.items.indices || toIndex !in group.items.indices) return

        val reordered = group.items.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        val entries = reordered.mapIndexedNotNull { idx, item ->
            item.id?.let { ReorderEntry(id = it, position = idx) }
        }

        // Optimistic update
        val positionMap = entries.associate { it.id to it.position }
        val updatedItems = currentList.items.map { item ->
            if (item.id != null && item.id in positionMap) {
                item.copy(position = positionMap[item.id]!!)
            } else {
                item
            }
        }
        val updatedList = currentList.copy(items = updatedItems)
        _uiState.value = _uiState.value.copy(
            groceryList = updatedList,
            storeGroups = buildStoreGroups(updatedList),
            laterItems = buildLaterItems(updatedList),
        )

        viewModelScope.launch {
            try {
                groceryListRepository.reorderItems(entries).getOrThrow()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
                refresh()
            }
        }
    }

    fun startSelection(itemId: String) {
        _uiState.value = _uiState.value.copy(
            isSelecting = true,
            selectedIds = setOf(itemId),
        )
    }

    fun toggleSelection(itemId: String) {
        val current = _uiState.value.selectedIds
        val updated = if (itemId in current) current - itemId else current + itemId
        _uiState.value = if (updated.isEmpty()) {
            _uiState.value.copy(isSelecting = false, selectedIds = emptySet())
        } else {
            _uiState.value.copy(selectedIds = updated)
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(isSelecting = false, selectedIds = emptySet())
    }

    fun checkSelectedItems() {
        viewModelScope.launch {
            val currentList = _uiState.value.groceryList ?: return@launch
            val selectedIds = _uiState.value.selectedIds

            // Optimistic update
            val updatedItems = currentList.items.map { item ->
                if (item.id in selectedIds) item.copy(checked = true) else item
            }
            val updatedList = currentList.copy(items = updatedItems)
            _uiState.value = _uiState.value.copy(
                groceryList = updatedList,
                storeGroups = buildStoreGroups(updatedList),
                laterItems = buildLaterItems(updatedList),
                isSelecting = false,
                selectedIds = emptySet(),
            )

            try {
                for (id in selectedIds) {
                    groceryListRepository.checkItem(id, true)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
                refresh()
            }
        }
    }

    fun deleteSelectedItems() {
        viewModelScope.launch {
            val currentList = _uiState.value.groceryList ?: return@launch
            val selectedIds = _uiState.value.selectedIds

            // Optimistic update
            val updatedItems = currentList.items.filter { it.id !in selectedIds }
            val updatedList = currentList.copy(items = updatedItems)
            _uiState.value = _uiState.value.copy(
                groceryList = updatedList,
                storeGroups = buildStoreGroups(updatedList),
                laterItems = buildLaterItems(updatedList),
                isSelecting = false,
                selectedIds = emptySet(),
            )

            try {
                for (id in selectedIds) {
                    groceryListRepository.deleteItem(id).getOrThrow()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
                refresh()
            }
        }
    }

    fun finishStore(storeId: String, storeName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            groceryListRepository.endErrand(storeId)
                .onSuccess { response ->
                    val restocked = response.restockedProducts.size
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingFinishStoreName = if (response.remainingItems.isNotEmpty()) storeName else null,
                        pendingFinishItems = response.remainingItems,
                        pendingFinishRestockedCount = restocked,
                        restockedToast = if (response.remainingItems.isEmpty()) restockedProductsMessage(restocked) else null,
                    )
                    refresh()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
        }
    }

    fun dismissPendingFinish() {
        _uiState.value = _uiState.value.copy(
            pendingFinishStoreName = null,
            pendingFinishItems = emptyList(),
            pendingFinishRestockedCount = 0,
        )
    }

    fun consumeRestockedToast() {
        _uiState.value = _uiState.value.copy(restockedToast = null)
    }

    fun keepPendingItem(itemId: String) {
        val remaining = _uiState.value.pendingFinishItems.filterNot { it.id == itemId }
        _uiState.value = _uiState.value.copy(
            pendingFinishItems = remaining,
            pendingFinishStoreName = if (remaining.isEmpty()) null else _uiState.value.pendingFinishStoreName,
            pendingFinishRestockedCount = if (remaining.isEmpty()) 0 else _uiState.value.pendingFinishRestockedCount,
        )
    }

    fun transferPendingItem(itemId: String, newStoreId: String) {
        viewModelScope.launch {
            groceryListRepository.editItem(itemId = itemId, storeId = newStoreId)
                .onSuccess {
                    keepPendingItem(itemId)
                    refresh()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    private fun buildLaterItems(list: GroceryList?): List<GroceryItem> {
        if (list == null) return emptyList()
        val today = LocalDate.now()
        return list.items
            .filter { isDeferred(it, today) }
            .sortedWith(compareBy({ buyAfterDate(it.buyAfter) }, { it.label }))
    }
}

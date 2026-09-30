package com.maggie.app.ui.screens.cookbook.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.api.EndErrandRemainingItem
import com.maggie.app.data.api.ReorderEntry
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

data class StoreGroup(val store: Store?, val items: List<GroceryItem>)

data class GroceryUiState(
    val groceryList: GroceryList? = null,
    val storeGroups: List<StoreGroup> = emptyList(),
    val products: List<Product> = emptyList(),
    val stores: List<Store> = emptyList(),
    val showAddSheet: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelecting: Boolean = false,
    val pendingFinishStoreName: String? = null,
    val pendingFinishItems: List<EndErrandRemainingItem> = emptyList(),
)

class GroceryViewModel(
    private val groceryListRepository: GroceryListRepository,
    private val productRepository: ProductRepository,
    private val storeRepository: StoreRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryUiState())
    val uiState: StateFlow<GroceryUiState> = _uiState

    init {
        refresh()
        loadProductsAndStores()
        subscribeToGroceryUpdates()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun subscribeToGroceryUpdates() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.GROCERY_LISTS))
                .catch { /* SSE connection errors — MercureService handles auto-reconnect */ }
                .collect { event ->
                    try {
                        val payload = json.parseToJsonElement(event.data).jsonObject
                        val action = payload["action"]?.jsonPrimitive?.contentOrNull

                        when (action) {
                            "reorder" -> {
                                val patchItems = payload["items"]?.jsonArray ?: run { refresh(); return@collect }
                                val positionMap = patchItems.associate { el ->
                                    val obj = el.jsonObject
                                    obj["id"]!!.jsonPrimitive.content to obj["position"]!!.jsonPrimitive.int
                                }
                                applyItemUpdate { item ->
                                    if (item.id in positionMap) item.copy(position = positionMap[item.id]!!) else item
                                }
                            }
                            "check" -> {
                                val itemId = payload["itemId"]?.jsonPrimitive?.contentOrNull ?: run { refresh(); return@collect }
                                val checked = payload["checked"]?.jsonPrimitive?.boolean ?: run { refresh(); return@collect }
                                applyItemUpdate { item ->
                                    if (item.id == itemId) item.copy(checked = checked) else item
                                }
                            }
                            "remove" -> {
                                val itemId = payload["itemId"]?.jsonPrimitive?.contentOrNull ?: run { refresh(); return@collect }
                                val currentList = _uiState.value.groceryList ?: return@collect
                                val updatedList = currentList.copy(items = currentList.items.filter { it.id != itemId })
                                _uiState.value = _uiState.value.copy(
                                    groceryList = updatedList,
                                    storeGroups = buildStoreGroups(updatedList),
                                )
                            }
                            else -> {
                                // Full data payload (create/update) — replace items array
                                val itemsElement = payload["items"]?.jsonArray ?: run { refresh(); return@collect }
                                val items = json.decodeFromString<List<GroceryItem>>(itemsElement.toString())
                                val currentList = _uiState.value.groceryList ?: return@collect
                                val updatedList = currentList.copy(items = items)
                                _uiState.value = _uiState.value.copy(
                                    groceryList = updatedList,
                                    storeGroups = buildStoreGroups(updatedList),
                                )
                            }
                        }
                    } catch (_: Exception) {
                        refresh()
                    }
                }
        }
    }

    private fun applyItemUpdate(transform: (GroceryItem) -> GroceryItem) {
        val currentList = _uiState.value.groceryList ?: return
        val updatedList = currentList.copy(items = currentList.items.map(transform))
        _uiState.value = _uiState.value.copy(
            groceryList = updatedList,
            storeGroups = buildStoreGroups(updatedList),
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val list = groceryListRepository.getGroceryList().getOrThrow()
                val groups = buildStoreGroups(list)
                _uiState.value = _uiState.value.copy(
                    groceryList = list,
                    storeGroups = groups,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
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
        _uiState.value = _uiState.value.copy(showAddSheet = true)
    }

    fun hideAddSheet() {
        _uiState.value = _uiState.value.copy(showAddSheet = false)
    }

    fun addItem(label: String, quantity: Float?, unit: String?, storeId: String?, storeName: String? = null, category: String? = null) {
        viewModelScope.launch {
            try {
                groceryListRepository.addItem(label, quantity, unit, storeId, storeName, category).getOrThrow()
                _uiState.value = _uiState.value.copy(showAddSheet = false)
                refresh()
                // Reload stores if a new store may have been created
                if (storeName != null) {
                    loadProductsAndStores()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
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
    ) {
        viewModelScope.launch {
            try {
                groceryListRepository.editItem(itemId, label, quantity, unit, storeId, storeName, category).getOrThrow()
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
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingFinishStoreName = if (response.remainingItems.isNotEmpty()) storeName else null,
                        pendingFinishItems = response.remainingItems,
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
        )
    }

    fun keepPendingItem(itemId: String) {
        val remaining = _uiState.value.pendingFinishItems.filterNot { it.id == itemId }
        _uiState.value = _uiState.value.copy(
            pendingFinishItems = remaining,
            pendingFinishStoreName = if (remaining.isEmpty()) null else _uiState.value.pendingFinishStoreName,
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

    private fun buildStoreGroups(list: GroceryList?): List<StoreGroup> {
        if (list == null) return emptyList()

        val today = LocalDate.now()
        val visibleItems = list.items.filter { item ->
            val buyAfter = item.buyAfter
            if (buyAfter == null) return@filter true
            try {
                val date = LocalDate.parse(buyAfter)
                !date.isAfter(today)
            } catch (_: Exception) {
                true
            }
        }

        val grouped = visibleItems.groupBy { it.store?.id ?: "__unassigned__" }
        val storeMap = mutableMapOf<String, StoreGroup>()

        for ((key, items) in grouped) {
            val store = items.firstOrNull()?.store
            storeMap[key] = StoreGroup(store = store, items = items.sortedBy { it.position })
        }

        return storeMap.values.sortedBy { it.store?.visitOrder ?: Int.MAX_VALUE }
    }
}

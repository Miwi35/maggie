package com.maggie.app.ui.screens.cookbook.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.GroceryListRepository
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
)

class GroceryViewModel(
    private val groceryListRepository: GroceryListRepository,
    private val productRepository: ProductRepository,
    private val storeRepository: StoreRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryUiState())
    val uiState: StateFlow<GroceryUiState> = _uiState

    init {
        refresh()
        loadProductsAndStores()
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

    fun addItem(label: String, quantity: Float?, unit: String?, storeId: String?) {
        viewModelScope.launch {
            try {
                groceryListRepository.addItem(label, quantity, unit, storeId).getOrThrow()
                _uiState.value = _uiState.value.copy(showAddSheet = false)
                refresh()
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

    fun endErrand(removeItemIds: List<String>) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val currentList = _uiState.value.groceryList ?: return@launch

                // Delete checked items
                for (item in currentList.items) {
                    if (item.checked && item.id != null) {
                        groceryListRepository.deleteItem(item.id)
                    }
                }

                // Delete items the user chose to remove
                for (itemId in removeItemIds) {
                    groceryListRepository.deleteItem(itemId)
                }

                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
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
            storeMap[key] = StoreGroup(store = store, items = items)
        }

        return storeMap.values.sortedBy { it.store?.visitOrder ?: Int.MAX_VALUE }
    }
}

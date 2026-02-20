package com.maggie.app.ui.screens.cookbook.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.repository.GroceryListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

data class GroceryUiState(
    val groceryLists: List<GroceryList> = emptyList(),
    val selectedList: GroceryList? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class GroceryViewModel(
    private val groceryListRepository: GroceryListRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryUiState())
    val uiState: StateFlow<GroceryUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val lists = groceryListRepository.getGroceryLists().getOrThrow()
                _uiState.value = _uiState.value.copy(
                    groceryLists = lists,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun selectList(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val list = groceryListRepository.getGroceryList(id).getOrThrow()
                _uiState.value = _uiState.value.copy(selectedList = list, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedList = null)
    }

    fun toggleItemChecked(listId: String, item: GroceryItem) {
        viewModelScope.launch {
            try {
                val currentList = _uiState.value.selectedList ?: return@launch
                val updatedItems = currentList.items.map { existing ->
                    if (existing.id == item.id) existing.copy(checked = !existing.checked) else existing
                }
                // Optimistic update
                _uiState.value = _uiState.value.copy(
                    selectedList = currentList.copy(items = updatedItems),
                )
                // PATCH to server
                val data = buildJsonObject {
                    putJsonArray("items") {
                        updatedItems.forEach { i ->
                            addJsonObject {
                                i.id?.let { put("id", it) }
                                put("checked", i.checked)
                            }
                        }
                    }
                }
                groceryListRepository.updateGroceryList(listId, data)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}

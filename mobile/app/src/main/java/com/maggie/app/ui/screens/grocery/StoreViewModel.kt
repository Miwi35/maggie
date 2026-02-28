package com.maggie.app.ui.screens.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.StoreCreateRequest
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class StoreUiState(
    val stores: List<Store> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class StoreViewModel(
    private val storeRepository: StoreRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreUiState())
    val uiState: StateFlow<StoreUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val stores = storeRepository.getStores().getOrThrow()
                _uiState.value = _uiState.value.copy(
                    stores = stores.sortedBy { it.visitOrder },
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun createStore(request: StoreCreateRequest) {
        viewModelScope.launch {
            try {
                storeRepository.createStore(request).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteStore(id: String) {
        viewModelScope.launch {
            try {
                storeRepository.deleteStore(id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    stores = _uiState.value.stores.filter { it.id != id },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}

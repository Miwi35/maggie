package com.maggie.app.ui.screens.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ProductCreateRequest
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store
import com.maggie.app.data.repository.ProductRepository
import com.maggie.app.data.repository.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProductUiState(
    val products: List<Product> = emptyList(),
    val stores: List<Store> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ProductViewModel(
    private val productRepository: ProductRepository,
    private val storeRepository: StoreRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductUiState())
    val uiState: StateFlow<ProductUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val products = productRepository.getProducts().getOrThrow()
                val stores = storeRepository.getStores().getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    products = products,
                    stores = stores,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun createProduct(request: ProductCreateRequest) {
        viewModelScope.launch {
            try {
                productRepository.createProduct(request).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteProduct(id: String) {
        viewModelScope.launch {
            try {
                productRepository.deleteProduct(id).getOrThrow()
                _uiState.value = _uiState.value.copy(
                    products = _uiState.value.products.filter { it.id != id },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}

package com.maggie.app.ui.screens.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ProductCreateRequest
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.CiqualFood
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
    // The aisles of each store by store id, to name a product's aisle and to choose one (MAG-605).
    val aislesByStore: Map<String, List<Aisle>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editor: ProductEditorState? = null,
)

/** The product sheet being edited: [original] as the list holds it, [draft] as typed. */
data class ProductEditorState(
    val original: Product,
    val draft: ProductDraft,
    // The name of the Ciqual food picked this time; the API only gives back the code.
    val ciqualFoodName: String? = null,
    val isSaving: Boolean = false,
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
                val aisles = stores.associate { it.id to storeRepository.getAisles(it.id).getOrDefault(emptyList()) }
                _uiState.value = _uiState.value.copy(
                    products = products,
                    stores = stores,
                    aislesByStore = aisles,
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

    /** The months the owner ticked (MAG-494); the product comes back with the API's `inSeason`. */
    fun updateSeasonMonths(id: String, months: List<Int>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            productRepository.updateSeasonMonths(id, months)
                .onSuccess { updated ->
                    _uiState.value = _uiState.value.copy(
                        products = _uiState.value.products.map { if (it.id == id) updated else it },
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
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

    fun openProduct(id: String) {
        val product = _uiState.value.products.firstOrNull { it.id == id } ?: return
        _uiState.value = _uiState.value.copy(editor = ProductEditorState(product, ProductDraft.from(product)))
    }

    fun closeProduct() {
        _uiState.value = _uiState.value.copy(editor = null)
    }

    fun editDraft(transform: (ProductDraft) -> ProductDraft) = updateEditor { it.copy(draft = transform(it.draft), error = null) }

    /** A shop for the slot; another shop than the one held empties its aisle. The two slots never name the same shop. */
    fun selectStore(slot: StoreSlot, storeId: String?) {
        val editor = _uiState.value.editor ?: return
        val other = if (slot == StoreSlot.PREFERRED) StoreSlot.FALLBACK else StoreSlot.PREFERRED
        if (storeId != null && storeId == editor.draft.storeId(other)) return
        editDraft { it.withStore(slot, storeId) }
        if (storeId != null && storeId !in _uiState.value.aislesByStore) loadAisles(storeId)
    }

    fun selectAisle(slot: StoreSlot, choice: AisleChoice?) = editDraft { it.withAisle(slot, choice) }

    fun pickCiqual(food: CiqualFood?) = updateEditor {
        it.copy(
            draft = it.draft.copy(ciqualAlimCode = food?.alimCode),
            ciqualFoodName = food?.alimNameFr,
            error = null,
        )
    }

    private fun loadAisles(storeId: String) {
        viewModelScope.launch {
            val aisles = storeRepository.getAisles(storeId).getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(aislesByStore = _uiState.value.aislesByStore + (storeId to aisles))
        }
    }

    /** Saves what changed; the sheet stays open, with the reason, when the API turns it down. */
    fun saveProduct() {
        val editor = _uiState.value.editor ?: return
        if (editor.isSaving) return
        editor.draft.problem()?.let { problem ->
            updateEditor { it.copy(error = problem) }
            return
        }
        val patch = editor.draft.toPatch(editor.original)
        if (patch.isEmpty()) {
            closeProduct()
            return
        }
        updateEditor { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            productRepository.updateProduct(editor.original.id, patch)
                .onSuccess { saved ->
                    _uiState.value = _uiState.value.copy(
                        products = _uiState.value.products.map { if (it.id == saved.id) saved else it },
                        editor = null,
                    )
                    if (editor.draft.preferredAisle?.isNew == true) loadAisles(editor.draft.preferredStoreId!!)
                    if (editor.draft.fallbackAisle?.isNew == true) loadAisles(editor.draft.fallbackStoreId!!)
                }
                .onFailure { e ->
                    updateEditor { it.copy(isSaving = false, error = e.message ?: "L'enregistrement a échoué.") }
                }
        }
    }

    private fun updateEditor(transform: (ProductEditorState) -> ProductEditorState) {
        val editor = _uiState.value.editor ?: return
        _uiState.value = _uiState.value.copy(editor = transform(editor))
    }
}

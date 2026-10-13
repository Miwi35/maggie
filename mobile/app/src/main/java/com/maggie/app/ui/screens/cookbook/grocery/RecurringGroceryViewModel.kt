package com.maggie.app.ui.screens.cookbook.grocery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.repository.RecurringGroceryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class RecurringGroceryUiState(
    val items: List<RecurringGroceryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /** The product's recurring item, if it has one: there is at most one per product. */
    fun forProduct(productId: String?): RecurringGroceryItem? =
        productId?.let { id -> items.firstOrNull { it.productId == id } }
}

/**
 * The recurring items, shared by the « Récurrents » tab, the list lines and the product
 * card (MAG-369). Any change made elsewhere reaches Mercure and is re-read whole: a
 * handful of items does not justify applying the differential payload by hand.
 */
class RecurringGroceryViewModel(
    private val repository: RecurringGroceryRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecurringGroceryUiState())
    val uiState: StateFlow<RecurringGroceryUiState> = _uiState

    private var mercureJob: Job? = null

    init {
        // Same lifecycle as GroceryViewModel: built before the first sign-in on a fresh install.
        viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collect { authenticated ->
                    mercureJob?.cancel()
                    mercureJob = null
                    if (authenticated) {
                        refresh()
                        mercureJob = subscribe()
                    }
                }
        }
    }

    private fun subscribe(): Job = viewModelScope.launch {
        val userId = authRepository.getUserId() ?: return@launch
        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.RECURRING_GROCERY_ITEMS))
            .catch { /* MercureService reconnects by itself */ }
            .collect { refresh() }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            repository.getRecurringItems()
                .onSuccess { _uiState.value = _uiState.value.copy(items = sorted(it), isLoading = false) }
                .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: MESSAGE_LOAD) }
        }
    }

    /** null removes the product's recurring item; otherwise it is created, or its frequency changed. */
    fun setFrequency(productId: String, frequency: RecurringFrequency?) {
        viewModelScope.launch {
            val existing = _uiState.value.forProduct(productId)
            when {
                frequency == null -> existing?.let { delete(it) }
                existing != null -> update(existing, frequency)
                else -> createUnlessItAlreadyExists(productId, frequency)
            }
        }
    }

    fun changeFrequency(item: RecurringGroceryItem, frequency: RecurringFrequency) {
        viewModelScope.launch { update(item, frequency) }
    }

    fun remove(item: RecurringGroceryItem) {
        viewModelScope.launch { delete(item) }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // The state may lag behind another device: read the server's before creating, never a second item.
    private suspend fun createUnlessItAlreadyExists(productId: String, frequency: RecurringFrequency) {
        val fresh = repository.getRecurringItems().getOrElse { return fail(it, MESSAGE_SAVE) }
        _uiState.value = _uiState.value.copy(items = sorted(fresh))
        val existing = fresh.firstOrNull { it.productId == productId }
        if (existing != null) {
            update(existing, frequency)
            return
        }
        repository.createForProduct(productId, frequency)
            .onSuccess { _uiState.value = _uiState.value.copy(items = sorted(_uiState.value.items + it), error = null) }
            .onFailure { fail(it, MESSAGE_SAVE) }
    }

    private suspend fun update(item: RecurringGroceryItem, frequency: RecurringFrequency) {
        if (item.frequency == frequency) return
        repository.updateFrequency(item.id, frequency)
            .onSuccess { updated ->
                _uiState.value = _uiState.value.copy(
                    items = sorted(_uiState.value.items.map { if (it.id == updated.id) updated else it }),
                    error = null,
                )
            }
            .onFailure { fail(it, MESSAGE_SAVE) }
    }

    private suspend fun delete(item: RecurringGroceryItem) {
        repository.delete(item.id)
            .onSuccess { _uiState.value = _uiState.value.copy(items = _uiState.value.items.filter { it.id != item.id }, error = null) }
            .onFailure { fail(it, MESSAGE_SAVE) }
    }

    // The previous items stay on screen: nothing was applied before the server answered.
    private fun fail(cause: Throwable, fallback: String) {
        _uiState.value = _uiState.value.copy(error = cause.message ?: fallback)
    }

    private fun sorted(items: List<RecurringGroceryItem>) = items.sortedBy { it.label.lowercase() }

    private companion object {
        const val MESSAGE_LOAD = "Impossible de charger les articles récurrents"
        const val MESSAGE_SAVE = "Le changement n'a pas été enregistré"
    }
}

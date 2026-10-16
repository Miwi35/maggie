package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.sortedSummary
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The candidates of « Virement interne »: the other legs the API found for the open line. */
data class TransferChoice(
    val isLoading: Boolean = true,
    val candidates: List<TransferLeg> = emptyList(),
)

data class ToSortUiState(
    val isLoading: Boolean = true,
    /** True once a read of the pile has succeeded: before it, an empty pile means nothing. */
    val loaded: Boolean = false,
    val pile: List<TransactionToSort> = emptyList(),
    val total: Int = 0,
    val categories: List<Category> = emptyList(),
    /** The line open on a tablet's list; null means the newest, which is what a phone always shows. */
    val selectedId: String? = null,
    val alwaysForMerchant: Boolean = false,
    val isSorting: Boolean = false,
    val choosingCategory: Boolean = false,
    val transfer: TransferChoice? = null,
    val message: String? = null,
    /** A pile that could not be read. */
    val loadError: String? = null,
    /** An answer that was refused or lost: the card stays where it was. */
    val sortError: String? = null,
) {
    val current: TransactionToSort? get() = pile.firstOrNull { it.id == selectedId } ?: pile.firstOrNull()

    val isEmpty: Boolean get() = loaded && pile.isEmpty()

    val canValidate: Boolean get() = !isSorting && current?.suggestion != null
}

/**
 * The lines without a category, one card at a time (MAG-423).
 *
 * The card carries the heading the app would pick and its reason; one tap files the
 * line, then the next one comes. « Toujours pour ce marchand » is an opt-in that
 * falls back to off after every answer: a rule is a lasting decision, never the
 * leftover of the previous card. After each answer the pile is read again from the
 * server, since a rule may have filed lines that were further down.
 */
class ToSortViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToSortUiState())
    val uiState: StateFlow<ToSortUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)

        viewModelScope.launch {
            loadCategories()
            transactionRepository.getToSort()
                .onSuccess { applyPile(it) }
                .onFailure { failure ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadError = failure.message ?: "Chargement impossible.",
                    )
                }
        }
    }

    // The headings do not change between two answers: read once, kept.
    private suspend fun loadCategories() {
        if (_uiState.value.categories.isNotEmpty()) {
            return
        }
        categoryRepository.getCategories()
            .onSuccess { categories ->
                _uiState.value = _uiState.value.copy(categories = categories)
            }
    }

    private fun applyPile(pile: ToSortPile) {
        val state = _uiState.value
        _uiState.value = state.copy(
            isLoading = false,
            loaded = true,
            loadError = null,
            pile = pile.transactions,
            total = pile.total,
            selectedId = state.selectedId?.takeIf { id -> pile.transactions.any { it.id == id } },
        )
    }

    fun select(id: String) {
        _uiState.value = _uiState.value.copy(selectedId = id, alwaysForMerchant = false)
    }

    fun setAlwaysForMerchant(always: Boolean) {
        _uiState.value = _uiState.value.copy(alwaysForMerchant = always)
    }

    /** « Valider »: the suggested heading, as it is. */
    fun validate() {
        val state = _uiState.value
        val suggestion = state.current?.suggestion ?: return
        sortToCategory(suggestion.categoryId)
    }

    fun openCategorySearch() {
        _uiState.value = _uiState.value.copy(choosingCategory = true)
    }

    fun closeCategorySearch() {
        _uiState.value = _uiState.value.copy(choosingCategory = false)
    }

    /** « Autre catégorie », once picked in the full-screen search. */
    fun chooseCategory(categoryId: String) {
        _uiState.value = _uiState.value.copy(choosingCategory = false)
        sortToCategory(categoryId)
    }

    private fun sortToCategory(categoryId: String) {
        val state = _uiState.value
        val line = state.current ?: return
        if (state.isSorting) {
            return
        }

        // Marked before the coroutine starts: two taps land in the same frame, and one
        // answer must not be sent twice.
        _uiState.value = state.copy(isSorting = true, sortError = null, message = null)

        viewModelScope.launch {
            transactionRepository.sortToCategory(line.id, categoryId, state.alwaysForMerchant)
                .onSuccess { afterSort(line, it) }
                .onFailure { failedSort(it) }
        }
    }

    /** « Virement interne »: opens the lines it may be the other leg of. */
    fun openTransfer() {
        val line = _uiState.value.current ?: return
        _uiState.value = _uiState.value.copy(transfer = TransferChoice())

        viewModelScope.launch {
            transactionRepository.getTransferCandidates(line.id)
                .onSuccess { candidates ->
                    _uiState.value = _uiState.value.copy(transfer = TransferChoice(isLoading = false, candidates = candidates))
                }
                // No candidate list is no reason to refuse the answer: « sans contrepartie » stays possible.
                .onFailure {
                    _uiState.value = _uiState.value.copy(transfer = TransferChoice(isLoading = false))
                }
        }
    }

    fun closeTransfer() {
        _uiState.value = _uiState.value.copy(transfer = null)
    }

    /** [counterpartId] null: the other leg is not imported. */
    fun markAsInternalTransfer(counterpartId: String?) {
        val state = _uiState.value
        val line = state.current ?: return
        if (state.isSorting) {
            return
        }

        _uiState.value = state.copy(isSorting = true, transfer = null, sortError = null, message = null)

        viewModelScope.launch {
            transactionRepository.sortAsInternalTransfer(line.id, counterpartId)
                .onSuccess { afterSort(line, it) }
                .onFailure { failedSort(it) }
        }
    }

    // The answered line leaves the pile at once; the read that follows only brings what a rule filed besides.
    private fun afterSort(line: TransactionToSort, result: SortResult) {
        _uiState.value = _uiState.value.copy(
            pile = _uiState.value.pile.filter { it.id != line.id },
            total = result.remaining,
            isSorting = false,
            alwaysForMerchant = false,
            selectedId = null,
            message = sortedSummary(result),
        )
        refresh()
    }

    private fun failedSort(failure: Throwable) {
        _uiState.value = _uiState.value.copy(
            isSorting = false,
            sortError = failure.message ?: "Classement impossible.",
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearSortError() {
        _uiState.value = _uiState.value.copy(sortError = null)
    }
}

package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.sortedSummary
import com.maggie.app.data.repository.CategorizationRuleRepository
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
    /** The line open on a tablet's list; null means the oldest, which is what a phone always shows. */
    val selectedId: String? = null,
    val alwaysForMerchant: Boolean = false,
    /** « Obligatoire » and the type, chosen on the card before the category is: they go with the answer. */
    val qualification: Qualification = Qualification.NONE,
    val isSorting: Boolean = false,
    val isJudging: Boolean = false,
    /** The « Créer un filtre » screen, opened from the card. */
    val ruleDraft: RuleDraftState? = null,
    val choosingCategory: Boolean = false,
    val transfer: TransferChoice? = null,
    val message: String? = null,
    /** A pile that could not be read. */
    val loadError: String? = null,
    /** An answer that was refused or lost: the card stays where it was. */
    val sortError: String? = null,
    /** A satisfaction that was refused or lost: the line keeps the one it had. */
    val judgeError: String? = null,
) {
    val current: TransactionToSort? get() = pile.firstOrNull { it.id == selectedId } ?: pile.firstOrNull()

    val isEmpty: Boolean get() = loaded && pile.isEmpty()

    val canValidate: Boolean get() = !isSorting && current?.suggestion != null
}

/**
 * The lines without a category, oldest first, one card at a time (MAG-423, MAG-458).
 *
 * The card carries the heading the app would pick and its reason; one tap files the
 * line, then the next one comes. The card also holds the sheet's gestures: « Obligatoire »
 * and the type, « Créer un filtre », and the satisfaction of a spend. « Toujours pour ce marchand » is an opt-in that
 * falls back to off after every answer: a rule is a lasting decision, never the
 * leftover of the previous card. After each answer the pile is read again from the
 * server, since a rule may have filed lines that were further down.
 */
class ToSortViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    ruleRepository: CategorizationRuleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToSortUiState())
    val uiState: StateFlow<ToSortUiState> = _uiState

    // The rule is also applied to the history: its other lines leave the pile in the same stroke.
    private val ruleDraftSession = RuleDraftSession(
        scope = viewModelScope,
        ruleRepository = ruleRepository,
        read = { _uiState.value.ruleDraft },
        write = { _uiState.value = _uiState.value.copy(ruleDraft = it) },
        applyToExisting = true,
        onSaved = {
            _uiState.value = _uiState.value.copy(selectedId = null, qualification = Qualification.NONE, message = "Filtre créé")
            refresh()
        },
    )

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)

        viewModelScope.launch {
            loadCategories()
            transactionRepository.getToSort(oldestFirst = true)
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
        _uiState.value = _uiState.value.copy(selectedId = id, alwaysForMerchant = false, qualification = Qualification.NONE)
    }

    fun setQualification(qualification: Qualification) {
        _uiState.value = _uiState.value.copy(qualification = qualification)
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
            val result = transactionRepository.sortToCategory(line.id, categoryId, state.alwaysForMerchant)
                .getOrElse { return@launch failedSort(it) }
            // The sort does not carry « Obligatoire » nor the type: they follow, on the line it just filed.
            if (state.qualification != Qualification.NONE) {
                transactionRepository.setQualification(line.id, categoryId, state.qualification)
                    .onFailure {
                        // The line is filed all the same: it leaves the pile, the owner is told what was lost.
                        failedSort(it)
                        _uiState.value = _uiState.value.copy(selectedId = null, qualification = Qualification.NONE)
                        refresh()
                        return@launch
                    }
            }
            afterSort(line, result)
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
            qualification = Qualification.NONE,
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

    /** « À conserver » / « Superflue »: tapping the verdict the line already has takes it back. */
    fun judge(verdict: String) {
        val state = _uiState.value
        val line = state.current ?: return
        if (!line.judgeable || state.isJudging) {
            return
        }
        val next = if (line.retrospect == verdict) RETROSPECT_UNRATED else verdict

        _uiState.value = state.copy(isJudging = true, judgeError = null)

        viewModelScope.launch {
            transactionRepository.setRetrospect(line.id, next)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isJudging = false,
                        pile = _uiState.value.pile.map { if (it.id == line.id) it.copy(retrospect = next) else it },
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isJudging = false, judgeError = it.message ?: "Jugement impossible.")
                }
        }
    }

    /** « Créer un filtre »: the rule draft, filled from the open line and the qualification chosen on the card. */
    fun openRuleDraft() {
        val state = _uiState.value
        val line = state.current ?: return
        ruleDraftSession.open(line.label, line.amountCents, line.suggestion?.categoryId, state.qualification)
    }

    fun closeRuleDraft() = ruleDraftSession.close()

    fun changeRulePattern(text: String) = ruleDraftSession.changePattern(text)

    fun changeRuleAmount(amount: String, tolerance: String) = ruleDraftSession.changeAmount(amount, tolerance)

    fun changeRuleCategory(categoryId: String?) = ruleDraftSession.changeCategory(categoryId)

    fun changeRuleQualification(qualification: Qualification) = ruleDraftSession.changeQualification(qualification)

    fun saveRule() = ruleDraftSession.save()

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearSortError() {
        _uiState.value = _uiState.value.copy(sortError = null)
    }

    fun clearJudgeError() {
        _uiState.value = _uiState.value.copy(judgeError = null)
    }
}

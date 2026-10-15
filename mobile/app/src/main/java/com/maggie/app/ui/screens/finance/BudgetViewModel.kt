package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.EnvelopeSplitChild
import com.maggie.app.data.api.EnvelopeSplitRequest
import com.maggie.app.data.api.RollOverRequest
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.SplitShares
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.NestedBudgetLine
import com.maggie.app.data.model.daughtersOf
import com.maggie.app.data.model.monthLabel
import com.maggie.app.data.model.moveBoundary
import com.maggie.app.data.model.nestBudgetLines
import com.maggie.app.data.model.overlappingMonth
import com.maggie.app.data.model.splitSharesOf
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A mother's ruler being edited: nothing is written until it is saved. */
data class SplitEditor(
    val motherId: String,
    val shares: SplitShares,
    val isSaving: Boolean = false,
)

data class BudgetUiState(
    val status: BudgetStatus? = null,
    val score: DailyScore? = null,
    val categories: List<Category> = emptyList(),
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
    val isLoading: Boolean = false,
    val isRollingOver: Boolean = false,
    val rollOverMessage: String? = null,
    val splitEditor: SplitEditor? = null,
    val error: String? = null,
) {
    /** The envelopes directly below a mother: what her ruler shares. */
    fun daughtersOf(mother: BudgetLine): List<BudgetLine> =
        daughtersOf(mother, status?.budgets.orEmpty())

    /** The envelopes in tree order, each nested under its mother. */
    val nestedBudgets: List<NestedBudgetLine>
        get() = nestBudgetLines(status?.budgets.orEmpty())

    /** The annual envelopes, apart from the month's budget, nested the same way. */
    val nestedAnnualBudgets: List<NestedBudgetLine>
        get() = nestBudgetLines(status?.outsideMonthlyBudget?.annual.orEmpty())
}

class BudgetViewModel(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BudgetUiState(year = today.year, month = today.monthValue),
    )
    val uiState: StateFlow<BudgetUiState> = _uiState

    init {
        refresh()
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.value = _uiState.value.copy(categories = categories.sortedBy { it.name })
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val state = _uiState.value
            try {
                val status = budgetRepository.getBudgetStatus(state.year, state.month).getOrThrow()
                _uiState.value = _uiState.value.copy(status = status, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }

            // The signal is a bonus on top of the budget: its failure must not
            // cost the user their budget view.
            budgetRepository.getDailyScore(state.year, state.month)
                .onSuccess { score -> _uiState.value = _uiState.value.copy(score = score) }
        }
    }

    /** Move the shown period by a number of months, then reload it. */
    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        _uiState.value = state.copy(year = shifted.year, month = shifted.monthValue)
        refresh()
    }

    /** Copies the previous month's envelopes onto the period being shown. */
    fun rollOverPreviousPeriod() {
        val state = _uiState.value
        val previous = LocalDate.of(state.year, state.month, 1).minusMonths(1)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRollingOver = true, rollOverMessage = null)
            try {
                val result = budgetRepository.rollOverEnvelopes(
                    RollOverRequest(
                        fromYear = previous.year,
                        fromMonth = previous.monthValue,
                        year = state.year,
                        month = state.month,
                    ),
                ).getOrThrow()

                _uiState.value = _uiState.value.copy(
                    isRollingOver = false,
                    rollOverMessage = if (result.created > 0) {
                        "${result.created} enveloppe(s) reconduite(s)"
                    } else {
                        "Rien à reconduire : les enveloppes sont déjà en place"
                    },
                )
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRollingOver = false, error = e.message)
            }
        }
    }

    /** Opens a mother's ruler for editing (the tablet; the phone only reads it). */
    fun startSplit(motherId: String) {
        val state = _uiState.value
        val mother = state.status?.budgets?.firstOrNull { it.id == motherId } ?: return
        val daughters = state.daughtersOf(mother)
        if (daughters.isEmpty()) return

        _uiState.value = state.copy(
            splitEditor = SplitEditor(motherId, splitSharesOf(daughters.map { it.amountCents }, mother.amountCents)),
        )
    }

    /** Moves the boundary after daughter [index]; the euros it trades are bounded by its neighbours. */
    fun moveSplitBoundary(index: Int, deltaCents: Int) {
        val editor = _uiState.value.splitEditor ?: return
        if (editor.isSaving) return
        _uiState.value = _uiState.value.copy(
            splitEditor = editor.copy(shares = editor.shares.moveBoundary(index, deltaCents)),
        )
    }

    fun cancelSplit() {
        _uiState.value = _uiState.value.copy(splitEditor = null)
    }

    /** Saves every daughter at once; a refusal (400) changes nothing. */
    fun saveSplit() {
        val state = _uiState.value
        val editor = state.splitEditor ?: return
        if (editor.isSaving) return
        val mother = state.status?.budgets?.firstOrNull { it.id == editor.motherId } ?: return
        val children = state.daughtersOf(mother).mapIndexed { i, daughter ->
            EnvelopeSplitChild(daughter.id, editor.shares.amounts[i])
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(splitEditor = editor.copy(isSaving = true))
            budgetRepository.splitEnvelope(editor.motherId, EnvelopeSplitRequest(children))
                .onSuccess {
                    _uiState.value = _uiState.value.copy(splitEditor = null)
                    refresh()
                }
                .onFailure { e ->
                    // Nothing was written, so the amounts on screen are still the stored ones.
                    val refused = e is ApiRefusalException && e.status == 400
                    _uiState.value = _uiState.value.copy(
                        splitEditor = null,
                        error = if (refused) SPLIT_REFUSED else e.message,
                    )
                }
        }
    }

    fun clearRollOverMessage() {
        _uiState.value = _uiState.value.copy(rollOverMessage = null)
    }

    fun createEnvelope(request: EnvelopeCreateRequest) {
        // The API refuses two seasons sharing a month: say which before sending (MAG-415).
        overlappingMonth(request.seasonalAmounts)?.let { month ->
            _uiState.value = _uiState.value.copy(
                error = "Deux saisons couvrent ${monthLabel(month).lowercase()} : une saison ne peut pas en chevaucher une autre.",
            )
            return
        }
        viewModelScope.launch {
            try {
                budgetRepository.createEnvelope(request).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Sets the threshold past which a sync makes Maggie interrupt; null is back to 80 %. */
    fun setAlertThreshold(id: String, percent: Int?) {
        viewModelScope.launch {
            try {
                budgetRepository.setAlertThreshold(id, percent).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteEnvelope(id: String) {
        viewModelScope.launch {
            try {
                budgetRepository.deleteEnvelope(id).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

private const val SPLIT_REFUSED = "La répartition dépasse le montant de l'enveloppe : rien n'a été modifié."

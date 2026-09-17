package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.RollOverRequest
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class BudgetUiState(
    val status: BudgetStatus? = null,
    val score: DailyScore? = null,
    val categories: List<Category> = emptyList(),
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
    val isLoading: Boolean = false,
    val isRollingOver: Boolean = false,
    val rollOverMessage: String? = null,
    val error: String? = null,
)

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

    fun clearRollOverMessage() {
        _uiState.value = _uiState.value.copy(rollOverMessage = null)
    }

    fun createEnvelope(request: EnvelopeCreateRequest) {
        viewModelScope.launch {
            try {
                budgetRepository.createEnvelope(request).getOrThrow()
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
}

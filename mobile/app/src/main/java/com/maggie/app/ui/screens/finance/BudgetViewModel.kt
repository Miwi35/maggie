package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Category
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class BudgetUiState(
    val status: BudgetStatus? = null,
    val categories: List<Category> = emptyList(),
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
    val isLoading: Boolean = false,
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
        }
    }

    /** Move the shown period by a number of months, then reload it. */
    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        _uiState.value = state.copy(year = shifted.year, month = shifted.monthValue)
        refresh()
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

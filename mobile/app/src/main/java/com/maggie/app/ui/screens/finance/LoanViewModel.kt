package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.LoanCreateRequest
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.Loan
import com.maggie.app.data.repository.LoanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LoanUiState(
    val loans: List<Loan> = emptyList(),
    val timeline: DebtTimeline? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class LoanViewModel(
    private val loanRepository: LoanRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoanUiState())
    val uiState: StateFlow<LoanUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val loans = loanRepository.getLoans().getOrThrow()
                _uiState.value = _uiState.value.copy(
                    loans = loans.sortedByDescending { it.priority },
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }

            // The schedule is a bonus on top of the list: losing it must not
            // cost the user their loans.
            loanRepository.getTimeline()
                .onSuccess { timeline -> _uiState.value = _uiState.value.copy(timeline = timeline) }
        }
    }

    fun createLoan(request: LoanCreateRequest) {
        viewModelScope.launch {
            try {
                loanRepository.createLoan(request).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteLoan(id: String) {
        viewModelScope.launch {
            try {
                loanRepository.deleteLoan(id).getOrThrow()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}

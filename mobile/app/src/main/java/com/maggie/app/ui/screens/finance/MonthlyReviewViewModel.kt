package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.repository.MonthlyReviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MonthlyReviewUiState(
    val review: MonthlyReview? = null,
    val year: Int = 0,
    val month: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class MonthlyReviewViewModel(
    private val reviewRepository: MonthlyReviewRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    // A review looks at the month just ended.
    private val initial = today.minusMonths(1)

    private val _uiState = MutableStateFlow(
        MonthlyReviewUiState(year = initial.year, month = initial.monthValue),
    )
    val uiState: StateFlow<MonthlyReviewUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val state = _uiState.value
            try {
                val review = reviewRepository.getReview(state.year, state.month).getOrThrow()
                _uiState.value = _uiState.value.copy(review = review, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        _uiState.value = state.copy(year = shifted.year, month = shifted.monthValue)
        refresh()
    }

    fun rate(transactionId: String, verdict: String) {
        viewModelScope.launch {
            try {
                reviewRepository.rate(transactionId, verdict).getOrThrow()
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

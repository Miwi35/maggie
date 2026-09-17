package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.repository.FinanceDashboardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class FinanceDashboardUiState(
    val dashboard: FinanceDashboard? = null,
    val year: Int = 0,
    val month: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class FinanceDashboardViewModel(
    private val dashboardRepository: FinanceDashboardRepository,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FinanceDashboardUiState(year = today.year, month = today.monthValue),
    )
    val uiState: StateFlow<FinanceDashboardUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val state = _uiState.value
            try {
                val dashboard = dashboardRepository.getDashboard(state.year, state.month).getOrThrow()
                _uiState.value = _uiState.value.copy(dashboard = dashboard, isLoading = false)
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
}

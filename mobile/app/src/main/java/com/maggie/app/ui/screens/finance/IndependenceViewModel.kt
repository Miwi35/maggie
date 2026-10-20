package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.repository.FinanceDashboardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class IndependenceUiState(
    val counter: IndependenceCounter? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** The independence counter has no endpoint of its own: it is the Planification tile of the dashboard. */
class IndependenceViewModel(
    private val dashboardRepository: FinanceDashboardRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(IndependenceUiState())
    val uiState: StateFlow<IndependenceUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val date = today()
            dashboardRepository.getDashboard(date.year, date.monthValue).fold(
                onSuccess = {
                    _uiState.value = IndependenceUiState(counter = it.tiles.planning.independence)
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "Indisponible")
                },
            )
        }
    }
}

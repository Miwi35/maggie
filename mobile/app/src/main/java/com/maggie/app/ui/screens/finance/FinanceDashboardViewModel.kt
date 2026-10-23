package com.maggie.app.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.TreasuryBannerState
import com.maggie.app.data.model.toBanner
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.data.repository.TreasuryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The rings, the curve and the tiles all come from [dashboard]: one answer, one month, one state. */
data class FinanceDashboardUiState(
    val dashboard: FinanceDashboard? = null,
    val year: Int = 0,
    val month: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Null when no overdraft is forecast, or when the outlook could not be loaded: the dashboard goes on without it.
    val treasuryBanner: TreasuryBannerState? = null,
    // The Gantt of the Planification tile (MAG-529): null until read, or when it could not be — the tile goes on without it.
    val planning: ProjectPlanning? = null,
)

class FinanceDashboardViewModel(
    private val dashboardRepository: FinanceDashboardRepository,
    private val treasuryRepository: TreasuryRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val projectRepository: ProjectRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FinanceDashboardUiState(year = today.year, month = today.monthValue),
    )
    val uiState: StateFlow<FinanceDashboardUiState> = _uiState

    private var treasuryJob: Job? = null
    private var planningJob: Job? = null
    private var dashboardJob: Job? = null

    init {
        refresh()
        refreshTreasury()
        refreshPlanning()
        subscribeToMercure()
    }

    // An account, a transaction, an envelope, a project or a scenario that changes moves the tiles, what is left to spend and the next project.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            merge(
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ACCOUNTS)),
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TRANSACTIONS)),
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ENVELOPES)),
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FINANCE_PROJECTS)),
                // The current scenario decides the next project of the home card (MAG-551).
                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.FINANCE_SCENARIOS)),
            )
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .collect {
                    refresh()
                    refreshPlanning()
                }
        }
    }

    fun refreshTreasury() {
        treasuryJob?.cancel()
        treasuryJob = viewModelScope.launch {
            val banner = treasuryRepository.getOutlook().getOrNull()?.toBanner()
            _uiState.value = _uiState.value.copy(treasuryBanner = banner)
        }
    }

    fun refreshPlanning() {
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            projectRepository.getProjectPlanning().onSuccess { planning ->
                _uiState.value = _uiState.value.copy(planning = planning)
            }
        }
    }

    /** Loads the month shown; an answer that comes after a newer request, or after a month change, is dropped. */
    fun refresh() {
        dashboardJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val state = _uiState.value
        dashboardJob = viewModelScope.launch {
            val result = dashboardRepository.getDashboard(state.year, state.month)
            currentCoroutineContext().ensureActive()
            result
                .onSuccess { dashboard ->
                    _uiState.value = _uiState.value.copy(dashboard = dashboard, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
                }
        }
    }

    fun shiftPeriod(months: Long) {
        val state = _uiState.value
        val shifted = LocalDate.of(state.year, state.month, 1).plusMonths(months)
        // The figures on screen belong to the month left: they must not stay while the new ones load.
        _uiState.value = state.copy(
            year = shifted.year,
            month = shifted.monthValue,
            dashboard = null,
            error = null,
        )
        refresh()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
